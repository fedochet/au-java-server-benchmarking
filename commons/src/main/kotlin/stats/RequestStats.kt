package stats

data class RequestStats(
    val requestStart: Long,
    val jobStart: Long,
    val jobEnd: Long,
    val requestEnd: Long
)