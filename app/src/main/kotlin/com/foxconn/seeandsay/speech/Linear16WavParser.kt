package com.foxconn.seeandsay.speech

/**
 * Carries validated headerless PCM data required by Android AudioTrack playback.
 *
 * @property pcmBytes copied signed little-endian PCM bytes with no RIFF/WAV header.
 * @property sampleRateHz sample rate declared by the WAV `fmt ` chunk.
 * @property channelCount channel count declared by the WAV `fmt ` chunk.
 * @property bitsPerSample sample resolution declared by the WAV `fmt ` chunk.
 *
 * This passive provider-neutral value performs no I/O or coroutine work. Its byte array is
 * immutable by convention and must not be modified after construction. Parsing owns failures;
 * this value has no independent failure, threading, or cancellation behavior.
 */
internal data class PcmPlaybackAudio(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
)

/**
 * Parses Google's unary LINEAR16 WAV container into validated headerless PCM16 samples.
 *
 * The parser understands RIFF/WAVE chunk framing rather than assuming a fixed 44-byte header, so
 * optional metadata chunks and word padding cannot be played as samples. All work is pure and
 * synchronous on the caller's thread. Malformed, truncated, unsupported, or metadata-mismatched
 * input becomes a fixed recoverable [CloudTtsException]; no audio content is logged.
 */
internal object Linear16WavParser {

    /**
     * Extracts the WAV `data` chunk and verifies it against synthesized-audio metadata.
     *
     * @param audio Google synthesis bytes plus expected sample metadata.
     * @return headerless, frame-aligned PCM16 data suitable for AudioTrack.
     * @throws CloudTtsException when the container is short/malformed, lacks required chunks, uses
     * compressed/non-PCM data, has unsupported channels/bit depth, or disagrees with metadata.
     *
     * Parsing performs bounded in-memory reads/copies and no I/O or suspension. It is safe on any
     * dispatcher and has no cancellation point; input is limited by Google's unary text boundary.
     */
    fun parse(audio: SynthesizedAudio): PcmPlaybackAudio {
        if (audio.format != SynthesizedAudioFormat.Linear16Wav) throw malformedAudioFailure()
        val bytes = audio.bytes
        if (bytes.size < RIFF_HEADER_SIZE) throw malformedAudioFailure()
        if (!bytes.matchesAscii(offset = 0, expected = RIFF_ID)) throw malformedAudioFailure()
        if (!bytes.matchesAscii(offset = 8, expected = WAVE_ID)) throw malformedAudioFailure()

        val riffPayloadSize = bytes.readUnsignedIntLittleEndian(offset = 4)
        // RIFF's size excludes the initial 8 bytes; rejecting overrun prevents trusting a truncated
        // provider response while allowing harmless trailing transport bytes.
        if (riffPayloadSize < WAVE_ID.size || riffPayloadSize + 8L > bytes.size.toLong()) {
            throw malformedAudioFailure()
        }
        val riffEndExclusive = (riffPayloadSize + 8L).toInt()

        var format: ParsedWaveFormat? = null
        var pcm: ByteArray? = null
        var offset = RIFF_HEADER_SIZE
        while (offset < riffEndExclusive) {
            if (riffEndExclusive - offset < CHUNK_HEADER_SIZE) throw malformedAudioFailure()
            val chunkSizeLong = bytes.readUnsignedIntLittleEndian(offset + 4)
            if (chunkSizeLong > Int.MAX_VALUE) throw malformedAudioFailure()
            val chunkSize = chunkSizeLong.toInt()
            val payloadOffset = offset + CHUNK_HEADER_SIZE
            val payloadEndLong = payloadOffset.toLong() + chunkSizeLong
            if (payloadEndLong > riffEndExclusive.toLong()) throw malformedAudioFailure()
            val payloadEnd = payloadEndLong.toInt()

            when {
                bytes.matchesAscii(offset, FORMAT_ID) ->
                    format = parseFormatChunk(bytes, payloadOffset, chunkSize)

                bytes.matchesAscii(offset, DATA_ID) -> {
                    if (chunkSize == 0) throw malformedAudioFailure()
                    pcm = bytes.copyOfRange(payloadOffset, payloadEnd)
                }
            }

            // RIFF chunks are word-aligned; odd payload sizes include one pad byte not counted in
            // the chunk size. Advancing over it prevents mistaking padding for the next chunk ID.
            val paddedEndLong = payloadEndLong + (chunkSize and 1)
            if (paddedEndLong > riffEndExclusive.toLong()) throw malformedAudioFailure()
            offset = paddedEndLong.toInt()
        }

        val parsedFormat = format ?: throw malformedAudioFailure()
        val parsedPcm = pcm ?: throw malformedAudioFailure()
        if (
            parsedFormat.sampleRateHz != audio.sampleRateHz ||
                parsedFormat.channelCount != audio.channelCount ||
                parsedFormat.bitsPerSample != audio.bitsPerSample
        ) {
            throw malformedAudioFailure()
        }
        val bytesPerFrame = parsedFormat.channelCount * (parsedFormat.bitsPerSample / BITS_PER_BYTE)
        if (bytesPerFrame <= 0 || parsedPcm.size % bytesPerFrame != 0) {
            throw malformedAudioFailure()
        }
        return PcmPlaybackAudio(
            pcmBytes = parsedPcm,
            sampleRateHz = parsedFormat.sampleRateHz,
            channelCount = parsedFormat.channelCount,
            bitsPerSample = parsedFormat.bitsPerSample,
        )
    }

