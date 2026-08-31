package top.guozk.pipilot.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationControllerTest {
    private class FakeRecognizer : DictationRecognizer {
        var startCount = 0
        var cancelCount = 0
        var destroyCount = 0

        override val isAvailable: Boolean = true

        override fun start() {
            startCount += 1
        }

        override fun cancel() {
            cancelCount += 1
        }

        override fun destroy() {
            destroyCount += 1
        }
    }

    @Test
    fun `start marks listening and duplicate start ignored`() {
        val recognizer = FakeRecognizer()
        val controller = DictationController(recognizer)

        controller.start()
        controller.start()

        assertEquals(1, recognizer.startCount)
        assertTrue(controller.state.value.isListening)
    }

    @Test
    fun `final result clears partial and ends listening`() {
        val controller = DictationController(FakeRecognizer())
        controller.start()
        controller.onPartial("你好")
        assertEquals("你好", controller.state.value.partialText)

        controller.onFinal("你好世界")
        controller.onEnded(null)

        assertFalse(controller.state.value.isListening)
        assertEquals("", controller.state.value.partialText)
    }

    @Test
    fun `error is exposed once via consumeError`() {
        val controller = DictationController(FakeRecognizer())
        controller.start()
        controller.onEnded(DictationError.NETWORK)

        assertFalse(controller.state.value.isListening)
        assertEquals(DictationError.NETWORK, controller.consumeError())
        assertNull(controller.consumeError())
    }

    @Test
    fun `partial ignored when not listening`() {
        val controller = DictationController(FakeRecognizer())
        controller.onPartial("迟到")

        assertEquals("", controller.state.value.partialText)
    }
}
