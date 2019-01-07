package client

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.mainBody
import config.ClientConfig
import java.net.InetAddress

class SessionConfigArgs(parser: ArgParser) {
    val serverAddress: InetAddress by parser.storing("--address", help = "Address of the server") { InetAddress.getByName(this) }
    val serverPort: Int by parser.storing("--port", help = "Port of the server") { toInt() }
    val numberOfElements: Int by parser.storing("--N", help = "Number of elements in request") { toInt() }
    val numberOfRequests: Int by parser.storing("--X", help = "Number of requests to perform") { toInt() }
    val pauseDuration: Long by parser.storing("--delta", help = "Pause between requests, ms") { toLong() }
}

fun SessionConfigArgs.toSessionConfig() =
    ClientConfig(serverAddress, serverPort, numberOfElements, numberOfRequests, pauseDuration)

fun main(args: Array<String>) = mainBody {
    val sessionConfig = ArgParser(args).parseInto(::SessionConfigArgs).toSessionConfig()

    Session(sessionConfig).run()
}

