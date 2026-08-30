package top.guozk.pipilot.ui.chat

enum class ActiveRunDeliveryMode {
    FOLLOW_UP,
    STEER,
}

data class ActiveRunSubmission(
    val message: String,
    val deliveryMode: ActiveRunDeliveryMode,
)

fun createActiveRunSubmission(
    draft: String,
    deliveryMode: ActiveRunDeliveryMode,
): ActiveRunSubmission? {
    val message = draft.trim()
    if (message.isEmpty()) return null
    return ActiveRunSubmission(message = message, deliveryMode = deliveryMode)
}
