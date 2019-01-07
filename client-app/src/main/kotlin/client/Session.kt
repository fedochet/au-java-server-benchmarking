package client

import config.ClientConfig
import org.slf4j.LoggerFactory
import proto.IntArrayJob
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

private val logger = LoggerFactory.getLogger(Session::class.java)

class Session(private val config: ClientConfig) : Runnable {
    private val socket = Socket(config.serverAddress, config.serverPort)
    private val dataInputStream = DataInputStream(socket.getInputStream())
    private val dataOutputStream = DataOutputStream(socket.getOutputStream())

    private val executedCount: AtomicInteger = AtomicInteger(0)

    override fun run() {
        try {
            socket.use {
                repeat(config.numberOfRequests) {
                    if (Thread.interrupted()) return
                    executeSingleJob()
                    executedCount.incrementAndGet()
                    Thread.sleep(config.pauseDuration)
                }
            }
        } catch (e: Exception) {
            logger.error("Something gone wrong during session", e)
        }
    }

    private fun executeSingleJob() {
        val arrayJob = generateArrayJob(config.numberOfElements)

        dataOutputStream.writeInt(arrayJob.serializedSize)
        arrayJob.writeTo(dataOutputStream)
        dataOutputStream.flush()

        val responseSize = dataInputStream.readInt()
        val buffer = ByteArray(responseSize)
        dataInputStream.readFully(buffer)
        val result = IntArrayJob.parseFrom(buffer)

        assert(result.dataCount == arrayJob.dataCount) {
            "Result array $result have size ${result.dataCount} instead of ${arrayJob.dataCount}"
        }
        assert(result.dataList.isSorted()) {
            "Result array $result is not sorted"
        }
    }
}

private val random = ThreadLocalRandom.current()

private fun generateArrayJob(count: Int): IntArrayJob {
    val builder = IntArrayJob.newBuilder()
    random.ints(count.toLong()).forEach { builder.addData(it) }
    return builder.build()
}

private fun <T : Comparable<T>> MutableList<T>.isSorted() =
    asSequence().windowed(2).all { (a, b) -> a <= b }