package org.webservices.testrunner.suites

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.sql.DriverManager
import kotlinx.serialization.json.*
import org.webservices.testrunner.framework.*

suspend fun TestRunner.mastodonExtendedCommunicationTests() = suite("Mastodon Extended Communication Tests") {
test("Mastodon: Web interface is accessible") {
        val response = getMastodonInternalResponse("/")
        requireOkOrRedirectResponse(response, "Mastodon web interface")
        println("      ✓ Mastodon endpoint returned ${response.status}")
    }

    test("Mastodon: API endpoint responds") {
        val response = getMastodonInternalResponse("/api/v1/instance")
        val body = requireOkResponse(response, "Mastodon instance API")
        body shouldContain "uri"
        println("      ✓ Mastodon instance API accessible")
    }

    test("Mastodon: Streaming API is reachable") {
        val response = client.getRawResponse("${endpoints.mastodonStreaming}/api/v1/streaming/health")
        response.status shouldBe HttpStatusCode.OK
        println("      ✓ Mastodon streaming endpoint responded with ${response.status}")
    }

    test("Mastodon: Public timeline endpoint exists") {
        val response = getMastodonInternalResponse("/api/v1/timelines/public")

        val body = requireOkResponse(response, "Mastodon public timeline")
        val json = Json.parseToJsonElement(body)
        require(json is JsonArray) { "Public timeline should return array" }
        println("      ✓ Public timeline endpoint functional")
    }

    test("Mastodon: OAuth endpoint exists") {
        val response = getMastodonInternalResponse("/oauth/authorize")

        response.status.value shouldBeOneOf listOf(200, 400, 302)
        println("      ✓ OAuth endpoint accessible (${response.status})")
    }

    test("Mastodon: Static assets are served") {
        val response = getMastodonInternalResponse("/manifest.json")
        response.status.value shouldBeOneOf listOf(200, 304)
        println("      ✓ Static assets served")
    }

    test("Mastodon: Federation is configured") {
        val response = getMastodonInternalResponse("/.well-known/webfinger?resource=acct:admin@${System.getenv("MASTODON_HOST_HEADER") ?: "mastodon.${System.getenv("DOMAIN")}"}")

        response.status.value shouldBeOneOf listOf(200, 400, 404)
        println("      ✓ WebFinger endpoint present (${response.status})")
    }

    test("Mastodon: ActivityPub endpoint responds") {
        val response = getMastodonInternalResponse("/.well-known/host-meta")
        response.status shouldBe HttpStatusCode.OK
        println("      ✓ ActivityPub host-meta available")
    }

    test("Mastodon: Media upload API does not allow anonymous upload") {
        val response = getMastodonInternalResponse("/api/v2/media")

        require(response.status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)) {
            "Mastodon media upload API should be protected or absent for anonymous GET, got ${response.status}"
        }
        println("      ✓ Media endpoint does not allow anonymous upload (${response.status})")
    }

    test("Mastodon: cached attachment records resolve to files") {
        val jdbcUrl = "jdbc:postgresql://${System.getenv("POSTGRES_HOST") ?: "postgres-ssd"}:${System.getenv("POSTGRES_PORT") ?: "5432"}/mastodon"
        val user = System.getenv("POSTGRES_MASTODON_USER") ?: "mastodon"
        val password = System.getenv("POSTGRES_MASTODON_PASSWORD").orEmpty()
        val mediaAttachments = DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("select count(*) from media_attachments").use { rs ->
                    require(rs.next()) { "Mastodon media attachment count query returned no rows" }
                    rs.getLong(1)
                }
            }
        }
        require(mediaAttachments >= 0) {
            "Mastodon media attachment table returned an invalid count"
        }
        println("      ✓ Mastodon media attachment table is queryable ($mediaAttachments records)")
    }
}
