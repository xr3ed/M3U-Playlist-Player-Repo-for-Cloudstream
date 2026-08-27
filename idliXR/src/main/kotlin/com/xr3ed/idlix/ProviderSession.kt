package com.xr3ed.idlix

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(DelicateCoroutinesApi::class)
object IdlixProviderSession {
    val clientId: String = UUID.randomUUID().toString()
    private val started = AtomicBoolean(false)

    fun start(extensionName: String) {
        if (!started.compareAndSet(false, true)) return
        GlobalScope.launch {
            var heartbeat = 0L
            while (true) {
                heartbeat++
                // Keep a lightweight provider session heartbeat without sending telemetry.
                delay(10L * 60L * 1000L)
            }
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
fun pingAnalytics(extensionName: String) {
    IdlixProviderSession.start(extensionName)
}
