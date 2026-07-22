package com.foxconn.seeandsay.bridge

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The `AccessibilityService` that gives the assistant its "eye & hand" on the device.
 *
 * Android runs exactly one instance of this service while the user has it enabled in
 * Settings → Accessibility. The instance is published to the rest of the `bridge` package through
 * [instance] so [AccessibilityBridge] can reach `getRootInActiveWindow()` without a Context handle.
 *
 * Reading remains pull-based. For M2.3, relevant window content/state events are reduced to an
 * in-process coroutine signal consumed by [awaitScreenSettled]; no Android callback or node escapes
 * the `bridge` package. This class stays inside `bridge` and never imports speech or decision code.
 */
class SeeAndSayService : AccessibilityService() {

    /** Hot, non-blocking event signal; overflow keeps the newest activity in a burst. */
    private val screenChangeEvents =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Debounces the service-owned signal without exposing Android callbacks outside bridge. */
    private val screenSettler = EventDrivenScreenSettler(screenChangeEvents)

    /**
     * Records that the service is connected and publishes this instance for the Bridge.
     *
     * Called by Android on the main thread once the user enables the service and it binds.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    /**
     * Signals content/state changes without blocking Android's accessibility callback thread.
     *
     * @param event the platform-reported accessibility event, possibly null.
     *
     * Only `TYPE_WINDOW_CONTENT_CHANGED` and `TYPE_WINDOW_STATE_CHANGED` are accepted. `tryEmit`
     * performs no suspension or I/O; a burst is conflated by the bounded shared-flow buffer and
     * later debounced by [awaitScreenSettled]. Null and unrelated events are ignored.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            -> screenChangeEvents.tryEmit(Unit)
        }
    }

    /**
     * Waits for a relevant event burst to become quiet.
     *
     * @param timeoutMillis positive complete wait bound supplied by pipeline composition.
     * @return settled-change or timeout observation; timeout never throws.
     *
     * This is bridge-internal coroutine work. Cancellation propagates and removes the shared-flow
     * collector; no listener, callback, node, or service reference escapes this package.
     */
    internal suspend fun awaitScreenSettled(timeoutMillis: Long): ScreenSettleResult =
        screenSettler.awaitScreenSettled(timeoutMillis)

    /**
     * Required override; the assistant does not respond to interruption requests.
     */
    override fun onInterrupt() {
        // No feedback stream to interrupt in this milestone.
    }

    /**
     * Clears the published instance when Android unbinds the service (user disabled it or shutdown).
     *
     * @param intent the unbind intent supplied by the platform.
     * @return always the platform default; rebinding is handled by Android re-creating the service.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        Log.d(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "SeeAndSayService"

        /**
         * The connected service instance, or `null` when the user has not enabled it.
         *
         * Written on the main thread in [onServiceConnected] / [onUnbind] and read by
         * [AccessibilityBridge]; marked `@Volatile` for safe cross-thread visibility. A non-null
         * value is the app's signal that the Bridge is usable.
         */
        @Volatile
        var instance: SeeAndSayService? = null
            private set
    }
}
