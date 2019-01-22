package servers

import org.slf4j.LoggerFactory
import proto.IntArrayJob
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.channels.SelectionKey.OP_WRITE
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

private val logger = LoggerFactory.getLogger(NonBlockingServer::class.java)

class NonBlockingServer : ServerBase(), Runnable {
    private val executor = Executors.newFixedThreadPool(4)
    private val serverSocketChannel = ServerSocketChannel.open()

    private val readSelector = Selector.open()
    private val writeSelector = Selector.open()

    private val readRegistrationQueue = ConcurrentLinkedQueue<ReadRegistrationJob>()
    private val writeRegistrationQueue = ConcurrentLinkedQueue<WriteRegistrationJob>()

    private val acceptThread = Thread(this)

    override fun start(port: Int) {
        serverSocketChannel.bind(InetSocketAddress(port))
        acceptThread.start()

        Thread {
            try {
                while (!Thread.interrupted() && readSelector.isOpen) {
                    while (true) {
                        val (socketChannel, readerAttachment) = readRegistrationQueue.poll() ?: break
                        socketChannel.register(readSelector, OP_WRITE, readerAttachment)
                    }

                    val selected = readSelector.select()
                    if (selected == 0) continue

                    val selectedKeys: MutableIterator<SelectionKey> = readSelector.selectedKeys().iterator()

                    while (selectedKeys.hasNext()) {
                        val key = selectedKeys.next()
                        val socketChannel = key.socketChannel
                        val attachment = key.readerAttachment

                        val currentInfo = attachment.currentInfo
                        val arraySize = currentInfo?.arraySize

                        try {
                            if (arraySize == null) {
                                val requestStats = RequestStatsCollector()
                                val currentRequestInfo = CurrentRequestInfo(requestStats)
                                attachment.currentInfo = currentRequestInfo

                                requestStats.startRequest()
                                val read = socketChannel.read(attachment.sizeBuffer)

                                if (read == -1) {
                                    attachment.clientStatsCollector.disconnect()
                                    key.cancel()
                                    continue
                                }

                                if (attachment.sizeBuffer.isFull) {
                                    requestStats.startRequest()

                                    attachment.sizeBuffer.flip()
                                    val expectedArraySize = attachment.sizeBuffer.getInt()
                                    attachment.sizeBuffer.clear()
                                    currentRequestInfo.arraySize = expectedArraySize
                                    attachment.arrayBuffer = ByteBuffer.allocate(expectedArraySize)
                                }
                            } else {
                                val read = socketChannel.read(attachment.arrayBuffer)
                                if (read == -1) {
                                    attachment.clientStatsCollector.disconnect()
                                    key.cancel()
                                    continue
                                }

                                if (attachment.arrayBuffer.isFull) {
                                    val arrayJob = ByteArray(arraySize)

                                    attachment.arrayBuffer.flip()
                                    attachment.arrayBuffer.get(arrayJob)
                                    attachment.currentInfo = null
                                    attachment.arrayBuffer = EMPTY_BUFFER

                                    executor.submit {
                                        currentInfo.statsCollector.startJob()
                                        val result = performJob(arrayJob)
                                        currentInfo.statsCollector.finishJob()

                                        writeRegistrationQueue.add(WriteRegistrationJob(
                                            socketChannel,
                                            WriterAttachment(
                                                attachment.clientStatsCollector,
                                                currentInfo.statsCollector,
                                                result
                                            )
                                        ))
                                        writeSelector.wakeup()
                                    }
                                }
                            }
                        } catch (e: IOException) {
                            logger.warn("Error during reading from client", e)
                            attachment.clientStatsCollector.disconnect()
                            key.cancel()
                        }

                        selectedKeys.remove()
                    }
                }
            } catch (e: Exception) {
                logger.warn("Somethig gone wrong with reader thread", e)
            }
        }.start()

        Thread {
            try {
                while (!Thread.interrupted() && writeSelector.isOpen) {
                    while (true) {
                        val (socketChannel, writerAttachment) = writeRegistrationQueue.poll() ?: break
                        socketChannel.register(writeSelector, OP_WRITE, writerAttachment)
                    }

                    val selected = writeSelector.select()
                    if (selected == 0) continue

                    val selectedKeys: MutableIterator<SelectionKey> = writeSelector.selectedKeys().iterator()

                    while (selectedKeys.hasNext()) {
                        val key = selectedKeys.next()
                        val socketChannel = key.socketChannel
                        val attachment = key.writerAttachment

                        try {
                            socketChannel.write(attachment.message)
                            if (attachment.message.all { it.isFull }) {
                                attachment.requestStats.finishRequest()
                                attachment.clientStats.addRequest(attachment.requestStats.toRequestStatistics())
                                key.cancel()
                            }
                        } catch (e: IOException) {
                            logger.warn("Error during writing to client", e)
                            key.cancel()
                        }

                        selectedKeys.remove()
                    }
                }
            } catch (e: Exception) {
                logger.warn("Something gone wrong with writer thread", e)
            }
        }.start()
    }

