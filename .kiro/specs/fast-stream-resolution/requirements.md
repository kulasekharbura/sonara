# Requirements Document

## Introduction

The Sonara Android music app currently resolves audio stream URLs using a sequential pipeline: InnerTube → Piped instances (one-by-one) → NewPipe fallback. Each step has generous timeouts (15s for InnerTube, 10s per Piped instance), meaning worst-case resolution can take over 90 seconds before playback starts. This feature optimizes the stream resolution pipeline by racing Piped instances in parallel, reducing timeouts, caching the last-working instance, and warming the connection pool — dramatically reducing time-to-first-byte for audio playback.

## Glossary

- **Stream_Resolver**: The `fetchAudioStreamLink()` function in MusicRepository that orchestrates the multi-phase audio URL resolution pipeline
- **Piped_Instance**: A public Piped API server that returns pre-deciphered YouTube audio stream URLs via a REST endpoint
- **Instance_Cache**: An in-memory store that remembers which Piped instance most recently returned a successful response
- **Connection_Pool**: The OkHttp connection pool that maintains keep-alive TCP/TLS connections to previously-contacted hosts
- **Parallel_Race**: A coroutine pattern where multiple async requests are launched simultaneously and the first successful result is used, cancelling the rest
- **InnerTube_Client**: The `InnerTubeClient` object that calls YouTube's internal `/youtubei/v1/player` endpoint directly
- **NewPipe_Fallback**: The NewPipeExtractor library used as a last-resort stream resolution method
- **Audio_Stream_Selector**: The `AudioStreamSelector` object that picks the best audio stream from a list of candidates based on codec and bitrate

## Requirements

### Requirement 1: Reduce InnerTube Timeout

**User Story:** As a user, I want the app to fail fast when InnerTube is unresponsive, so that the app moves to Piped fallback sooner instead of waiting 15 seconds.

#### Acceptance Criteria

1. THE InnerTube_Client SHALL configure its OkHttpClient with a connect timeout of 5 seconds (reduced from 15 seconds)
2. THE InnerTube_Client SHALL configure its OkHttpClient with a read timeout of 7 seconds (reduced from 15 seconds)
3. WHEN an InnerTube request exceeds the connect or read timeout, THE Stream_Resolver SHALL proceed to the Piped resolution phase within 100ms of the timeout expiring
4. WHEN InnerTube tries WEB_REMIX and it fails, THE InnerTube_Client SHALL proceed to WEB client using the same reduced timeouts without resetting the overall phase budget

### Requirement 2: Race Piped Instances in Parallel

**User Story:** As a user, I want multiple Piped instances to be queried simultaneously, so that I get a playable stream URL from whichever instance responds fastest.

#### Acceptance Criteria

1. WHEN the InnerTube phase returns a null or empty stream URL or throws an exception, THE Stream_Resolver SHALL launch requests to all configured Piped instances concurrently using Kotlin coroutines
2. WHEN at least one Piped instance returns an HTTP 200 response whose audioStreams list contains at least one stream with a non-empty URL, bitrate greater than 0, and mimeType starting with "audio/", THE Stream_Resolver SHALL use the first successful result (as selected by AudioStreamSelector) and cancel all remaining in-flight coroutines
3. IF a Piped instance returns an HTTP error status, a network exception, a response with an empty audioStreams list, or a response where AudioStreamSelector returns null, THEN THE Stream_Resolver SHALL treat that instance as failed
4. WHEN all concurrent Piped requests have failed or the overall phase timeout has elapsed, THE Stream_Resolver SHALL proceed to the NewPipe_Fallback phase
5. THE Stream_Resolver SHALL apply a per-instance timeout of 5 seconds for connection establishment and 7 seconds for response read during the parallel race
6. THE Stream_Resolver SHALL apply an overall timeout of 8 seconds to the entire parallel Piped race phase, after which all remaining in-flight requests are cancelled and the system proceeds to the next phase

### Requirement 3: Cache Last-Working Piped Instance

**User Story:** As a user, I want the app to remember which Piped instance worked last time, so that subsequent stream resolutions are even faster by trying the known-good instance first.

#### Acceptance Criteria

