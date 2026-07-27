package com.ayagmar.pimobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.ayagmar.pimobile.di.AppGraph
import com.ayagmar.pimobile.perf.PerformanceMetrics
import com.ayagmar.pimobile.perf.PerformanceMetrics.recordAppStart
import com.ayagmar.pimobile.ui.PiMobileApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val appGraph: AppGraph
        get() = (application as PiMobileApplication).appGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        // Record app start as early as possible
        recordAppStart()

        super.onCreate(savedInstanceState)
        appGraph.shareNavigationCoordinator.submitExternalIntent(intent.action, intent.dataString)
        enableEdgeToEdge()
        setContent {
            PiMobileApp(appGraph = appGraph)
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
