# Implementation Plan: Fast Stream Resolution

## Overview

Optimize the Sonara audio stream resolution pipeline by reducing InnerTube timeouts, racing Piped instances in parallel, caching the last-working instance, and warming connections on startup. The implementation modifies 3 existing files and creates 1 new file, plus adds test infrastructure.

## Tasks

- [x] 1. Reduce InnerTube timeouts
  - [x] 1.1 Modify InnerTubeClient OkHttpClient timeout configuration
    - Open `app/src/main/java/com/example/sonara/network/InnerTubeClient.kt`
    - Change `connectTimeout(15, TimeUnit.SECONDS)` to `connectTimeout(5, TimeUnit.SECONDS)`
    - Change `readTimeout(15, TimeUnit.SECONDS)` to `readTimeout(7, TimeUnit.SECONDS)`
    - Verify no other timeout configurations exist in the file
    - _Requirements: 1.1, 1.2, 1.4_

- [x] 2. Add connection pool and preconnect to PipedClient
  - [x] 2.1 Modify PipedClient to use shared OkHttpClient with ConnectionPool
    - Open `app/src/main/java/com/example/sonara/network/PipedClient.kt`
    - Add `import okhttp3.ConnectionPool` import
    - Create a `ConnectionPool(maxIdleConnections = 8, keepAliveDuration = 30, TimeUnit.SECONDS)` instance
    - Attach the connection pool to the existing `OkHttpClient.Builder()`
    - Reduce connect timeout from 10s to 5s and read timeout from 10s to 7s
    - _Requirements: 4.1, 4.3, 2.5_

  - [x] 2.2 Add preconnect() function to PipedClient
    - Add a `suspend fun preconnect(instances: List<String>)` function to PipedClient
    - For each instance in the list, issue an OkHttp HEAD request using the shared `okHttpClient`
    - Wrap each HEAD request in a try-catch; on failure log a warning with the instance URL and exception message using `android.util.Log.w`
    - Do not propagate exceptions to the caller
    - _Requirements: 4.2, 4.4_

- [x] 3. Create PipedInstanceCache
  - [x] 3.1 Create the PipedInstanceCache singleton
    - Create a new file `app/src/main/java/com/example/sonara/network/PipedInstanceCache.kt`
    - Define `object PipedInstanceCache` with `private val lock = Any()`
    - Add `private var cachedInstance: String? = null` and `private var consecutiveFailures: Int = 0`
    - Implement `fun getCachedInstance(): String?` that returns `cachedInstance` inside `synchronized(lock)`
    - Implement `fun recordSuccess(instance: String)` that sets `cachedInstance = instance` and resets `consecutiveFailures = 0` inside `synchronized(lock)`
    - Implement `fun recordFailure(instance: String)` that increments `consecutiveFailures` if `cachedInstance == instance`; if counter reaches 3, clears `cachedInstance` and resets counter — all inside `synchronized(lock)`
    - Add a `fun clear()` method (visible for testing) that resets both fields
    - _Requirements: 3.1, 3.5, 3.6, 3.7_

- [x] 4. Rewrite fetchAudioStreamLink with cache-first and parallel race
  - [x] 4.1 Refactor fetchAudioStreamLink into phase helper functions
    - Open `app/src/main/java/com/example/sonara/data/MusicRepository.kt`
    - Add imports for `kotlinx.coroutines.async`, `kotlinx.coroutines.coroutineScope`, `kotlinx.coroutines.selects.select`, `kotlinx.coroutines.withTimeoutOrNull`, and `com.example.sonara.network.PipedInstanceCache`
    - Extract the existing InnerTube logic into a private `suspend fun tryInnerTube(videoId: String): String?` function
    - Extract the existing NewPipe logic into a private `suspend fun tryNewPipe(videoId: String): String?` function
    - Create a private `suspend fun tryPipedInstance(videoId: String, instanceUrl: String): String?` function that calls `PipedClient.createService(instanceUrl).getStreams(videoId)`, runs `AudioStreamSelector.selectBest()`, and returns the URL or null on failure (catches all exceptions, logs warnings)
    - _Requirements: 5.1, 5.3_

  - [x] 4.2 Implement tryPipedWithCacheAndRace orchestration
    - Create a private `suspend fun tryPipedWithCacheAndRace(videoId: String): String?` function
    - Check `PipedInstanceCache.getCachedInstance()`; if non-null, try that instance with `withTimeoutOrNull(3000)` wrapping `tryPipedInstance()`
    - On cache hit success: call `PipedInstanceCache.recordSuccess(instance)`, log success, and return the URL immediately
    - On cache hit failure: call `PipedInstanceCache.recordFailure(instance)`, log failure, and fall through to parallel race
    - Implement the parallel race: wrap in `withTimeoutOrNull(8000)` + `coroutineScope`; launch an `async` for each instance in `pipedInstances` calling `tryPipedInstance()`; use `select` on the deferred results; return the first non-null result and cancel the scope
    - On parallel race success: call `PipedInstanceCache.recordSuccess(winningInstance)`, log success, cancel remaining coroutines, return URL
    - On parallel race failure (all null or timeout): log failure, return null
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6, 3.2, 3.3, 3.4_

  - [x] 4.3 Rewrite fetchAudioStreamLink to use the new phase functions
    - Replace the body of `fetchAudioStreamLink()` with sequential calls: `tryInnerTube(videoId)` → `tryPipedWithCacheAndRace(videoId)` → `tryNewPipe(videoId)` → return `""`
    - Each phase checks the result and returns early on non-null/non-empty
    - Log phase transitions with phase name and outcome
    - Wrap the entire function in a top-level try-catch that returns `""` on unexpected exceptions
    - _Requirements: 5.1, 5.2, 5.3, 6.1, 6.2, 6.3, 6.4_

