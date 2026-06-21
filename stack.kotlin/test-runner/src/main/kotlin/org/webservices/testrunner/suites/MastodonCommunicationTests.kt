package org.webservices.testrunner.suites

import org.webservices.testrunner.framework.*

suspend fun TestRunner.mastodonCommunicationTests() = suite("Mastodon Communication Tests") {
test("Mastodon recommendation seeder container is running") {
        val containerName = composeServiceContainerName("mastodon-recommendation-seeder")
        val result = DockerCli.run(
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

    test("Mastodon native bootstrap recommendations are configured") {
        val ruby = """
            configured = Setting.bootstrap_timeline_accounts.to_s.split(",").map(&:strip).reject(&:empty?)
            required = %w[
              wikimediafoundation@wikimedia.social
              internetarchive@mastodon.archive.org
              creativecommons@mastodon.social
              openstreetmap@en.osm.town
              ProPublica@newsie.social
              briankrebs@infosec.exchange
              NASA@mstdn.social
              b0rk@jvns.ca
              tomscott@mastodon.social
            ]

            matched = required.select do |handle|
              username, domain = handle.downcase.split("@", 2)
              configured.any? do |configured_handle|
                configured_username, configured_domain = configured_handle.downcase.gsub(/\A@/, "").split("@", 2)
                configured_username == username && configured_domain == domain
              end
            end
            missing = required - matched

            unresolved = configured.filter_map do |handle|
              username, domain = handle.downcase.gsub(/\A@/, "").split("@", 2)
              account = Account.with_username(username).with_domain(domain).first
              handle if account.nil? || !account.discoverable? || account.suspended? || account.silenced? || account.moved?
            end

            payload = { configured: configured, matched: matched, missing: missing, unresolved: unresolved }
            puts payload.to_json
            exit(configured.length >= 8 && matched.length >= 8 && unresolved.empty? ? 0 : 42)
        """.trimIndent()

        val result = DockerCli.run("exec", composeServiceContainerName("mastodon-web"), "bin/rails", "runner", ruby)
        require(result.exitCode == 0) {
            "Mastodon native bootstrap recommendations are not configured correctly: ${result.output}"
        }
        println("      ✓ Mastodon native bootstrap recommendations configured: ${result.output}")
    }
}
