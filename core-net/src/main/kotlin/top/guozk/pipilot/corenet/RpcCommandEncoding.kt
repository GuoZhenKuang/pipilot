package top.guozk.pipilot.corenet

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import top.guozk.pipilot.corerpc.AbortBashCommand
import top.guozk.pipilot.corerpc.AbortCommand
import top.guozk.pipilot.corerpc.AbortRetryCommand
import top.guozk.pipilot.corerpc.BashCommand
import top.guozk.pipilot.corerpc.CloneCommand
import top.guozk.pipilot.corerpc.CompactCommand
import top.guozk.pipilot.corerpc.CycleModelCommand
import top.guozk.pipilot.corerpc.CycleThinkingLevelCommand
import top.guozk.pipilot.corerpc.ExportHtmlCommand
import top.guozk.pipilot.corerpc.ExtensionUiResponseCommand
import top.guozk.pipilot.corerpc.FollowUpCommand
import top.guozk.pipilot.corerpc.ForkCommand
import top.guozk.pipilot.corerpc.GetAvailableModelsCommand
import top.guozk.pipilot.corerpc.GetCommandsCommand
import top.guozk.pipilot.corerpc.GetEntriesCommand
import top.guozk.pipilot.corerpc.GetForkMessagesCommand
import top.guozk.pipilot.corerpc.GetLastAssistantTextCommand
import top.guozk.pipilot.corerpc.GetMessagesCommand
import top.guozk.pipilot.corerpc.GetSessionStatsCommand
import top.guozk.pipilot.corerpc.GetStateCommand
import top.guozk.pipilot.corerpc.GetTreeCommand
import top.guozk.pipilot.corerpc.NewSessionCommand
import top.guozk.pipilot.corerpc.PromptCommand
import top.guozk.pipilot.corerpc.RpcCommand
import top.guozk.pipilot.corerpc.SetAutoCompactionCommand
import top.guozk.pipilot.corerpc.SetAutoRetryCommand
import top.guozk.pipilot.corerpc.SetFollowUpModeCommand
import top.guozk.pipilot.corerpc.SetModelCommand
import top.guozk.pipilot.corerpc.SetSessionNameCommand
import top.guozk.pipilot.corerpc.SetSteeringModeCommand
import top.guozk.pipilot.corerpc.SetThinkingLevelCommand
import top.guozk.pipilot.corerpc.SteerCommand
import top.guozk.pipilot.corerpc.SwitchSessionCommand

private typealias RpcCommandEncoder = (Json, RpcCommand) -> JsonObject

private val rpcCommandEncoders: Map<Class<out RpcCommand>, RpcCommandEncoder> =
    mapOf(
        PromptCommand::class.java to typedEncoder(PromptCommand.serializer()),
        SteerCommand::class.java to typedEncoder(SteerCommand.serializer()),
        FollowUpCommand::class.java to typedEncoder(FollowUpCommand.serializer()),
        AbortCommand::class.java to typedEncoder(AbortCommand.serializer()),
        AbortRetryCommand::class.java to typedEncoder(AbortRetryCommand.serializer()),
        GetStateCommand::class.java to typedEncoder(GetStateCommand.serializer()),
        GetMessagesCommand::class.java to typedEncoder(GetMessagesCommand.serializer()),
        GetEntriesCommand::class.java to typedEncoder(GetEntriesCommand.serializer()),
        GetTreeCommand::class.java to typedEncoder(GetTreeCommand.serializer()),
        SwitchSessionCommand::class.java to typedEncoder(SwitchSessionCommand.serializer()),
        SetSessionNameCommand::class.java to typedEncoder(SetSessionNameCommand.serializer()),
        GetForkMessagesCommand::class.java to typedEncoder(GetForkMessagesCommand.serializer()),
        ForkCommand::class.java to typedEncoder(ForkCommand.serializer()),
        CloneCommand::class.java to typedEncoder(CloneCommand.serializer()),
        ExportHtmlCommand::class.java to typedEncoder(ExportHtmlCommand.serializer()),
        CompactCommand::class.java to typedEncoder(CompactCommand.serializer()),
        CycleModelCommand::class.java to typedEncoder(CycleModelCommand.serializer()),
        CycleThinkingLevelCommand::class.java to typedEncoder(CycleThinkingLevelCommand.serializer()),
        SetThinkingLevelCommand::class.java to typedEncoder(SetThinkingLevelCommand.serializer()),
        ExtensionUiResponseCommand::class.java to typedEncoder(ExtensionUiResponseCommand.serializer()),
        NewSessionCommand::class.java to typedEncoder(NewSessionCommand.serializer()),
        GetCommandsCommand::class.java to typedEncoder(GetCommandsCommand.serializer()),
        GetLastAssistantTextCommand::class.java to typedEncoder(GetLastAssistantTextCommand.serializer()),
        BashCommand::class.java to typedEncoder(BashCommand.serializer()),
        AbortBashCommand::class.java to typedEncoder(AbortBashCommand.serializer()),
        GetSessionStatsCommand::class.java to typedEncoder(GetSessionStatsCommand.serializer()),
        GetAvailableModelsCommand::class.java to typedEncoder(GetAvailableModelsCommand.serializer()),
        SetModelCommand::class.java to typedEncoder(SetModelCommand.serializer()),
        SetAutoCompactionCommand::class.java to typedEncoder(SetAutoCompactionCommand.serializer()),
        SetAutoRetryCommand::class.java to typedEncoder(SetAutoRetryCommand.serializer()),
        SetSteeringModeCommand::class.java to typedEncoder(SetSteeringModeCommand.serializer()),
        SetFollowUpModeCommand::class.java to typedEncoder(SetFollowUpModeCommand.serializer()),
    )

fun encodeRpcCommand(
    json: Json,
    command: RpcCommand,
): JsonObject {
    val basePayload = serializeRpcCommand(json, command)

    return buildJsonObject {
        basePayload.forEach { (key, value) ->
            put(key, value)
        }

        if (!basePayload.containsKey("type")) {
            put("type", command.type)
        }

        val commandId = command.id
        if (commandId != null && !basePayload.containsKey("id")) {
            put("id", commandId)
        }
    }
}

private fun serializeRpcCommand(
    json: Json,
    command: RpcCommand,
): JsonObject {
    val encoder =
        rpcCommandEncoders[command.javaClass]
            ?: error("Unsupported RPC command type: ${command::class.qualifiedName}")

    return encoder(json, command)
}

@Suppress("UNCHECKED_CAST")
private fun <T : RpcCommand> typedEncoder(serializer: KSerializer<T>): RpcCommandEncoder {
    return { currentJson, currentCommand ->
        currentJson.encodeToJsonElement(serializer, currentCommand as T).jsonObject
    }
}
