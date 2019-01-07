package config

import java.net.InetAddress

data class ClientConfig(
    val serverAddress: InetAddress,
    val serverPort: Int,
    val numberOfElements: Int,
    val numberOfRequests: Int,
    val pauseDuration: Long
)