- [x] 5. Wire preconnect on MusicRepository initialization
  - [x] 5.1 Add preconnect call in MusicRepository init block
    - In `MusicRepository.kt`, inside the `init` block, add a coroutine launch on `Dispatchers.IO` that calls `PipedClient.preconnect(pipedInstances.take(3))`
    - Ensure this does not block app startup — use `repositoryScope.launch` with `Dispatchers.IO`
    - Log a message before and after preconnect attempt
    - _Requirements: 4.2, 4.4_

- [x] 6. Checkpoint - Verify compilation and manual smoke test
  - Ensure all code compiles without errors, ask the user if questions arise.

- [x] 7. Add test dependencies and write tests
  - [x] 7.1 Add test dependencies to build.gradle.kts
    - Open `app/build.gradle.kts`
    - Add `testImplementation("io.kotest:kotest-property:5.8.0")`
    - Add `testImplementation("io.kotest:kotest-assertions-core:5.8.0")`
    - Add `testImplementation("io.mockk:mockk:1.13.9")`
    - Add `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")`
    - _Requirements: All (test infrastructure)_

  - [ ]* 7.2 Write property test for PipedInstanceCache state machine (Property 3)
    - Create `app/src/test/java/com/example/sonara/network/PipedInstanceCachePropertyTest.kt`
    - **Property 3: Cache state machine consistency**
    - **Validates: Requirements 3.1, 3.6, 3.7**
    - Use Kotest `checkAll` with a custom `Arb` that generates random sequences of `recordSuccess(url)` and `recordFailure(url)` operations
    - Assert: after `recordSuccess(url)`, `getCachedInstance()` returns `url`
    - Assert: after < 3 consecutive `recordFailure(url)`, cache still returns `url`
    - Assert: after exactly 3 consecutive `recordFailure(url)`, cache returns null
    - Assert: after failures followed by `recordSuccess(url)`, failure counter resets
    - Call `PipedInstanceCache.clear()` before each iteration

  - [ ]* 7.3 Write property test for AudioStreamSelector optimality (Property 6)
    - Create `app/src/test/java/com/example/sonara/data/AudioStreamSelectorPropertyTest.kt`
    - **Property 6: AudioStreamSelector always picks the optimal stream**
    - **Validates: Requirements 5.4**
    - Use Kotest `checkAll` with a custom `Arb` that generates random non-empty lists of `PipedAudioStream` with valid URLs, bitrate > 0, and mimeType starting with "audio/"
    - Assert: the returned stream has the highest codec preference score; among equal scores, it has the highest bitrate
    - Verify the selection is deterministic for the same input

  - [ ]* 7.4 Write property test for total failure yields empty string (Property 5)
    - Create `app/src/test/java/com/example/sonara/data/StreamResolverFailurePropertyTest.kt`
    - **Property 5: Total failure yields empty string without exception**
    - **Validates: Requirements 5.2**
    - Use MockK to mock `InnerTubeClient`, `PipedClient`, and NewPipe to all fail/return null
    - Use Kotest `checkAll` with `Arb.string()` for random video IDs
    - Assert: `fetchAudioStreamLink()` returns `""` and does not throw

  - [ ]* 7.5 Write unit tests for InnerTube timeout configuration
    - Create `app/src/test/java/com/example/sonara/network/InnerTubeClientTest.kt`
    - Use reflection to access the private `client` field of `InnerTubeClient`
    - Assert `connectTimeoutMillis == 5000` and `readTimeoutMillis == 7000`
    - _Requirements: 1.1, 1.2_

  - [ ]* 7.6 Write unit tests for PipedClient connection pool configuration
    - Create `app/src/test/java/com/example/sonara/network/PipedClientTest.kt`
    - Use reflection to access the private `okHttpClient` field and its `connectionPool`
    - Assert pool `maxIdleConnections == 8` and keep-alive duration is 30 seconds
    - Assert `connectTimeoutMillis == 5000` and `readTimeoutMillis == 7000`
    - _Requirements: 4.1, 2.5_

- [x] 8. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific configurations and edge cases
- The design uses Kotlin throughout — no language selection needed
- Test directory structure: `app/src/test/java/com/example/sonara/` mirrors main source

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "3.1"] },
    { "id": 1, "tasks": ["2.2", "4.1"] },
    { "id": 2, "tasks": ["4.2", "5.1"] },
    { "id": 3, "tasks": ["4.3"] },
    { "id": 4, "tasks": ["7.1"] },
    { "id": 5, "tasks": ["7.2", "7.3", "7.4", "7.5", "7.6"] }
  ]
}
```
