package com.ayagmar.pimobile

import android.app.Application
import com.ayagmar.pimobile.di.AppGraph

class PiMobileApplication : Application() {
    val appGraph: AppGraph by lazy { AppGraph(this) }
}
