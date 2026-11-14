# WatchLearn AI Android MVP

This is the source project for a consent-first Android assistant. It includes:

- A screen-recording consent dialog followed by Android's MediaProjection consent flow.
- A working foreground MediaProjection + MediaRecorder service that saves video-only MP4 files to `Movies/WatchLearn AI` on Android 10+.
- WatchLearn AI branding: navy/cyan/amber theme, adaptive launcher icon, and Android 12-compatible splash screen.
- A handoff to Android Accessibility Settings.
- Chat, automation, model-provider, and settings screens.
- A provider selector for OpenAI, Anthropic, Gemini, and OpenAI-compatible endpoints.
- Clear separation between personal sideload mode and Play Store mode.

## Important implementation status

This workspace does not contain an Android SDK or Gradle distribution, so an APK cannot be compiled or tested here. The source is intentionally honest about the remaining production work:

1. Connect the app to an authenticated HTTPS backend.
2. Implement backend account creation, sessions, tenant isolation, encrypted API-key storage, key rotation, provider adapters, spend limits, and audit logs.
3. Implement a narrow, typed action registry for supported workflows. Do not ship unrestricted autonomous AccessibilityService behavior to Google Play.
4. Add Android tests on real Android 10, 14, and 15 devices and produce separate sideload and release builds.

## Recording behavior

The app asks for its own confirmation first, then opens Android's system capture dialog. The system dialog cannot be bypassed. After approval, the service records video-only MP4 through `MediaProjection` and `MediaRecorder`, keeps a visible foreground notification, saves the finished video in the Movies library on Android 10+, and stops when the user taps Stop, the system ends projection, or the task stops.

## Build on a machine with Android SDK

```bash
./gradlew :app:assembleDebug
```

If `./gradlew` is not present after importing the project, open it in Android Studio and let Gradle sync, or generate a Gradle wrapper with Gradle 8.9. The debug APK will be under `app/build/outputs/apk/debug/`.

## GitHub Actions build

The repository includes `.github/workflows/android-build.yml`. Every push to `main`, pull request, or manual workflow run installs the Android SDK and Gradle, runs lint, builds the debug APK and unsigned release AAB, and uploads both as a `watchlearn-ai-builds` workflow artifact. A signed Play Store release still requires a signing key configured as protected GitHub secrets.

## Backend contract to add next

The app should call an HTTPS backend with endpoints such as:

- `POST /v1/auth/signup`
- `POST /v1/auth/login`
- `POST /v1/providers/keys` — accepts a provider and raw key only over TLS, encrypts it server-side, and never returns it.
- `GET /v1/models`
- `POST /v1/tasks/plan`
- `POST /v1/tasks/execute` — accepts only typed, approved actions.
- `POST /v1/automations`

Do not put an OpenAI or customer API key in the APK, source code, logs, crash reports, or analytics payloads.

## ChatGPT and plugins boundary

The app can use official APIs and connectors that provide authorization. It cannot generically edit every ChatGPT chat or invoke every ChatGPT plugin simply because a customer has a ChatGPT account. Each supported integration needs its own official API/OAuth connection and permission scope.
