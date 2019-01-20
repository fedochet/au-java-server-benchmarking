package servers

import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.lang.Exception
import java.net.*
import java.util.*

private val logger = LoggerFactory.getLogger(ThreadPerConnectionServer::class.java)

class ThreadPerConnectionServer : ServerBase(), Runnable {
    private val thread = Thread(this)
    private val serverSocket = ServerSocket()

    private val clients: MutableSet<ClientHandler> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    override fun start(port: Int) {
        serverSocket.bind(InetSocketAddress(port))
        thread.start()
    }

    override fun run() {
        while (!Thread.interrupted() && !serverSocket.isClosed) {
            val client = serverSocket.accept()
            val handler = ClientHandler(client)
            statsCollector.addCollector(handler.clientStatsCollector)
            clients.add(handler)

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

        synchronized(clients) {
            for (client in clients) {
                try {
                    client.stop()
                } catch (e: Exception) {
                    logger.error("Error stopping client $client", e)
                }
            }
        }
    }
}

private class ClientHandler(private val clientSocket: Socket) : Runnable {
    val dataInputStream = DataInputStream(clientSocket.getInputStream())
    val dataOutputStream = DataOutputStream(clientSocket.getOutputStream())

    val clientStatsCollector = ClientStatsCollector()

    override fun run() {
        try {
            clientSocket.use {
                while (true) {
                    val requestStats = RequestStatsCollector()

                    val requestSize = try {
                        dataInputStream.readInt()
                    } catch (e: EOFException) { // socket is closed
                        break
                    }

                    requestStats.startRequest()

                    val buffer = ByteArray(requestSize)
                    dataInputStream.readFully(buffer)

                    requestStats.startJob()
                    val result = performJob(buffer)
                    requestStats.finishJob()

                    dataOutputStream.writeInt(result.serializedSize)
                    result.writeTo(dataOutputStream)
                    dataOutputStream.flush()

                    requestStats.finishRequest()

                    clientStatsCollector.addRequest(requestStats.toRequestStatistics())
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling client", e)
        } finally {
            clientStatsCollector.disconnect()
        }
    }

    fun stop() {
        clientSocket.close()
    }
}
