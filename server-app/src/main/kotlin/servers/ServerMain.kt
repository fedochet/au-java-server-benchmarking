package servers

import com.fasterxml.jackson.databind.SerializationFeature
import config.ServerConfig
import config.ServerType
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.event.Level

@Volatile
private var currentServer: Server? = null

fun main(args: Array<String>) {
    val server = embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            jackson {
                enable(SerializationFeature.INDENT_OUTPUT) // Pretty Prints the JSON
            }
        }

        install(CallLogging) {
            level = Level.INFO
        }

        routing {
            get("/status") {
                call.respond(HttpStatusCode.OK)
            }

            post("/server/start") {
                if (currentServer != null) {
                    call.respond(HttpStatusCode.BadRequest, "Server is already running")
                    return@post
                }

                val config = call.receive<ServerConfig>()
                val server: Server = when (config.serverType) {
                    ServerType.THREAD_PER_CONNECTION -> ThreadPerConnectionServer()
                    ServerType.THREAD_PLUS_POOL -> ThreadPlusPoolServer()
                    ServerType.NON_BLOCKING -> NonBlockingServer()
                }

                currentServer = server
                server.start(config.port)
                call.respond(HttpStatusCode.OK, Unit)
            }

            post("/server/stop") {
                currentServer?.shutdown()
                currentServer = null
                call.respond(HttpStatusCode.OK)
            }

            get("/stats/get") {
                val currentStats = currentServer?.getStats()
                if (currentStats != null) {
                    call.respond(currentStats)
                } else {
                    call.respond(HttpStatusCode.BadRequest, "No server is running right now")
                }
            }

            post("/stats/clear") {
                currentServer?.clearStats()
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    server.start(wait = true)
}