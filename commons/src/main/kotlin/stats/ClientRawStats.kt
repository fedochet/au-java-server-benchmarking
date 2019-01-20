package stats

data class ClientRawStats(
    val connectionStart: Long,
    val connectionEnd: Long,
    val requests: List<RequestStats>
)