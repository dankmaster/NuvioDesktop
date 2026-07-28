package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitlePlaybackFingerprintTest {
    @Test
    fun serializesTheSameStremioFingerprintContractAsWebAndTv() {
        val extra = SubtitlePlaybackFingerprint(
            videoHash = "A1B2C3D4E5F60718",
            videoSize = 1_234_567_890L,
            filename = "Show Name/S01E02 & finale.mkv",
        ).toSubtitleExtraPathSegment()

        assertEquals(
            "videoHash=a1b2c3d4e5f60718&videoSize=1234567890&filename=S01E02%20%26%20finale.mkv",
            extra,
        )
    }

    @Test
    fun dropsInvalidOrEmptyFingerprintParts() {
        assertNull(
            SubtitlePlaybackFingerprint(
                videoHash = "torrent-info-hash",
                videoSize = -1,
                filename = " ",
            ).toSubtitleExtraPathSegment(),
        )
    }

    @Test
    fun keepsTheFingerprintContractAcrossFiftyMoviesAndOneHundredEpisodes() {
        val movieFiles = (1..50).map { index ->
            "Movie.${index.toString().padStart(2, '0')}.2026.1080p.WEB-DL.mkv"
        }
        val episodeFiles = (1..100).map { index ->
            val season = ((index - 1) / 20) + 1
            val episode = ((index - 1) % 20) + 1
            "Series.S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}.1080p.WEB-DL.mkv"
        }

        (movieFiles + episodeFiles).forEachIndexed { index, filename ->
            val hash = (index + 1).toString(16).padStart(16, '0')
            val size = 734_003_200L + index
            val extra = SubtitlePlaybackFingerprint(
                videoHash = hash,
                videoSize = size,
                filename = filename,
            ).toSubtitleExtraPathSegment()

            assertEquals(
                "videoHash=$hash&videoSize=$size&filename=$filename",
                extra,
            )
        }
    }
}
