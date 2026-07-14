package org.webservices.testrunner.suites

import org.webservices.testrunner.framework.*

suspend fun TestRunner.mastodonCommunicationTests() = suite("Mastodon Communication Tests") {
test("Mastodon recommendation seeder container is running") {
        val containerName = runtimeServiceContainerName("mastodon-recommendation-seeder")
        val result = ContainerCli.run(
            "inspect", "-f", "{{.State.Status}}", containerName
        )
        require(result.exitCode == 0) {
            "Unable to inspect $containerName container: ${result.output}"
        }
        require(result.output == "running") {
            "$containerName should be running, got: ${result.output}"
        }
        println("      ✓ mastodon-recommendation-seeder container running")
    }

    test("Mastodon native bootstrap recommendations resolve selected packs") {
        val ruby = """
            configured = Setting.bootstrap_timeline_accounts.to_s.split(",").map(&:strip).reject(&:empty?)
            unresolved = configured.filter_map do |handle|
              username, domain = handle.downcase.gsub(/\A@/, "").split("@", 2)
              account = Account.with_username(username).with_domain(domain).first
              handle if account.nil? || !account.discoverable? || account.suspended? || account.silenced? || account.moved?
            end

            payload = { configured: configured, unresolved: unresolved }
            puts payload.to_json
            exit(unresolved.empty? ? 0 : 42)
        """.trimIndent()

        val result = ContainerCli.run("exec", runtimeServiceContainerName("mastodon-web"), "bin/rails", "runner", ruby)
        require(result.exitCode == 0) {
            "Mastodon native bootstrap recommendations are not configured correctly: ${result.output}"
        }
        println("      ✓ Mastodon native bootstrap recommendations resolved selected packs: ${result.output}")
    }

    test("Mastodon follow seeding creates bootstrap follows for local users") {
        val ruby = """
            configured = Setting.bootstrap_timeline_accounts.to_s.split(",").map(&:strip).reject(&:empty?)
            sysadmin = Account.find_by(username: "sysadmin", domain: nil)
            raise "missing sysadmin account" if sysadmin.nil?

            following = sysadmin.following.map(&:acct)
            requested = sysadmin.follow_requests.map { |follow_request| follow_request.target_account.acct }
            seeded = (following + requested).uniq

            matched = configured.select do |handle|
              username, domain = handle.downcase.gsub(/\A@/, "").split("@", 2)
              seeded.any? do |seeded_handle|
                seeded_username, seeded_domain = seeded_handle.downcase.gsub(/\A@/, "").split("@", 2)
                seeded_username == username && seeded_domain == domain
              end
            end

            payload = {
              configured_count: configured.length,
              following_count: following.length,
              requested_count: requested.length,
              matched: matched,
              sample_following: following.first(10),
              sample_requested: requested.first(10),
            }
            puts payload.to_json
            exit(configured.empty? || matched.length == configured.length ? 0 : 42)
        """.trimIndent()

        val result = ContainerCli.run("exec", runtimeServiceContainerName("mastodon-web"), "bin/rails", "runner", ruby)
        require(result.exitCode == 0) {
            "Mastodon follow seeding did not create the expected bootstrap follows: ${result.output}"
        }
        println("      ✓ Mastodon follow seeding created selected bootstrap follows: ${result.output}")
    }
}
