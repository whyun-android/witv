# AGENTS.md

## Cursor Cloud specific instructions

### Project overview

WiTV is an Android TV IPTV player (Java, Gradle). There is no backend service, database, or Node.js tooling — it is a single-module Android app built entirely with Gradle.

### Environment

- **JDK 17** is required (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`). The VM ships with JDK 21 by default; the update script installs JDK 17 and configures `~/.bashrc`.
- **Android SDK** is installed at `/opt/android-sdk` with platform API 36 and build-tools 36.0.0. `ANDROID_HOME` and `ANDROID_SDK_ROOT` are set via `~/.bashrc`.
- The Gradle wrapper (`./gradlew`) auto-downloads Gradle 8.9; no manual Gradle install is needed.

### Key commands (see README for full details)

| Task | Command |
|------|---------|
| Unit tests | `./gradlew testDebugUnitTest --no-daemon` |
| Debug APK build | `./gradlew assembleDebug --no-daemon` |
| Release APK build | `./gradlew assembleRelease --no-daemon` |

### Gotchas

- `./gradlew lintDebug` currently fails due to pre-existing `NewApi` lint errors in the codebase. Lint is **not** part of CI — CI only runs `testDebugUnitTest` + `assembleDebug`.
- The app uses `compileSdk 36` which triggers a suppressible warning via `android.suppressUnsupportedCompileSdk=36` in `gradle.properties`.
- Room annotation processor produces a warning about multiple constructors on `FavoriteChannel.java` — this is harmless.
- This is an Android TV app with no web frontend build step; the files in `app/src/main/assets/web/` are plain HTML/JS served by the embedded NanoHTTPD server at runtime.
- There is no Android emulator on the cloud VM, so the APK cannot be run directly. Testing is limited to unit tests and build verification.
