package top.guozk.pipilot.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 平台语音听写适配器（继承原计划 016 的决策）：
 *
 * - 平台识别服务可能把音频传到设备外：使用前必须向用户披露，绝不声称本地处理。
 * - 听写只产出手稿草稿，绝不自动发送；识别内容不进日志/通知。
 * - 只有一个识别代次（generation）活跃；取消/销毁后的迟到回调一律忽略。
 * - 部分结果是临时预览，不进 SavedStateHandle；最终结果只插入一次。
 */
interface DictationRecognizer {
    val isAvailable: Boolean

    fun start()

    fun cancel()

    fun destroy()
}

class PlatformDictationRecognizer(
    private val context: Context,
    private val listener: Callbacks,
) : DictationRecognizer,
    RecognitionListener {
    interface Callbacks {
        fun onPartial(text: String)

        fun onFinal(text: String)

        fun onEnded(error: DictationError?)
    }

    private val recognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private var generation = 0

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start() {
        if (!isAvailable) {
            listener.onEnded(DictationError.UNAVAILABLE)
            return
        }
        generation += 1
        val currentGeneration = generation

        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

        recognizer.setRecognitionListener(this)
        activeGeneration = currentGeneration
        recognizer.startListening(intent)
    }

    @Volatile
    private var activeGeneration = 0

    override fun cancel() {
        generation += 1
        recognizer.cancel()
        listener.onEnded(null)
    }

    override fun destroy() {
        generation += 1
        recognizer.destroy()
    }

    private fun isActive(): Boolean = activeGeneration == generation

    override fun onPartialResults(partialResults: Bundle?) {
        if (!isActive()) return
        extractText(partialResults)?.let { listener.onPartial(it) }
    }

    override fun onResults(results: Bundle?) {
        if (!isActive()) return
        val text = extractText(results)
        if (text.isNullOrBlank()) {
            listener.onEnded(DictationError.NO_MATCH)
        } else {
            listener.onFinal(text)
            listener.onEnded(null)
        }
    }

    override fun onError(error: Int) {
        if (!isActive()) return
        listener.onEnded(DictationError.fromPlatform(error))
    }

    // ---- 未使用的 RecognitionListener 回调 ----
    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onEvent(
        eventType: Int,
        params: Bundle?,
    ) = Unit

    private fun extractText(bundle: Bundle?): String? =
        bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.take(MAX_RESULT_LENGTH)

    private companion object {
        /** 有界化识别结果，防止超长文本冲击草稿状态。 */
        const val MAX_RESULT_LENGTH = 4000
    }
}

enum class DictationError {
    UNAVAILABLE,
    NO_MATCH,
    BUSY,
    PERMISSION,
    NETWORK,
    GENERIC,

    companion object {
        fun fromPlatform(platformError: Int): DictationError =
            when (platformError) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NO_MATCH
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> BUSY
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> PERMISSION
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                -> NETWORK
                else -> GENERIC
            }
    }
}

/** 听写会话的 UI 状态（供 Compose 消费）。 */
data class DictationState(
    val isListening: Boolean = false,
    val partialText: String = "",
    /** 最近一次最终识别结果，供需要完整文本的调用方读取。 */
    val lastFinalText: String = "",
    val error: DictationError? = null,
) {
    val hasError: Boolean get() = error != null
}

/** 驱动听写状态机的轻量控制器：供 Composable 持有，生命周期跟随组合。 */
class DictationController(
    private val recognizer: DictationRecognizer,
) {
    private val _state = MutableStateFlow(DictationState())
    val state: StateFlow<DictationState> = _state.asStateFlow()

    fun start() {
        if (_state.value.isListening) return
        _state.value = DictationState(isListening = true)
        recognizer.start()
    }

    fun onPartial(text: String) {
        if (!_state.value.isListening) return
        _state.value = _state.value.copy(partialText = text)
    }

    fun onFinal(text: String) {
        if (!_state.value.isListening) return
        _state.value = _state.value.copy(partialText = "", lastFinalText = text)
    }

    fun onEnded(error: DictationError?) {
        _state.value = _state.value.copy(isListening = false, error = error, partialText = "")
    }

    /** 披露确认后的启动：识别服务不可用时给出 UNAVAILABLE，而不是静默失败。 */
    fun startAfterDisclosure(context: android.content.Context) {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            onEnded(DictationError.UNAVAILABLE)
        } else {
            start()
        }
    }

    fun cancel() {
        recognizer.cancel()
        onEnded(null)
    }

    fun consumeError(): DictationError? {
        val error = _state.value.error
        if (error != null) {
            _state.value = _state.value.copy(error = null)
        }
        return error
    }
}
