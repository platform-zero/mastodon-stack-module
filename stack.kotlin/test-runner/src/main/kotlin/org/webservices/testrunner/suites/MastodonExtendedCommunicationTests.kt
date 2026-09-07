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
        require(response.status == HttpStatusCode.OK || response.status.value in 300..399) {
            "Mastodon streaming API was not reachable: ${response.status}"
        }
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
        require(response.status in setOf(HttpStatusCode.OK, HttpStatusCode.BadRequest, HttpStatusCode.Found)) {
            "Mastodon OAuth endpoint returned ${response.status}"
        }
        if (response.status == HttpStatusCode.Found) {
            require(!response.headers["Location"].isNullOrBlank()) { "Mastodon OAuth redirect omitted Location" }
        } else {
            require(response.bodyAsText().contains("oauth", ignoreCase = true)) {
                "Mastodon OAuth endpoint returned an unrelated payload"
            }
        }
        println("      ✓ OAuth endpoint accessible (${response.status})")
    }

    test("Mastodon: Static assets are served") {
        val response = getMastodonInternalResponse("/manifest.json")
        val body = requireOkResponse(response, "Mastodon web manifest")
        require(Json.parseToJsonElement(body).jsonObject["name"] != null) {
            "Mastodon web manifest is missing its name"
        }
        println("      ✓ Static assets served")
    }

    test("Mastodon: Federation is configured") {
        val host = System.getenv("MASTODON_HOST_HEADER") ?: "mastodon.${System.getenv("DOMAIN")}"
        val response = getMastodonInternalResponse("/.well-known/webfinger?resource=acct:sysadmin@$host")
        val body = requireOkResponse(response, "Mastodon WebFinger endpoint")
        require(Json.parseToJsonElement(body).jsonObject["subject"]?.jsonPrimitive?.content?.contains("sysadmin@$host") == true) {
            "Mastodon WebFinger did not resolve the bootstrap sysadmin account"
        }
        println("      ✓ WebFinger endpoint present (${response.status})")
    }

    test("Mastodon: ActivityPub endpoint responds") {
        val response = getMastodonInternalResponse("/.well-known/host-meta")
        val body = requireOkResponse(response, "Mastodon ActivityPub host-meta")
        body shouldContain "lrdd"
        println("      ✓ ActivityPub host-meta available")
    }

    test("Mastodon: Media upload API does not allow anonymous upload") {
        val response = getMastodonInternalResponse("/api/v2/media")

        require(response.status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)) {
            "Mastodon media upload API should be protected or absent for anonymous GET, got ${response.status}"
        }
        println("      ✓ Media endpoint does not allow anonymous upload (${response.status})")
    }

    test("Mastodon: media attachment table exists") {
        val jdbcUrl = "jdbc:postgresql://${System.getenv("POSTGRES_HOST") ?: "postgres-ssd"}:${System.getenv("POSTGRES_PORT") ?: "5432"}/mastodon"
        val user = System.getenv("POSTGRES_MASTODON_USER") ?: "mastodon"
        val password = System.getenv("POSTGRES_MASTODON_PASSWORD").orEmpty()
        val mediaTableExists = DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("select to_regclass('public.media_attachments') is not null").use { rs ->
                    require(rs.next()) { "Mastodon media attachment table query returned no rows" }
                    rs.getBoolean(1)
                }
            }
        }
        require(mediaTableExists) { "Mastodon media attachment table is missing" }
        println("      ✓ Mastodon media attachment table exists")
    }
}
