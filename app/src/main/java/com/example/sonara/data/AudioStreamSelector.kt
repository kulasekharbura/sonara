package com.example.sonara.data

import com.example.sonara.network.PipedAudioStream

/**
 * Selects the best audio stream from a list of candidates.
 * Prefers opus/webm codecs, then mp4/aac, then other.
 * Among equal codec preference, selects highest bitrate.
 */
object AudioStreamSelector {

    /**
     * Selects the best audio stream from the given list.
     *
     * Filtering criteria:
     * - url must be non-empty
     * - bitrate must be > 0
     * - mimeType must start with "audio/"
     *
     * Sorting order:
     * 1. Codec preference descending (opus/webm=2, mp4/aac=1, other=0)
     * 2. Bitrate descending
     *
     * @param streams list of audio stream candidates
     * @return the best stream, or null if no valid streams exist
     */
    fun selectBest(streams: List<PipedAudioStream>): PipedAudioStream? {
        val validStreams = streams.filter { stream ->
            stream.url.isNotEmpty() &&
                stream.bitrate > 0 &&
                stream.mimeType.startsWith("audio/")
        }

        if (validStreams.isEmpty()) return null

        return validStreams.sortedWith(
            compareByDescending<PipedAudioStream> { preferredCodecScore(it) }
                .thenByDescending { it.bitrate }
        ).first()
    }

    private fun preferredCodecScore(stream: PipedAudioStream): Int {
        return when {
            stream.mimeType.contains("webm") || stream.codec?.contains("opus") == true -> 2
            stream.mimeType.contains("mp4") || stream.codec?.contains("aac") == true -> 1
            else -> 0
        }
    }
}
