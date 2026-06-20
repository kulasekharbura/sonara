# Requirements Document

## Introduction

This document specifies requirements for fixing the broken audio stream playback in the Sonara Android music app. The app currently fails to play songs because the stream URL extraction pipeline relies on outdated or dead services. The fix introduces the Piped API as the primary stream resolution method with multi-instance failover, retains NewPipeExtractor as a fallback, and removes dead code (Cobalt API, Render.com backend).

## Glossary

- **Stream_Resolver**: The component in MusicRepository responsible for converting a YouTube video ID into a playable audio stream URL
- **Piped_API**: A free, open REST API that returns deciphered and proxied YouTube audio stream URLs at the endpoint `GET /streams/{videoId}`
- **Piped_Instance**: A specific deployment of the Piped API accessible at a unique base URL (e.g., `https://pipedapi.kavin.rocks`)
- **Audio_Stream_Selector**: The component that chooses the best audio stream from a list of candidates based on codec preference and bitrate
- **NewPipe_Extractor**: The NewPipeExtractor library (v0.24.4) used as a secondary fallback for deciphering YouTube stream URLs
- **Instance_Rotation**: The pattern of sequentially trying multiple Piped instances until one succeeds or all fail
- **ExoPlayer**: The Media3 ExoPlayer component that receives resolved stream URLs and handles audio playback

## Requirements

### Requirement 1: Piped API Service Interface

**User Story:** As a developer, I want a Retrofit interface for the Piped API, so that the app can fetch stream metadata from Piped instances using the existing networking stack.

#### Acceptance Criteria

1. THE Piped_API service interface SHALL define a GET endpoint at path `streams/{videoId}` that accepts a video ID path parameter and returns a PipedStreamResponse object
2. THE Piped_API service interface SHALL be compatible with the existing Retrofit 2.11 and Gson converter stack
3. WHEN creating a Piped_API service instance, THE system SHALL accept a dynamic base URL parameter to support multiple Piped instances

### Requirement 2: Piped Response Data Models

**User Story:** As a developer, I want data classes that model the Piped API response, so that JSON responses are deserialized into type-safe Kotlin objects.

#### Acceptance Criteria

1. THE PipedStreamResponse data class SHALL contain an `audioStreams` field of type `List<PipedAudioStream>`
2. THE PipedAudioStream data class SHALL contain fields for `url` (String), `bitrate` (Int), `mimeType` (String), `codec` (String?), `format` (String?), and `quality` (String?)
3. WHEN the Piped API returns JSON with nullable fields, THE data models SHALL handle missing fields without throwing exceptions

### Requirement 3: Primary Stream Resolution via Piped API

**User Story:** As a user, I want songs to play reliably, so that I can listen to music without failures.

#### Acceptance Criteria

1. WHEN a stream URL is requested for a video ID, THE Stream_Resolver SHALL attempt the Piped_API as the primary resolution method before any fallback
2. WHEN a Piped_Instance returns a valid response with non-empty audio streams, THE Stream_Resolver SHALL select the best audio stream and return its URL
3. WHEN a Piped_Instance fails (timeout, HTTP error, empty audio streams), THE Stream_Resolver SHALL try the next Piped_Instance in the configured list
4. THE Stream_Resolver SHALL configure a connect timeout of 10 seconds and a read timeout of 10 seconds for each Piped_Instance request
5. THE Stream_Resolver SHALL attempt all configured Piped instances before falling back to alternative methods

### Requirement 4: Instance Rotation and Failover

**User Story:** As a user, I want playback to work even when some Piped servers are down, so that temporary server issues don't prevent me from listening.

#### Acceptance Criteria

1. THE Stream_Resolver SHALL maintain a list of at least 3 Piped_Instance URLs for failover
2. WHEN iterating through Piped instances, THE Stream_Resolver SHALL try each instance sequentially until one succeeds
3. IF a Piped_Instance returns an error or times out, THEN THE Stream_Resolver SHALL log a warning and proceed to the next instance without crashing
4. WHEN all Piped instances fail, THE Stream_Resolver SHALL proceed to the NewPipe_Extractor fallback

### Requirement 5: Audio Stream Selection

**User Story:** As a user, I want to hear the best audio quality available, so that my listening experience is optimal.

#### Acceptance Criteria

1. THE Audio_Stream_Selector SHALL filter streams to include only those with a `mimeType` starting with "audio/", a non-empty `url`, and a `bitrate` greater than 0
2. THE Audio_Stream_Selector SHALL prefer streams with opus/webm codec over m4a/aac codec, and prefer m4a/aac over other codecs
3. WHEN multiple streams have the same codec preference, THE Audio_Stream_Selector SHALL select the stream with the highest bitrate
4. IF no valid audio-only streams exist in the response, THEN THE Audio_Stream_Selector SHALL return null to signal no suitable stream was found

### Requirement 6: NewPipeExtractor Fallback

**User Story:** As a user, I want a secondary fallback if all Piped servers are unavailable, so that playback has maximum resilience.

#### Acceptance Criteria

1. WHEN all Piped instances fail to resolve a stream URL, THE Stream_Resolver SHALL attempt resolution using NewPipe_Extractor as a secondary fallback
2. WHEN using NewPipe_Extractor, THE Stream_Resolver SHALL filter to progressive HTTP audio streams and select the highest bitrate stream
3. IF NewPipe_Extractor also fails, THEN THE Stream_Resolver SHALL return an empty string to indicate total resolution failure
4. THE Stream_Resolver SHALL never throw an unhandled exception to its caller regardless of which resolution method fails

### Requirement 7: Dead Code Removal

**User Story:** As a developer, I want dead fallback code removed, so that the codebase is clean and maintainable.

#### Acceptance Criteria

1. THE Stream_Resolver SHALL NOT contain any Cobalt API endpoint references or request logic
2. THE Stream_Resolver SHALL NOT contain any Render.com backend URL references or fallback logic
3. WHEN dead code is removed, THE remaining stream resolution logic SHALL compile and function independently without referencing removed components
