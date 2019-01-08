package stats

data class ClientStats(
    val connectionStart: Long,
    val connectionEnd: Long,
    val requests: List<RequestStats>
)