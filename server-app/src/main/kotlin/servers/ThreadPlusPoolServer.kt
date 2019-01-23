package servers

import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executor
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

            Thread(Worker(client, clientStatsCollector, executor)).start()
        }
    }

    override fun shutdown() {
        runCatching { serverSocket.close() }
        executor.shutdown()
    }

    private class Worker(
        private val client: Socket,
        private val statsCollector: ClientStatsCollector,
        private val executor: Executor
    ) : Runnable {

        private val dataInputStream = DataInputStream(client.getInputStream())
        private val dataOutputStream = DataOutputStream(client.getOutputStream())
        private val responseExecutor = Executors.newSingleThreadExecutor()

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

                    executor.execute {
                        requestStats.startJob()
                        val result = performJob(buffer)
                        requestStats.finishJob()

                        responseExecutor.execute {
                            try {
                                dataOutputStream.writeInt(result.serializedSize)
                                result.writeTo(dataOutputStream)
                                dataOutputStream.flush()

                                requestStats.finishRequest()
                                statsCollector.addRequest(requestStats.toRequestStatistics())
                            } catch (e: Exception) {
                                logger.error("Error responding to client", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Error handling client", e)
            } finally {
                runCatching { client.close() }
                statsCollector.disconnect()
                responseExecutor.shutdown()
            }
        }
    }
}

