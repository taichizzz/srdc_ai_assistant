package com.foxconn.seeandsay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Replaces Android's main dispatcher so lifecycle ViewModel coroutines run in local unit tests.
 *
 * @param dispatcher test dispatcher installed as `Dispatchers.Main` for each test.
 *
 * JUnit invokes setup/teardown on its test thread. The rule performs no I/O, and reset always removes
 * the replacement after success or assertion failure. Individual test coroutine cancellation is
 * controlled by the supplied [TestDispatcher].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    /**
     * Installs [dispatcher] before a test constructs lifecycle-aware ViewModels.
     *
     * @param description JUnit metadata for the test about to start.
     * @return This callback has no return value.
     *
     * The callback runs synchronously on JUnit's thread, performs no I/O, and has no cancellation
     * behavior. Dispatcher installation failure propagates and fails the test.
     */
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    /**
     * Restores the original main-dispatcher state after a test finishes.
     *
     * @param description JUnit metadata for the completed test.
     * @return This callback has no return value.
     *
     * The callback runs synchronously even after assertion failure, performs no I/O, and has no
     * cancellation behavior.
     */
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