    /**
     * Parses and validates the fixed core of a RIFF `fmt ` chunk.
     *
     * @param bytes complete WAV response.
     * @param offset first byte of the `fmt ` payload.
     * @param size declared payload size.
     * @return validated mono PCM16 format metadata.
     * @throws CloudTtsException when the chunk is short, compressed, multi-channel, malformed, or
     * internally inconsistent.
     *
     * This pure helper performs fixed-size little-endian reads on the caller's thread. It performs
     * no I/O/suspension and owns no cancellation resource.
     */
    private fun parseFormatChunk(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): ParsedWaveFormat {
        if (size < PCM_FORMAT_CORE_SIZE) throw malformedAudioFailure()
        val audioFormat = bytes.readUnsignedShortLittleEndian(offset)
        val channelCount = bytes.readUnsignedShortLittleEndian(offset + 2)
        val sampleRateLong = bytes.readUnsignedIntLittleEndian(offset + 4)
        val byteRateLong = bytes.readUnsignedIntLittleEndian(offset + 8)
        val blockAlign = bytes.readUnsignedShortLittleEndian(offset + 12)
        val bitsPerSample = bytes.readUnsignedShortLittleEndian(offset + 14)
        if (
            audioFormat != PCM_AUDIO_FORMAT ||
                channelCount != SUPPORTED_CHANNEL_COUNT ||
                sampleRateLong == 0L ||
                sampleRateLong > Int.MAX_VALUE ||
                bitsPerSample != SUPPORTED_BITS_PER_SAMPLE
        ) {
            throw malformedAudioFailure()
        }
        val bytesPerFrame = channelCount * (bitsPerSample / BITS_PER_BYTE)
        val expectedByteRate = sampleRateLong * bytesPerFrame
        if (blockAlign != bytesPerFrame || byteRateLong != expectedByteRate) {
            throw malformedAudioFailure()
        }
        return ParsedWaveFormat(
            sampleRateHz = sampleRateLong.toInt(),
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
        )
    }

    /**
     * Describes validated values read from one PCM WAV `fmt ` chunk.
     *
     * @property sampleRateHz positive sample rate.
     * @property channelCount supported mono channel count.
     * @property bitsPerSample supported PCM16 bit depth.
     *
     * This pure value owns no I/O, coroutine, cancellation, or failure behavior.
     */
    private data class ParsedWaveFormat(
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
    )

    /**
     * Tests whether an array contains an ASCII identifier at the requested offset.
     *
     * @receiver source WAV byte array.
     * @param offset first byte to compare.
     * @param expected exact ASCII identifier bytes.
     * @return true only when all expected bytes exist and match.
     *
     * This bounded pure comparison performs no I/O/suspension, never throws for an invalid offset,
     * and has no cancellation behavior.
     */
    private fun ByteArray.matchesAscii(
        offset: Int,
        expected: ByteArray,
    ): Boolean {
        if (offset < 0 || offset > size - expected.size) return false
        return expected.indices.all { index -> this[offset + index] == expected[index] }
    }

    /**
     * Reads an unsigned little-endian 16-bit integer from validated WAV bytes.
     *
     * @receiver source WAV byte array.
     * @param offset first of two bytes.
     * @return value in the range 0..65535.
     * @throws CloudTtsException when two bytes are not available.
     *
     * This pure helper performs no I/O/suspension and owns no cancellation resource.
     */
    private fun ByteArray.readUnsignedShortLittleEndian(offset: Int): Int {
        if (offset < 0 || offset > size - 2) throw malformedAudioFailure()
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8)
    }

    /**
     * Reads an unsigned little-endian 32-bit integer from validated WAV bytes.
     *
     * @receiver source WAV byte array.
     * @param offset first of four bytes.
     * @return value represented as a non-negative Long.
     * @throws CloudTtsException when four bytes are not available.
     *
     * This pure helper performs no I/O/suspension and owns no cancellation resource.
     */
    private fun ByteArray.readUnsignedIntLittleEndian(offset: Int): Long {
        if (offset < 0 || offset > size - 4) throw malformedAudioFailure()
        return (this[offset].toLong() and 0xffL) or
            ((this[offset + 1].toLong() and 0xffL) shl 8) or
            ((this[offset + 2].toLong() and 0xffL) shl 16) or
            ((this[offset + 3].toLong() and 0xffL) shl 24)
    }

    /**
     * Creates the fixed recoverable exception for invalid provider WAV data.
     *
     * @return malformed-audio failure containing no audio bytes or provider detail.
     *
     * This synchronous helper performs no I/O/coroutine work and cannot be cancelled.
     */
    private fun malformedAudioFailure(): CloudTtsException =
        CloudTtsException(
            CloudTtsFailureReason.MalformedAudio,
            "Cloud text-to-speech returned malformed or unsupported audio.",
        )

    /** Initial RIFF size plus WAVE identifier bytes. */
    private const val RIFF_HEADER_SIZE = 12

    /** Four-byte chunk ID plus four-byte unsigned chunk size. */
    private const val CHUNK_HEADER_SIZE = 8

    /** Required PCM core size of a `fmt ` payload. */
    private const val PCM_FORMAT_CORE_SIZE = 16

    /** RIFF audio-format code for uncompressed integer PCM. */
    private const val PCM_AUDIO_FORMAT = 1

    /** Phase 4 supports the selected Google mono voice only. */
    private const val SUPPORTED_CHANNEL_COUNT = 1

    /** Phase 4 AudioTrack path supports signed PCM16 only. */
    private const val SUPPORTED_BITS_PER_SAMPLE = 16

    /** Conversion unit used for frame-size validation. */
    private const val BITS_PER_BYTE = 8

    /** ASCII RIFF container identifier. */
    private val RIFF_ID = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())

    /** ASCII WAVE form identifier. */
    private val WAVE_ID = byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte())

    /** ASCII PCM format-chunk identifier including its trailing space. */
    private val FORMAT_ID = byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte())

    /** ASCII audio-data chunk identifier. */
    private val DATA_ID = byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte())
}
