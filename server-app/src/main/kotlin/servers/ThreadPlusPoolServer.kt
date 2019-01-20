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

            val thread = Thread {
                val responseExecutor = Executors.newSingleThreadExecutor()
                try {
                    val dataOutputStream = DataOutputStream(client.getOutputStream())
                    val dataInputStream = DataInputStream(client.getInputStream())

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

                            executor.submit {
                                requestStats.startJob()
                                val result = performJob(buffer)
                                requestStats.finishJob()

                                responseExecutor.submit {
                                    try {
                                        result.writeTo(dataOutputStream)
                                    } catch (e: Exception) {
                                        logger.error("Error responding to client", e)
                                    } finally {
                                        requestStats.finishRequest()
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