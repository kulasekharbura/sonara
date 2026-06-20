# Implementation Plan: Fix Audio Stream Playback

## Overview

Replace the broken stream URL extraction pipeline (NewPipeExtractor primary + dead Cobalt/Render fallbacks) with a Piped API-first approach. Create the Piped service interface and models, implement instance-rotation failover, add an audio stream selector, rewire MusicRepository, and clean up dead code.

## Tasks

- [x] 1. Create Piped API data models and service interface
  - [x] 1.1 Create `PipedModels.kt` in `network/` package
    - Add `PipedStreamResponse` data class with `title: String?`, `uploader: String?`, `audioStreams: List<PipedAudioStream>`
    - Add `PipedAudioStream` data class with `url: String`, `bitrate: Int`, `mimeType: String`, `codec: String?`, `format: String?`, `quality: String?`
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 1.2 Create `PipedApiService.kt` in `network/` package
    - Define Retrofit interface with `@GET("streams/{videoId}")` endpoint
    - Method signature: `suspend fun getStreams(@Path("videoId") videoId: String): PipedStreamResponse`
    - _Requirements: 1.1, 1.2_

  - [x] 1.3 Create `PipedClient.kt` in `network/` package
    - Add `object PipedClient` with `fun createService(baseUrl: String): PipedApiService`
    - Reuse OkHttpClient with logging interceptor, add 10-second connect and read timeouts
    - Use Retrofit.Builder with GsonConverterFactory and the dynamic baseUrl
    - _Requirements: 1.3, 3.4_

- [x] 2. Implement AudioStreamSelector
  - [x] 2.1 Create `AudioStreamSelector.kt` in `data/` package
    - Add `object AudioStreamSelector` with `fun selectBest(streams: List<PipedAudioStream>): PipedAudioStream?`
    - Filter to streams where `url.isNotEmpty()`, `bitrate > 0`, and `mimeType.startsWith("audio/")`
    - Add `private fun preferredCodecScore(stream: PipedAudioStream): Int` returning 2 for opus/webm, 1 for mp4/aac, 0 for other
    - Sort by codec preference descending, then bitrate descending, return first or null
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [x] 2.2 Write property tests for AudioStreamSelector
    - **Property 4: Stream Filtering Validity**
    - **Property 5: Stream Selection Preference Order**
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4**

- [x] 3. Rewrite MusicRepository.fetchAudioStreamLink()
  - [x] 3.1 Add Piped instance list to MusicRepository
    - Add `private val pipedInstances = listOf(...)` with at least 8 Piped API URLs
    - URLs: pipedapi.kavin.rocks, pipedapi.leptons.xyz, pipedapi.nosebs.ru, pipedapi-libre.kavin.rocks, piped-api.privacy.com.de, pipedapi.adminforge.de, api.piped.yt, pipedapi.drgns.space
    - _Requirements: 4.1_

  - [x] 3.2 Rewrite `fetchAudioStreamLink()` with Piped-first logic
    - Loop through `pipedInstances`, create PipedApiService via PipedClient for each
    - Call `service.getStreams(videoId)`, pass `response.audioStreams` to `AudioStreamSelector.selectBest()`
    - On success (non-null selection), return `bestStream.url`
    - On failure (exception or null selection), log warning and continue to next instance
    - _Requirements: 3.1, 3.2, 3.3, 3.5, 4.2, 4.3_

  - [x] 3.3 Add NewPipeExtractor as secondary fallback
    - After all Piped instances exhausted, try existing NewPipeExtractor logic
    - Filter to progressive HTTP audio streams, select highest bitrate
    - On failure, return empty string
    - Wrap entire method in try/catch to guarantee no exceptions escape
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

  - [x] 3.4 Write property test for instance failover exhaustion
    - **Property 3: Instance Failover Exhaustion**
    - **Validates: Requirements 3.3, 3.5, 4.2**

  - [x] 3.5 Write property test for no unhandled exceptions
    - **Property 6: No Unhandled Exceptions**
    - **Validates: Requirements 4.3, 6.4**

- [x] 4. Remove dead fallback code
  - [x] 4.1 Remove Cobalt API code from `fetchAudioStreamLink()`
    - Delete the entire "SECONDARY ENGINE FAILOVER: ENHANCED COBALT CLOAKED PAYLOADS" block
    - Remove the `backupGateways` list, POST request logic, and cloaking headers
    - _Requirements: 7.1_

  - [x] 4.2 Remove Render.com backend fallback from `fetchAudioStreamLink()`
    - Delete the "ULTIMATE RESILIENT SAFEGUARD" block with the Render URL
    - _Requirements: 7.2_

  - [x] 4.3 Clean up unused imports in MusicRepository
    - Remove `org.json.JSONObject`, `java.net.HttpURLConnection`, `java.net.URL` if no longer needed
    - Verify build compiles successfully
    - _Requirements: 7.3_

- [x] 5. Checkpoint - Verify build and integration
  - Ensure all tests pass, ask the user if questions arise.
  - Verify the app compiles without errors
  - Confirm MusicRepository has no references to Cobalt or Render.com
  - Confirm PipedApiService, PipedModels, PipedClient, and AudioStreamSelector are all wired correctly

- [x] 6. Write unit tests for stream resolution
  - [x] 6.1 Write unit tests for PipedClient service creation
    - Test that createService returns a non-null PipedApiService for valid base URLs
    - _Requirements: 1.3_

  - [x] 6.2 Write property test for deserialization robustness
    - **Property 1: Deserialization Robustness**
    - **Validates: Requirement 2.3**

  - [x] 6.3 Write unit test for valid Piped response yielding a playable URL
    - **Property 2: Valid Piped Response Yields Playable URL**
    - **Validates: Requirements 3.2, 5.1**

- [x] 7. Final checkpoint - Verify complete integration
  - Ensure all tests pass, ask the user if questions arise.
  - Verify the full stream resolution pipeline: Piped instances → NewPipeExtractor fallback
  - Confirm no dead code remains

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- The implementation language is Kotlin, matching the existing codebase
- No new dependencies are required — Piped API uses the existing Retrofit/Gson stack
- Task 4 (dead code removal) can be done as part of Task 3 during the rewrite, or separately
- Property tests can use JUnit with manually-generated random data, or Kotest property testing if added later
