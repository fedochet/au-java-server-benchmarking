package servers

import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.application.call
import io.ktor.application.install
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

enum class ServerType {
    SINGLE_THREAD,
    THREAD_PLUS_POOL,
    NON_BLOCKING
}

data class ServerConfig(val serverType: ServerType, val port: Int)

fun main(args: Array<String>) {
    val server = embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            jackson {
                enable(SerializationFeature.INDENT_OUTPUT) // Pretty Prints the JSON
            }
        }

        routing {
            get("/status") {
                call.respond(HttpStatusCode.OK)
            }

            post("/server/start") {
                val config = call.receive<ServerConfig>()
                println(config)
                call.respond(HttpStatusCode.OK)
            }

            post("/server/stop") {
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    server.start(wait = true)
}