1. WHEN a Piped instance returns an HTTP 200 response and the Audio_Stream_Selector returns a non-null stream from that response, THE Instance_Cache SHALL store that instance's base URL as the last-working instance, replacing any previously stored value
2. WHEN the Piped resolution phase begins and the Instance_Cache contains a stored instance, THE Stream_Resolver SHALL try the cached instance first with a timeout of 3 seconds before trying the remaining Piped instances
3. WHEN the cached instance returns a valid audio stream within the 3-second timeout, THE Stream_Resolver SHALL use that result without trying additional Piped instances
4. IF the cached instance fails to return a valid audio stream within the 3-second timeout due to network error, HTTP error, empty audio streams, or timeout expiry, THEN THE Stream_Resolver SHALL immediately proceed to try all Piped instances in parallel (including the cached one)
5. THE Instance_Cache SHALL store values in memory only with no persistence across app process restarts
6. WHEN a cached instance fails 3 consecutive times across successive resolution attempts, THE Instance_Cache SHALL clear the stored instance so that subsequent resolutions skip the cache-first phase
7. WHEN a cached instance succeeds after 1 or 2 consecutive failures, THE Instance_Cache SHALL reset the consecutive failure counter to zero

### Requirement 4: Preconnect and Connection Pooling

**User Story:** As a user, I want the app to keep network connections warm to known Piped instances, so that stream resolution does not spend time on TCP/TLS handshakes.

#### Acceptance Criteria

1. THE Connection_Pool SHALL maintain a pool of up to 8 idle connections with a keep-alive duration of 30 seconds, evicting idle connections that exceed the 30-second threshold
2. WHEN the app starts or the Piped instance list is refreshed, THE Stream_Resolver SHALL preconnect to the first 3 Piped instances in the configured instance list by issuing HEAD requests on a background thread, each with a connect timeout of 5 seconds, without blocking app startup or UI rendering
3. THE Stream_Resolver SHALL share a single OkHttpClient instance (and its connection pool) across all Piped instance requests
4. IF a preconnect HEAD request fails due to timeout, DNS resolution failure, or HTTP error response, THEN THE Stream_Resolver SHALL log a warning indicating which instance failed and continue preconnecting the remaining instances without propagating the error to the caller

### Requirement 5: Preserve Existing Fallback Behavior

**User Story:** As a user, I want the overall resolution pipeline to still fall back to NewPipe if all optimized paths fail, so that playback works even when Piped instances are all down.

#### Acceptance Criteria

1. THE Stream_Resolver SHALL execute resolution phases in fixed order: InnerTube first, then Piped (parallel race with cache-first optimization), then NewPipe_Fallback, advancing to the next phase only when the current phase yields no playable URL
2. WHEN all three phases fail to produce a non-empty stream URL, THE Stream_Resolver SHALL return an empty string without throwing an exception to the caller
3. WHEN a phase fails or succeeds, THE Stream_Resolver SHALL log a message containing the phase name, the instance or method attempted, and the failure reason (exception message or "no valid audio streams") before proceeding to the next step
4. THE Audio_Stream_Selector SHALL select streams using codec preference scoring (opus/webm = highest, mp4/aac = middle, other = lowest) with bitrate descending as tiebreaker, and this selection logic SHALL not be modified by changes to the resolution pipeline

### Requirement 6: Overall Performance Target

**User Story:** As a user, I want stream resolution to complete significantly faster than the current sequential approach, so that music starts playing sooner.

#### Acceptance Criteria

1. WHEN InnerTube succeeds on the first client attempt, THE Stream_Resolver SHALL return a stream URL within 7 seconds measured from the start of the fetchAudioStreamLink() call
2. WHEN InnerTube fails and at least one Piped instance returns a valid response, THE Stream_Resolver SHALL return a stream URL within 15 seconds total measured from the start of fetchAudioStreamLink()
3. WHEN InnerTube fails and the cached Piped instance responds successfully, THE Stream_Resolver SHALL return a stream URL within 10 seconds total measured from the start of fetchAudioStreamLink()
4. IF no resolution method succeeds, THEN THE Stream_Resolver SHALL return an empty string within 25 seconds total measured from the start of fetchAudioStreamLink()
