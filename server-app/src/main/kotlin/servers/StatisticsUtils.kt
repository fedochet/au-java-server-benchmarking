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
    private val requestTimer = StopWatch()
    private val jobTimer = StopWatch()

    fun startRequest() = requestTimer.start()
    fun startJob() = jobTimer.start()
    fun finishJob() = jobTimer.stop()
    fun finishRequest() = requestTimer.stop()

    fun toRequestStatistics(): RequestStats = RequestStats(
        requestTimer.startTime,
        jobTimer.startTime,
        jobTimer.stopTime,
        requestTimer.stopTime
    )
}

private val StopWatch.stopTime get() = startTime + time
