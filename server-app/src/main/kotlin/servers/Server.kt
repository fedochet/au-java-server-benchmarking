package servers

import proto.IntArrayJob
import stats.SessionRawStats

interface Server {
    fun start(port: Int)
    fun shutdown()
    fun getStats(): SessionRawStats
    fun clearStats()
}

abstract class ServerBase : Server {
    protected val statsCollector = SessionStatsCollector()
    override fun getStats(): SessionRawStats = statsCollector.toSessionStats()
    override fun clearStats() {
        statsCollector.clear()
    }
}

internal fun performJob(buffer: ByteArray): IntArrayJob {
    val arrayJob = IntArrayJob.parseFrom(buffer)
    val sorted = arrayJob.dataList.insertionSorted()
    return IntArrayJob.newBuilder().addAllData(sorted).build()
}
