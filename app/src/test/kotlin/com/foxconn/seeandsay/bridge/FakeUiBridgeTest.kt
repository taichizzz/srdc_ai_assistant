package com.foxconn.seeandsay.bridge

import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies scripted screen progression and ordered action recording in [FakeUiBridge].
 *
 * Tests use a virtual coroutine test scope and pure immutable data. They perform no Android,
 * network, filesystem, device, or real-time work and fail only through assertions.
 */
class FakeUiBridgeTest {

    /**
     * Verifies reads advance through the script and every bridge call remains ordered.
     *
     * @return no value; assertion failure reports incorrect scripting or call recording.
     *
     * The test uses a virtual coroutine scheduler, performs no I/O or dispatcher switching, and
     * completes without real suspension or cancellation resources.
     */
    @Test
    fun scriptedScreensAndActionsAreRecordedInOrder() =
        runTest {
            val first = FakeUiBridge.realisticHomeScreen()
            val second = ScreenSnapshot("settings", 2L, emptyList())
            val bridge = FakeUiBridge(listOf(first, second))

            assertEquals(first, bridge.readScreen())
            assertEquals(true, bridge.click(0))
            assertEquals(true, bridge.setText(4, "Roxanne"))
            assertEquals(true, bridge.back())
            assertEquals(second, bridge.readScreen())
            assertEquals(second, bridge.readScreen())
            assertEquals(
                listOf(
                    FakeUiBridge.Call.ReadScreen,
                    FakeUiBridge.Call.Click(0),
                    FakeUiBridge.Call.SetText(4, "Roxanne"),
                    FakeUiBridge.Call.Back,
                    FakeUiBridge.Call.ReadScreen,
                    FakeUiBridge.Call.ReadScreen,
                ),
                bridge.calls(),
            )
        }
}
