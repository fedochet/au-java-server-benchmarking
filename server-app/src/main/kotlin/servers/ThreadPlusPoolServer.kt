package servers

import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

private val logger = LoggerFactory.getLogger(ThreadPlusPoolServer::class.java)

class ThreadPlusPoolServer : ServerBase(), Runnable {
    private val thread = Thread(this)

    private val serverSocket = ServerSocket()
    private val executor = Executors.newFixedThreadPool(4)

    override fun start(port: Int) {
        serverSocket.bind(InetSocketAddress(port))
        thread.start()
    }

    override fun run() {
        while (!Thread.interrupted() && !serverSocket.isClosed) {
            val client = serverSocket.accept()

            val clientStatsCollector = ClientStatsCollector()
            statsCollector.addCollector(clientStatsCollector)

            val thread = Thread {
                val responseExecutor = Executors.newSingleThreadExecutor()
                try {
                    val dataInputStream = DataInputStream(client.getInputStream())
                    val dataOutputStream = DataOutputStream(client.getOutputStream())

                    client.use {
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

                            executor.execute {
                                requestStats.startJob()
                                val result = performJob(buffer)
                                requestStats.finishJob()

                                responseExecutor.execute {
                                    try {
                                        dataOutputStream.writeInt(result.serializedSize)
                                        result.writeTo(dataOutputStream)
                                        dataOutputStream.flush()
                                    } catch (e: Exception) {
                                        logger.error("Error responding to client", e)
                                    } finally {
                                        requestStats.finishRequest()
                                        clientStatsCollector.addRequest(requestStats.toRequestStatistics())
                                    }
                                }
                            }
                        }
                    }

                } catch (e: Exception) {
                    logger.error("Error handling client", e)
                } finally {
                    clientStatsCollector.disconnect()
                    responseExecutor.shutdown()
                }
            }

            thread.start()
        }
    }

    override fun shutdown() {
        runCatching { serverSocket.close() }
        executor.shutdown()
    }
}