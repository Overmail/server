package dev.babies.overmail.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

const val AUTHENTICATION_NAME = "overmail-jwt"
val jwtAudience = System.getenv("JWT_AUDIENCE") ?: "overmail-audience"
val jwtIssuer = System.getenv("JWT_ISSUER") ?: "overmail-issuer"
val jwtSecret = System.getenv("JWT_SECRET") ?: "overmail-secret"

fun Application.configureAuthentication() {
    install(Authentication) {
        jwt(AUTHENTICATION_NAME) {
            realm = "Overmail"
            verifier(
                JWT
                .require(Algorithm.HMAC256(jwtSecret))
                .withAudience(jwtAudience)
                .withIssuer(jwtIssuer)
                .build()
            )

            authHeader { call ->
                val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.ifBlank { null }
                if (token != null) {
                    return@authHeader parseAuthorizationHeader(token)
                }

                val cookie = call.request.cookies["overmail-token"]?.ifBlank { null }
                if (cookie != null) {
                    return@authHeader parseAuthorizationHeader("Bearer $cookie")
                }

                return@authHeader null
            }

            validate { credential ->
                val userId = credential.payload.getClaim("id").asInt()
                if (userId != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }

            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    "You are not authorized to access this resource. Please log in."
                )
            }
        }
    }
}