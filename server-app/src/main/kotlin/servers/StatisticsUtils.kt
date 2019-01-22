package servers

import org.apache.commons.lang3.time.StopWatch
import stats.ClientRawStats
import stats.RequestStats
import stats.SessionRawStats
import java.util.concurrent.ConcurrentLinkedQueue

class SessionStatsCollector {
    private val clients = ConcurrentLinkedQueue<ClientStatsCollector>()

    fun addCollector(collector: ClientStatsCollector) {
        clients.add(collector)
    }

    fun clear() {
        clients.clear()
    }

    fun toSessionStats(): SessionRawStats = SessionRawStats(clients.map { it.toClientStats() })
}

class ClientStatsCollector {
    private val timer: StopWatch = StopWatch.createStarted()

    private val requests = ConcurrentLinkedQueue<RequestStats>()

    fun disconnect() {
        timer.stop()
    }

    fun addRequest(requestStats: RequestStats) {
        requests.add(requestStats)
    }

    fun toClientStats(): ClientRawStats = ClientRawStats(timer.startTime, timer.stopTime, requests.toList())
}

class RequestStatsCollector {
    @Volatile
    private var requestStart: Long? = null
    @Volatile
    private var jobStart: Long? = null
    @Volatile
    private var jobEnd: Long? = null
    @Volatile
    private var requestEnd: Long? = null

    fun startRequest() {
        requestStart = System.currentTimeMillis()
    }
    fun startJob() {
        jobStart = System.currentTimeMillis()
    }
    fun finishJob() {
        jobEnd = System.currentTimeMillis()
    }
    fun finishRequest() {
        requestEnd = System.currentTimeMillis()
    }

    fun toRequestStatistics(): RequestStats = RequestStats(
        requestStart!!,
        jobStart!!,
        jobEnd!!,
        requestEnd!!
    )
}

private val StopWatch.stopTime get() = startTime + time
