# livekit-kmp

Kotlin Multiplatform bindings for [`livekit-ffi`](https://github.com/livekit/rust-sdks), the
C-ABI core that also powers LiveKit's Python, Node and Unity SDKs.

Exists because there is no LiveKit SDK for JVM desktop, and because binding the FFI once
covers desktop, Android and iOS instead of maintaining three separate integrations.

## Status

**Early, but joinable.** The native transport works and a `LiveKitRoom` sits on top of it with
connect / disconnect, connection state and a live participant list. Media is not wired yet.

| | State |
|---|---|
| FFI transport (JVM) | works, covered by a test |
| Per-platform native artifacts | linux / macos / windows, x86_64 + arm64 |
| Room API (connect, state, participants) | works |
| Tracks (publish / subscribe) | not started |
| Audio device I/O | not started |
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
room.disconnect()
```

## Notes

- The FFI does **no device I/O**. Audio and video are frame-fed: push PCM/I420 frames to a
  source, read frames from a stream. Encoding, echo cancellation and resampling are provided.
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
