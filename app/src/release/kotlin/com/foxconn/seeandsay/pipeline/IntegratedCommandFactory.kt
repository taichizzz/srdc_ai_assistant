package com.foxconn.seeandsay.pipeline

/**
 * Release guard for the absent integrated DEBUG coordinator.
 *
 * @return never returns.
 * @throws IllegalStateException if release code accidentally requests DEBUG composition.
 *
 * The release composition never invokes this function, so it initializes no bridge, LM provider,
 * token source, event waiter, or network resource.
 */
fun createIntegratedCommandCoordinator(): IntegratedCommandCoordinator =
    error("The integrated command coordinator is unavailable in release builds")
