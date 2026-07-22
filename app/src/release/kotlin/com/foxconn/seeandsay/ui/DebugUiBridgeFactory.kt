package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.bridge.UiBridge

/**
 * Guards the absent matching inspector dependency in release builds.
 *
 * @return never returns because the DEBUG-only inspector must not request a bridge in release.
 * @throws IllegalStateException whenever mistakenly invoked by release code.
 *
 * This release-only guard performs no Android accessibility work or I/O and has no threading or
 * coroutine behavior. The release composition path never accesses its lazy ViewModel factory.
 */
fun createDebugUiBridge(): UiBridge = error("The matching inspector is unavailable in release builds")
