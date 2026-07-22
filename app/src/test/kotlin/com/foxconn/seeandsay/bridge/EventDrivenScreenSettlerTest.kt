package com.foxconn.seeandsay.bridge

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Virtual-time coverage for event subscription, debounce, timeout, and cancellation cleanup. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDrivenScreenSettlerTest {

    @Test
    fun `event burst returns only after three hundred milliseconds of quiet`() =
        runTest {
            val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val settler = EventDrivenScreenSettler(events)
            val result = async { settler.awaitScreenSettled(timeoutMillis = 2_000L) }
            runCurrent()

            events.emit(Unit)
            advanceTimeBy(200L)
            events.emit(Unit)
            advanceTimeBy(299L)
            assertFalse(result.isCompleted)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(ScreenSettleResult.ChangeObserved, result.await())
        }

    @Test
    fun `no event returns timeout without a real delay`() =
        runTest {
            val events = MutableSharedFlow<Unit>()
            val settler = EventDrivenScreenSettler(events)
            val result = async { settler.awaitScreenSettled(timeoutMillis = 750L) }
            runCurrent()

            advanceTimeBy(750L)
            runCurrent()

            assertEquals(ScreenSettleResult.TimedOut, result.await())
        }

    @Test
    fun `cancellation removes the active event collector`() =
        runTest {
            val events = MutableSharedFlow<Unit>()
            val settler = EventDrivenScreenSettler(events)
            val wait = launch { settler.awaitScreenSettled(timeoutMillis = 5_000L) }
            runCurrent()
            assertEquals(1, events.subscriptionCount.value)

            wait.cancelAndJoin()
            runCurrent()

            assertEquals(0, events.subscriptionCount.value)
        }
}
