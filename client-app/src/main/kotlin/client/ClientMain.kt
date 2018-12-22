package client

import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.ShutDownUrl
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.InetAddress

@Volatile
var session: Session? = null

data class SessionConfig(
    val serverAddress: InetAddress,
    val serverPort: Int,
    val numberOfElements: Int,
    val numberOfRequests: Int,
    val pauseDuration: Long
)

fun main(args: Array<String>) {
    val server = embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            jackson {
                enable(SerializationFeature.INDENT_OUTPUT) // Pretty Prints the JSON
            }
        }

        install(ShutDownUrl.ApplicationCallFeature) {
            shutDownUrl = "/shutdown"
            exitCodeSupplier = { 0 }
        }

        routing {
            post("/start") {
                val config = call.receive<SessionConfig>()
                session = Session(config).apply { start() }
                call.respond(HttpStatusCode.OK)
            }

            post("/stop") {
                val currentSession = session ?: throw IllegalStateException("No session is running!")
                session = null
                currentSession.stop()

                call.respond(HttpStatusCode.OK)
            }
        }
    }

    server.start(wait = false)
}

