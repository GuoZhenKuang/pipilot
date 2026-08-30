package top.guozk.pipilot.sessions

interface ClientIdentityStore {
    fun getClientId(): String
}
