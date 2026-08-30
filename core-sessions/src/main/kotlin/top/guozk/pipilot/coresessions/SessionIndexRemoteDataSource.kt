package top.guozk.pipilot.coresessions

interface SessionIndexRemoteDataSource {
    suspend fun fetch(hostId: String): List<SessionGroup>
}
