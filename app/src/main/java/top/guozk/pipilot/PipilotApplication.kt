package top.guozk.pipilot

import android.app.Application
import top.guozk.pipilot.di.AppGraph

class PipilotApplication : Application() {
    val appGraph: AppGraph by lazy { AppGraph(this) }
}
