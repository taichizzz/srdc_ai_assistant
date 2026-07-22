package com.foxconn.seeandsay.config

import com.foxconn.seeandsay.BuildConfig

/**
 * Centralizes compile-time behavior flags required by the architecture contract.
 *
 * Values are generated from non-secret Gradle configuration and are safe to read from any thread.
 * Reading performs no I/O, cannot fail project-specifically, and has no coroutine or cancellation
 * behavior. Callers must not read BuildConfig flags directly outside this object.
 */
object FeatureFlags {

    /**
     * Enables cloud-first TTS when true; false selects the on-device client without a cloud call.
     *
     * The value defaults to true and may be overridden for DEBUG builds through the gitignored
     * `local.properties`. Reading is synchronous, non-blocking, and never exposes a credential.
     */
    val CLOUD_TTS_ENABLED: Boolean = BuildConfig.CLOUD_TTS_ENABLED

    /**
     * Enables the primary LM intent path when true; false forces deterministic matching fallback.
     *
     * The value defaults to true for LM-first production behavior and may be overridden for DEBUG
     * through gitignored `local.properties`. Empty release LM configuration contains no credential;
     * provider/config failure must enter deterministic fallback. Reading is synchronous and performs
     * no provider initialization, network request, I/O, coroutine launch, or cancellation work.
     */
    val LM_ENABLED: Boolean = BuildConfig.LM_ENABLED
}