    override fun shutdown() {
        runCatching { serverSocketChannel.close() }
        runCatching { readSelector.close() }
        runCatching { writeSelector.close() }
        executor.shutdown()
    }

    override fun run() {
        try {
            while (!Thread.interrupted() && serverSocketChannel.isOpen) {
                val clientSocketChannel = serverSocketChannel.accept()
                clientSocketChannel.configureBlocking(false)

                val clientStatsCollector = ClientStatsCollector()
                statsCollector.addCollector(clientStatsCollector)

                readRegistrationQueue.add(ReadRegistrationJob(
                    clientSocketChannel,
                    ReaderAttachment(clientStatsCollector)
                ))
                readSelector.wakeup()
            }
        } catch (e: AsynchronousCloseException) {
            logger.info("ServerSocketChannel is closed", e)
        } catch (e: Exception) {
            logger.warn("Something gone wrong with accepting thread", e)
        }
    }
}


private data class ReadRegistrationJob(
    val socketChannel: SocketChannel,
    val readerAttachment: ReaderAttachment
)

private data class WriteRegistrationJob(
    val socketChannel: SocketChannel,
    val writerAttachment: WriterAttachment
)

private class CurrentRequestInfo(var statsCollector: RequestStatsCollector, var arraySize: Int? = null)

private val EMPTY_BUFFER = ByteBuffer.allocate(0)

/**
 * Fields are not volatile because they can be changed only in one thread (reader thread).
 */
private class ReaderAttachment(val clientStatsCollector: ClientStatsCollector) {
    val sizeBuffer: ByteBuffer = ByteBuffer.allocate(4)
    var currentInfo: CurrentRequestInfo? = null
    var arrayBuffer: ByteBuffer = EMPTY_BUFFER
}

private class WriterAttachment(
    val clientStats: ClientStatsCollector,
    val requestStats: RequestStatsCollector,
    intArrayJob: IntArrayJob
) {
    val sizeBuffer: ByteBuffer = ByteBuffer.allocate(4)
    val arrayBuffer: ByteBuffer = ByteBuffer.allocate(intArrayJob.serializedSize)

    val message: Array<ByteBuffer> = arrayOf(sizeBuffer, arrayBuffer)

    init {
        sizeBuffer.putInt(intArrayJob.serializedSize)
        sizeBuffer.flip()

        arrayBuffer.put(intArrayJob.toByteArray())
        arrayBuffer.flip()
    }
}

private val SelectionKey.socketChannel get() = channel() as SocketChannel

private val SelectionKey.readerAttachment get() = attachment() as ReaderAttachment
private val SelectionKey.writerAttachment get() = attachment() as WriterAttachment

private val Buffer.isFull get() = position() == limit()
