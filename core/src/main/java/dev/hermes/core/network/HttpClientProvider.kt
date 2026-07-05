package dev.hermes.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object HttpClientProvider {

    fun create(baseUrl: String): HttpClient {
        return HttpClient(OkHttp) {
            expectSuccess = false

            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                    }
                )
            }

            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }

            defaultRequest {
                url(baseUrl)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                // Upstream treats missing Origin/Referer as non-browser (curl-equivalent).
                // Explicitly omit them to avoid CSRF validation failures.
                headers.remove(HttpHeaders.Origin)
                headers.remove("Referer")
            }
        }
    }
}
