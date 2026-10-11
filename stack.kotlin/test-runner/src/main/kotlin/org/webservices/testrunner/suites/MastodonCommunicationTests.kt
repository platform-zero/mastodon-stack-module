package org.webservices.testrunner.suites

import java.nio.file.Path
import java.sql.DriverManager
import org.webservices.testrunner.framework.*

suspend fun TestRunner.mastodonCommunicationTests() = suite("Mastodon Communication Tests") {
    fun mastodonJdbcUrl(): String =
        "jdbc:postgresql://${System.getenv("POSTGRES_HOST") ?: "postgres-ssd"}:${System.getenv("POSTGRES_PORT") ?: "5432"}/mastodon"

    fun mastodonDbUser(): String = System.getenv("POSTGRES_MASTODON_USER") ?: "mastodon"

    fun mastodonDbPassword(): String = System.getenv("POSTGRES_MASTODON_PASSWORD").orEmpty()

    fun mastodonScalar(sql: String): Long =
        DriverManager.getConnection(mastodonJdbcUrl(), mastodonDbUser(), mastodonDbPassword()).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    require(rs.next()) { "Mastodon query returned no rows: $sql" }
                    rs.getLong(1)
                }
            }
        }

    test("Mastodon recommendation bootstrap configuration is present") {
        val runtimeRoot = System.getenv("TEST_RUNNER_RUNTIME_ROOT")?.takeIf { it.isNotBlank() } ?: "/runtime"
        val recommendationConfig = Path.of(
            runtimeRoot,
            "stack.config",
            "mastodon",
            "configure-bootstrap-recommendations.sh"
        )
        require(java.nio.file.Files.isRegularFile(recommendationConfig)) {
            "Mastodon recommendation bootstrap config is missing at $recommendationConfig"
        }
        println("      ✓ Mastodon recommendation bootstrap configuration is present")
    }

    fun selectedBootstrapHandles(): List<String> {
        val runtimeRoot = System.getenv("TEST_RUNNER_RUNTIME_ROOT")?.takeIf { it.isNotBlank() } ?: "/runtime"
        val fragments = Path.of(runtimeRoot, "stack.config", "mastodon", "bootstrap-follows.d")
        if (!java.nio.file.Files.isDirectory(fragments)) return emptyList()
        return java.nio.file.Files.list(fragments).use { paths ->
            paths.filter { java.nio.file.Files.isRegularFile(it) && it.toString().endsWith(".txt") }
                .sorted().toList().flatMap { java.nio.file.Files.readAllLines(it) }
                .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.distinct()
        }
    }

    fun requireNoEditorialDefaults() {
        require(mastodonScalar("""select count(*) from settings where var = 'bootstrap_timeline_accounts' and coalesce(value::text, '') !~ '^[[:space:]"''-]*$'""") == 0L) {
            "Mastodon has editorial defaults despite no selected follow packs"
        }
    }

    test("Mastodon native bootstrap recommendations resolve selected packs") {
        if (selectedBootstrapHandles().isEmpty()) {
            requireNoEditorialDefaults()
            println("      ✓ No follow packs selected; actual editorial defaults remain empty")
            return@test
        }
        val discoverableAccounts = mastodonScalar(
            "select count(*) from accounts where domain is not null and discoverable = true and suspended_at is null and silenced_at is null"
        )
        require(discoverableAccounts > 0) {
            "Mastodon has no discoverable remote accounts available for bootstrap recommendations"
        }
        println("      ✓ Mastodon has $discoverableAccounts discoverable remote accounts available for bootstrap recommendations")
    }

    test("Mastodon follow seeding creates bootstrap follows for local users") {
        if (selectedBootstrapHandles().isEmpty()) {
            requireNoEditorialDefaults()
            require(mastodonScalar("select count(*) from accounts where domain is null") > 0L) {
                "Mastodon has no native local accounts"
            }
            println("      ✓ Native local accounts exist without imposing unselected editorial follows")
            return@test
        }
        val sysadminFollows = mastodonScalar(
            """
            select count(*)
            from accounts local
            join follows follows on follows.account_id = local.id
            where local.username = 'sysadmin' and local.domain is null
            """.trimIndent()
        )
        require(sysadminFollows > 0) {
            "Mastodon bootstrap did not seed any follows for the sysadmin account"
        }
        println("      ✓ Mastodon follow relationship table is queryable for sysadmin ($sysadminFollows follows)")
    }
}
