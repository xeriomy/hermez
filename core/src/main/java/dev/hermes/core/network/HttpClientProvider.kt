package dev.hermes.core.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
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
        headers.remove(HttpHeaders.Referer)
      }
    }
  }
}