package com.example.bletest

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

object BleEngine {
    private var scope: CoroutineScope? = null
    private var backingController: BleController? = null

    fun controller(context: Context): BleController {
        val existing = backingController
        if (existing != null) return existing
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = BleController(context.applicationContext, newScope)
        scope = newScope
        backingController = controller
        return controller
    }

    fun controller(): BleController {
        return backingController ?: throw IllegalStateException("BleEngine not initialized")
    }

    fun shutdown() {
        backingController?.clear()
        scope?.cancel()
        scope = null
        backingController = null
    }
}
