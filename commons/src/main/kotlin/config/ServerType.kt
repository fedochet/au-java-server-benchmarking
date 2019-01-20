package config

enum class ServerType {
    THREAD_PER_CONNECTION,
    THREAD_PLUS_POOL,
    NON_BLOCKING
}