package stats

data class RequestStats(
    val requestStart: Long,
    val jobStart: Long,
    val jobEnd: Long,
    val requestEnd: Long
)

val RequestStats.jobDuration get() = jobEnd - jobStart
val RequestStats.requestDuration get() = requestEnd - requestStart