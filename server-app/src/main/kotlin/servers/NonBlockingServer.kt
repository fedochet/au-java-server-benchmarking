package servers

import org.slf4j.LoggerFactory
import proto.IntArrayJob
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.channels.SelectionKey.OP_READ
import java.nio.channels.SelectionKey.OP_WRITE
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

private val logger = LoggerFactory.getLogger(NonBlockingServer::class.java)

class NonBlockingServer : ServerBase(), Runnable {
    private val executor = Executors.newFixedThreadPool(4)
    private val serverSocketChannel = ServerSocketChannel.open()

    private val readSelector: Selector = Selector.open()
    private val writeSelector: Selector = Selector.open()

    private val readRegistrationQueue = ConcurrentLinkedQueue<ReadRegistrationJob>()
    private val writeRegistrationQueue = ConcurrentLinkedQueue<WriteRegistrationJob>()

    private val acceptThread = Thread(this)

    override fun start(port: Int) {
        serverSocketChannel.bind(InetSocketAddress(port))
        acceptThread.start()

        Thread {
            try {
                while (!Thread.interrupted() && readSelector.isOpen) {
                    readRegistrationQueue.drain { (socketChannel, clientStatsCollector) ->
                        try {
                            socketChannel.registerOnReading(readSelector, ReaderAttachment(clientStatsCollector))
                        } catch (e: RuntimeException) {
                            logger.warn("Cannot register channel in read selector", e)
                        }
                    }

                    val selected = readSelector.select()
                    if (selected == 0) continue

                    val selectedKeys: MutableIterator<SelectionKey> = readSelector.selectedKeys().iterator()

                    while (selectedKeys.hasNext()) {
                        val key = selectedKeys.next()
                        val socketChannel = key.socketChannel
                        val attachment = key.readerAttachment

                        val requestStatsCollector = attachment.currentInfo
                        val arraySize = attachment.arraySize

                        try {
                            if (arraySize == null) {

                                val read = socketChannel.read(attachment.sizeBuffer)

                                if (read == -1) {
                                    attachment.clientStatsCollector.disconnect()
                                    key.cancel()
                                    continue
                                }

                                if (attachment.sizeBuffer.isFull) {
                                    attachment.sizeBuffer.flip()
                                    val expectedArraySize = attachment.sizeBuffer.getInt()
                                    attachment.sizeBuffer.clear()
                                    attachment.arraySize = expectedArraySize
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
                                    requestStatsCollector.startRequest()

                                    val arrayJob = ByteArray(arraySize)

                                    attachment.arrayBuffer.flip()
                                    attachment.arrayBuffer.get(arrayJob)

                                    attachment.currentInfo = RequestStatsCollector()
                                    attachment.arraySize = null
                                    attachment.arrayBuffer = EMPTY_BUFFER

                                    executor.submit {
                                        requestStatsCollector.startJob()
                                        val result = performJob(arrayJob)
                                        requestStatsCollector.finishJob()

                                        writeRegistrationQueue.add(WriteRegistrationJob(
                                            socketChannel,
                                            WriterAttachment(
                                                attachment.clientStatsCollector,
                                                requestStatsCollector,
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
                    writeRegistrationQueue.drain { (socketChannel, writerAttachment) ->
                        try {
                            socketChannel.registerOnWriting(writeSelector, writerAttachment)
                        } catch (e: RuntimeException) {
                            logger.warn("Cannot register channel in write selector", e)
                        }
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
                            if (attachment.messageIsSent) {
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
                    clientStatsCollector
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
    val clientStatsCollector: ClientStatsCollector
)

private data class WriteRegistrationJob(
    val socketChannel: SocketChannel,
    val writerAttachment: WriterAttachment
)

private val EMPTY_BUFFER = ByteBuffer.allocate(0)

/**
 * Fields are not volatile because they can be changed only in one thread (reader thread).
 */
private class ReaderAttachment(val clientStatsCollector: ClientStatsCollector) {
    val sizeBuffer: ByteBuffer = ByteBuffer.allocate(4)
    var currentInfo: RequestStatsCollector = RequestStatsCollector()
    var arraySize: Int? = null
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
    val messageIsSent: Boolean get() = message.all { it.isFull }

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

private fun SocketChannel.registerOnWriting(selector: Selector, attachment: WriterAttachment) {
    this.register(selector, OP_WRITE, attachment)
}

private fun SocketChannel.registerOnReading(selector: Selector, attachment: ReaderAttachment) {
    this.register(selector, OP_READ, attachment)
}

private val Buffer.isFull get() = position() == limit()

private inline fun <T> Queue<T>.drain(action: (T) -> Unit) {
    while (true) {
        val next = this.poll() ?: return
        action(next)
    }
}