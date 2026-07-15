package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MastodonAuthHardeningTest {

    @Test
    fun `mastodon oidc state and nonce protections remain enabled and host filtering is not globally disabled`() {
        val mastodonEnv = repoFileText("stack.config/mastodon/mastodon.env")
        val mastodonRuntime = repoFileText("stack.runtime.yaml")
        val oidcInitializer = repoFileText("stack.config/mastodon/zz_webservices_oidc_state.rb")
        val sessionInitializer = repoFileText("stack.config/mastodon/zz_webservices_session_store.rb")

        assertTrue(mastodonEnv.contains("OIDC_REQUIRE_STATE=true"))
        assertTrue(mastodonEnv.contains("OIDC_SEND_NONCE=true"))
        assertFalse(mastodonEnv.contains("OIDC_REQUIRE_STATE=false"))
        assertFalse(mastodonEnv.contains("OIDC_SEND_NONCE=false"))

        assertTrue(
            Regex("""mastodon-web:[\s\S]*OIDC_ENABLED:\s*"true"[\s\S]*OIDC_CLIENT_SECRET:\s*"\$\{MASTODON_OAUTH_SECRET\}"[\s\S]*OMNIAUTH_ONLY:\s*"true"""")
                .containsMatchIn(mastodonRuntime),
            "mastodon-web must receive native OIDC env vars from stack.runtime.yaml"
        )
        assertTrue(
            Regex("""mastodon-sidekiq:[\s\S]*OIDC_ENABLED:\s*"true"[\s\S]*OIDC_CLIENT_SECRET:\s*"\$\{MASTODON_OAUTH_SECRET\}"[\s\S]*OMNIAUTH_ONLY:\s*"true"""")
                .containsMatchIn(mastodonRuntime),
            "mastodon-sidekiq must receive native OIDC env vars from stack.runtime.yaml"
        )

        assertFalse(mastodonRuntime.contains("DISABLE_HOST_CHECK: \"true\""))
        assertFalse(mastodonRuntime.contains("DANGEROUSLY_DISABLE_HOST_FILTERING: \"true\""))
        assertFalse(mastodonRuntime.contains("ACTION_DISPATCH_HOSTS_PERMIT_ALL: \"true\""))

        assertTrue(oidcInitializer.contains("oidc_config.options[:require_state] = true"))
        assertTrue(oidcInitializer.contains("oidc_config.options[:send_state] = true"))
        assertTrue(oidcInitializer.contains("oidc_config.options[:send_nonce] = true"))
        assertFalse(oidcInitializer.contains("def valid_state?"))
        assertFalse(oidcInitializer.contains("options.send_state = false"))
        assertFalse(oidcInitializer.contains("options.require_state = false"))

        assertTrue(mastodonEnv.contains("SESSION_COOKIE_SAMESITE=lax"))
        assertTrue(sessionInitializer.contains("ENV.fetch('SESSION_COOKIE_SAMESITE', 'lax')"))
        assertTrue(sessionInitializer.contains("same_site: same_site"))
        assertFalse(sessionInitializer.contains("same_site: :none"))
    }

    @Test
    fun `mastodon persists federated media cache across web and sidekiq`() {
        val mastodonEnv = repoFileText("stack.config/mastodon/mastodon.env")
        val mastodonRuntime = repoFileText("stack.runtime.yaml")

        assertTrue(mastodonEnv.contains("AUTHORIZED_FETCH=false"))
        assertTrue(mastodonEnv.contains("LIMITED_FEDERATION_MODE=false"))
        assertTrue(mastodonRuntime.contains("mastodon_public_system:/opt/mastodon/public/system"))
        assertTrue(mastodonRuntime.contains("mastodon_public_system:"))
        assertTrue(mastodonRuntime.contains("configure-bootstrap-recommendations.sh:/opt/mastodon/bin/configure-bootstrap-recommendations.sh"))
        assertTrue(
            Regex("""mastodon-web:[\s\S]*configure-bootstrap-recommendations\.sh:/opt/mastodon/bin/configure-bootstrap-recommendations\.sh""")
                .containsMatchIn(mastodonRuntime)
        )
        assertTrue(
            Regex("""mastodon-recommendation-seeder:[\s\S]*mastodon_public_system:/opt/mastodon/public/system""")
                .containsMatchIn(mastodonRuntime)
        )
        assertFalse(mastodonEnv.contains("EXTRA_MEDIA_HOSTS=*"))
    }

    @Test
    fun `mastodon web and sidekiq authenticate to the provisioned smtp account`() {
        val mastodonRuntime = repoFileText("stack.runtime.yaml")

        listOf("mastodon-web", "mastodon-sidekiq").forEach { service ->
            val serviceBlock = runtimeServiceBlock(mastodonRuntime, service)
            assertTrue(serviceBlock.contains("SMTP_SERVER: \"mail.\${DOMAIN}\""))
            assertTrue(serviceBlock.contains("SMTP_LOGIN: \"mastodon@\${MAIL_DOMAIN}\""))
            assertTrue(serviceBlock.contains("SMTP_PASSWORD: \"\${MASTODON_SMTP_PASSWORD}\""))
            assertTrue(serviceBlock.contains("SMTP_OPENSSL_VERIFY_MODE: \"peer\""))
            assertTrue(serviceBlock.contains("- \"mail.\${DOMAIN}:host-gateway\""))
            assertFalse(serviceBlock.contains("SMTP_OPENSSL_VERIFY_MODE: \"none\""))
        }
    }

    @Test
    fun `mastodon recommendation bootstrap clears missing cache-backed attachment metadata`() {
        val bootstrap = repoFileText("stack.config/mastodon/configure-bootstrap-recommendations.sh")

        assertTrue(bootstrap.contains("public\", \"system\", \"cache"))
        assertTrue(bootstrap.contains("path.start_with?(cache_root)"))
        assertTrue(bootstrap.contains("file.clear"))
        assertTrue(bootstrap.contains("record.save!(validate: false)"))
        assertFalse(bootstrap.contains("path.present? && !File.exist?(path)\n\n        file.clear"))
    }

    private fun repoFileText(relativePath: String): String =
        TestSourceFiles.moduleText("mastodon", relativePath)

    private fun runtimeServiceBlock(runtime: String, service: String): String {
        val header = "  $service:"
        val start = runtime.indexOf(header)
        require(start >= 0) { "Missing runtime service: $service" }
        val nextService = Regex("(?m)^  [A-Za-z0-9_-]+:")
            .find(runtime, start + header.length)
            ?.range
            ?.first
            ?: runtime.length
        return runtime.substring(start, nextService)
    }

    private fun repoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("MODULE.bazel"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
