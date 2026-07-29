# livekit-kmp

Kotlin Multiplatform bindings for [`livekit-ffi`](https://github.com/livekit/rust-sdks), the
C-ABI core that also powers LiveKit's Python, Node and Unity SDKs.

Exists because there is no LiveKit SDK for JVM desktop, and because binding the FFI once
covers desktop, Android and iOS instead of maintaining three separate integrations.

## Status

**Voice works.** Join a room, be heard, hear everyone else, mute and unmute. Video is not
wired yet.

| | State |
|---|---|
| FFI transport (JVM) | works, covered by a test |
| Per-platform native artifacts | linux / macos / windows, x86_64 + arm64 |
| Room API (connect, state, participants) | works |
| Microphone publish / mute | works |
| Remote audio (playback, subscription state) | works |
| Audio devices (enumerate, select) | works, via WebRTC's ADM |
| Video | not started |
| Android / iOS targets | not started |

## How it fits together

`livekit-ffi` is a single shared library exposing four functions. Everything else is protobuf:

```c
void     livekit_ffi_initialize(FfiCallbackFn cb, bool capture_logs, const char* sdk, const char* sdk_version);
uint64_t livekit_ffi_request(const uint8_t* data, size_t len, const uint8_t** res_ptr, size_t* res_len);
bool     livekit_ffi_drop_handle(uint64_t handle_id);
void     livekit_ffi_dispose(void);
```

The build pins one FFI version (`libs.versions.toml` -> `livekitFfi`) and downloads both the
`.proto` files and the native binaries from that exact release tag, so generated code can never
drift from the binary it talks to. Wire generates the Kotlin (the protocol is proto2). JNA does
the JVM binding, since the project targets JVM 11 and Panama needs 22+.

## Usage

```kotlin
val room = LiveKitRoom(scope)
room.connect(url = serverUrl, token = jwt)   // suspends until the server accepts or rejects
room.state.collect { … }                     // Disconnected / Connecting / Connected / Reconnecting
room.participants.collect { … }              // joins, leaves and active speakers

```

Hearing anyone requires platform audio, so it belongs to the room:

```kotlin
val audio = PlatformAudio.open()
val room = LiveKitRoom(scope, audio)
room.connect(url, token)

audio.devices()                    // microphones and speakers
audio.selectMicrophone(device)

room.setMicrophoneEnabled(true)    // publishes once; later calls mute that same track
room.participants                  // audioSubscribed / audioMuted / isSpeaking per person
```

A room built without a `PlatformAudio` joins deaf: WebRTC's ADM renders every subscribed
remote track and only runs while a handle is held. That is the right shape for a headless or
video-only client and a bug anywhere else.

## Notes

- Audio needs no PCM pumping. The FFI exposes WebRTC's Audio Device Module (`PlatformAudio`),
  which owns capture and playback and enumerates devices, so a desktop app needs nothing from
  `javax.sound.sampled`. It also puts echo cancellation on the right side of the device loop,
  which hand-fed frames cannot achieve. A push-based source exists too, for callers that have
  their own frames.
- Video **is** frame-fed: push I420 to a source, read frames from a stream. Encoding is the
  FFI's job either way.
- `size_t` is mapped to `Long`, correct on 64-bit only. Every desktop binary LiveKit ships is
  64-bit; a 32-bit target would need a size mapper.
- The event callback runs on LiveKit's threads and must not block, so it copies the bytes and
  drops events rather than stalling if a collector is slow.
- Natives ship as separate `livekit-kmp-natives-<platform>` artifacts, not inside the main
  jar, which stays ~3 MB. Depend on the platforms you actually distribute:

  ```kotlin
  implementation("io.github.nostrord:livekit-kmp-jvm:<version>")
  implementation("io.github.nostrord:livekit-kmp-natives-linux-x86-64:<version>")
  ```

  Each native jar lays the library out under its JNA resource prefix, so `Native.load`
  extracts it off the classpath with no `jna.library.path` needed. The test suite depends on
  the host's jar the same way, so the packaging is covered by the same test as the transport.
