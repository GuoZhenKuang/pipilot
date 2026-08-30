package top.guozk.pipilot.chat

data class ChatTurn(
    val key: String,
    val user: ChatTimelineItem.User?,
    val activity: List<ChatTimelineItem>,
    val sections: List<ChatTurnSection>,
    val runState: ChatTurnRunState,
) {
    val assistants: List<ChatTimelineItem.Assistant>
        get() = activity.filterIsInstance<ChatTimelineItem.Assistant>()

    val tools: List<ChatTimelineItem.Tool>
        get() = activity.filterIsInstance<ChatTimelineItem.Tool>()
}

sealed interface ChatTurnSection {
    val key: String

    data class Assistant(val item: ChatTimelineItem.Assistant) : ChatTurnSection {
        override val key: String = "assistant:${item.id}"
    }

    data class Tools(val items: List<ChatTimelineItem.Tool>) : ChatTurnSection {
        override val key: String = "tools:${items.first().id}"
    }
}

enum class ChatTurnRunState {
    COMPLETE,
    STREAMING,
    ERROR,
}

fun projectChatTurns(timeline: List<ChatTimelineItem>): List<ChatTurn> {
    if (timeline.isEmpty()) return emptyList()

    val turns = mutableListOf<ChatTurn>()
    var user: ChatTimelineItem.User? = null
    var activity = mutableListOf<ChatTimelineItem>()
    var key: String? = null

    fun appendTurn() {
        val turnKey = key ?: return
        turns +=
            ChatTurn(
                key = turnKey,
                user = user,
                activity = activity.toList(),
                sections = activity.toTurnSections(),
                runState = activity.toTurnRunState(),
            )
    }

    timeline.forEach { item ->
        if (item is ChatTimelineItem.User) {
            appendTurn()
            user = item
            activity = mutableListOf()
            key = "turn:${item.id}"
        } else {
            if (key == null) key = "orphan:${item.id}"
            activity += item
        }
    }
    appendTurn()
    return turns
}

private fun List<ChatTimelineItem>.toTurnSections(): List<ChatTurnSection> {
    val sections = mutableListOf<ChatTurnSection>()
    val pendingTools = mutableListOf<ChatTimelineItem.Tool>()

    fun appendTools() {
        if (pendingTools.isEmpty()) return
        sections += ChatTurnSection.Tools(pendingTools.toList())
        pendingTools.clear()
    }

    forEach { item ->
        when (item) {
            is ChatTimelineItem.Assistant -> {
                appendTools()
                sections += ChatTurnSection.Assistant(item)
            }
            is ChatTimelineItem.Tool -> pendingTools += item
            is ChatTimelineItem.User -> Unit
        }
    }
    appendTools()
    return sections
}

private fun List<ChatTimelineItem>.toTurnRunState(): ChatTurnRunState =
    when {
        filterIsInstance<ChatTimelineItem.Tool>().any { it.isError } -> ChatTurnRunState.ERROR
        any {
            when (it) {
                is ChatTimelineItem.Assistant -> it.isStreaming
                is ChatTimelineItem.Tool -> it.isStreaming
                is ChatTimelineItem.User -> false
            }
        } -> ChatTurnRunState.STREAMING
        else -> ChatTurnRunState.COMPLETE
    }
