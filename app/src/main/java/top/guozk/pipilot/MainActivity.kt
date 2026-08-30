package top.guozk.pipilot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.guozk.pipilot.di.AppGraph
import top.guozk.pipilot.perf.PerformanceMetrics
import top.guozk.pipilot.perf.PerformanceMetrics.recordAppStart
import top.guozk.pipilot.ui.PipilotApp

class MainActivity : ComponentActivity() {
    private val appGraph: AppGraph
        get() = (application as PipilotApplication).appGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        // Record app start as early as possible
        recordAppStart()

        super.onCreate(savedInstanceState)
        appGraph.shareNavigationCoordinator.submitExternalIntent(intent.action, intent.dataString)
        enableEdgeToEdge()
        setContent {
            PipilotApp(appGraph = appGraph)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appGraph.shareNavigationCoordinator.submitExternalIntent(intent.action, intent.dataString)
    }

    override fun onResume() {
        super.onResume()
        // Log any pending metrics
        lifecycleScope.launch {
            val timings = PerformanceMetrics.flushTimings()
            timings.forEach { timing ->
                android.util.Log.d(
                    "PerfMetrics",
                    "Flushed: ${timing.metric} = ${timing.durationMs}ms",
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            lifecycleScope.launch {
                appGraph.sessionController.disconnect()
            }
        }
        super.onDestroy()
    }
}
