package com.snapreel.app.player

import androidx.media3.common.PlaybackException

/** Why a page's video can't play. */
enum class PlaybackFailureKind {
    /** The device's decoders or audio output are exhausted or were reclaimed (may clear up). */
    DECODER_BUSY,
    /** The file was moved or deleted after the scan. */
    FILE_MISSING,
    /** Read access to the file (or its folder) was lost. */
    ACCESS_LOST,
    /** The container or codec can't be played on this device. */
    UNSUPPORTED,
    /** Anything else. */
    UNKNOWN,
}

/** A classified playback failure: what went wrong, whether one automatic retry may help, and the text shown to the user. */
data class PlaybackFailure(
    val kind: PlaybackFailureKind,
    val transient: Boolean,
    val message: String,
)

/** Pure mapping from Media3 error codes to [PlaybackFailure] (design › Playback). */
object PlaybackErrorPolicy {

    const val MESSAGE_DECODER_BUSY = "The video decoder is busy. Try again."
    const val MESSAGE_FILE_MISSING = "This video was moved or deleted"
    const val MESSAGE_ACCESS_LOST = "SnapReel no longer has access to this video"
    const val MESSAGE_UNSUPPORTED = "This video format isn't supported on this device"
    const val MESSAGE_UNKNOWN = "This video couldn't be played"

    private val DECODER_BUSY_CODES = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
    )

    private val UNSUPPORTED_CODES = setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    )

    fun classify(errorCode: Int): PlaybackFailure = when (errorCode) {
        in DECODER_BUSY_CODES -> PlaybackFailure(PlaybackFailureKind.DECODER_BUSY, transient = true, MESSAGE_DECODER_BUSY)
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            PlaybackFailure(PlaybackFailureKind.FILE_MISSING, transient = false, MESSAGE_FILE_MISSING)
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            PlaybackFailure(PlaybackFailureKind.ACCESS_LOST, transient = false, MESSAGE_ACCESS_LOST)
        in UNSUPPORTED_CODES -> PlaybackFailure(PlaybackFailureKind.UNSUPPORTED, transient = false, MESSAGE_UNSUPPORTED)
        else -> PlaybackFailure(PlaybackFailureKind.UNKNOWN, transient = true, MESSAGE_UNKNOWN)
    }
}
