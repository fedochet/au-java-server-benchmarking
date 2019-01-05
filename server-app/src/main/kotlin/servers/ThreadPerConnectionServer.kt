package servers

import org.slf4j.LoggerFactory
import proto.IntArrayJob
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.Exception
import java.net.*
import java.util.*

interface Server {
    fun start(port: Int)
    fun shutdown()
}

private val logger = LoggerFactory.getLogger(ThreadPerConnectionServer::class.java)

class ThreadPerConnectionServer : Server, Runnable {
    private val thread = Thread(this)
    private val serverSocket = ServerSocket()

    private val clients: MutableSet<ClientHandler> = Collections.newSetFromMap(WeakHashMap())
    override fun start(port: Int) {
        serverSocket.bind(InetSocketAddress(port))
        thread.start()
    }

    override fun run() {
        while (!Thread.interrupted()) {
            val client = serverSocket.accept()
            val handler = ClientHandler(client)
            synchronized(client) {
                clients.add(handler)
            }

            Thread(handler).start()
        }
    }

    override fun shutdown() {
        try {
            serverSocket.close()
        } catch (e: Exception) {
            logger.error("Error closing socket", e)
        }

        thread.interrupt()

        for (client in clients) {
            try {
                client.stop()
            } catch (e: Exception) {
                logger.error("Error stopping client $client", e)
            }
        }
    }
}

private class ClientHandler(private val clientSocket: Socket) : Runnable {
    val dataInputStream = DataInputStream(clientSocket.getInputStream())
    val dataOutputStream = DataOutputStream(clientSocket.getOutputStream())

    override fun run() {
        try {
            clientSocket.use {
                while (true) {
                    val requestSize = dataInputStream.readInt()
                    if (requestSize == -1) break

                    val buffer = ByteArray(requestSize)
                    dataInputStream.readFully(buffer)

                    val result = performRequest(buffer)

                    dataOutputStream.writeInt(result.serializedSize)
                    result.writeTo(dataOutputStream)
                    dataOutputStream.flush()
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling client", e)
        }
    }

    fun stop() {
        clientSocket.close()
    }
}

private fun performRequest(buffer: ByteArray): IntArrayJob {
    val arrayJob = IntArrayJob.parseFrom(buffer)
    val sorted = arrayJob.dataList.sorted()
    return IntArrayJob.newBuilder().addAllData(sorted).build()
}
