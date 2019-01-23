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

    private val clients: MutableSet<Worker> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    override fun start(port: Int) {
        serverSocket.bind(InetSocketAddress(port))
        thread.start()
    }

    override fun run() {
        while (!Thread.interrupted() && !serverSocket.isClosed) {
            val client = serverSocket.accept()
            val handler = Worker(client)
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

    private class Worker(private val clientSocket: Socket) : Runnable {
        val dataInputStream = DataInputStream(clientSocket.getInputStream())
        val dataOutputStream = DataOutputStream(clientSocket.getOutputStream())

        val clientStatsCollector = ClientStatsCollector()

        override fun run() {
            try {
                while (true) {
                    val requestSize = try {
                        dataInputStream.readInt()
                    } catch (e: EOFException) { // socket is closed
                        break
                    }

                    val buffer = ByteArray(requestSize)
                    dataInputStream.readFully(buffer)

                    val requestStats = RequestStatsCollector()
                    requestStats.startRequest()

                    requestStats.startJob()
                    val result = performJob(buffer)
                    requestStats.finishJob()

                    dataOutputStream.writeInt(result.serializedSize)
                    result.writeTo(dataOutputStream)
                    dataOutputStream.flush()

                    requestStats.finishRequest()

                    clientStatsCollector.addRequest(requestStats.toRequestStatistics())
                }
            } catch (e: Exception) {
                logger.error("Error handling client", e)
            } finally {
                runCatching { clientSocket.close() }
                clientStatsCollector.disconnect()
            }
        }

        fun stop() {
            clientSocket.close()
        }
    }
}
