package com.snapreel.app.testsupport

import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Waits (real time) for [condition], idling the Robolectric main looper between checks so that
 * `viewModelScope` continuations resumed from IO threads get to run.
 */
fun awaitMain(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        ShadowLooper.idleMainLooper()
        if (condition()) return true
        ShadowLooper.idleMainLooper(5, TimeUnit.MILLISECONDS)
        Thread.sleep(2)
    }
    ShadowLooper.idleMainLooper()
    return condition()
}
