package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.bridge.FakeUiBridge
import com.foxconn.seeandsay.bridge.UiBridge

/**
 * Creates the provider-neutral bridge shown by the current DEBUG matching inspector.
 *
 * @return scripted [FakeUiBridge] containing the realistic home-screen fixture.
 *
 * This DEBUG-only factory performs pure in-memory allocation, no Android accessibility work or
 * I/O, and has no expected failure. Person 1's real implementation can replace this composition
 * root dependency later without changing the ViewModel or Compose section.
 */
fun createDebugUiBridge(): UiBridge = FakeUiBridge()
