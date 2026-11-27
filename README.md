# NX10 Asparagus Android SDK

Lightweight, zero-bloat Android telemetry SDK (`com.nx10:asparagus-sdk`) implementing **`asparagus-contract-v7`** (`schema_version = 3`) for Android `minSdk 24+`.

## Highlights
- **Zero third-party bloat:** Pure Kotlin Protobuf v3 binary wire codec, RFC 8259 JSON codec, and `HttpURLConnection` transport (`androidx.core:core-ktx` and `androidx.lifecycle:lifecycle-runtime-ktx` only).
- **Full `asparagus-contract-v7` (Schema v3) capture:**
  - 4-sensor motion capture (accelerometer, gyroscope, gravity, magnetometer) with delta/hold encoding and gap detection, plus observer-only multi-touch tracking.
  - 18 event/span/state rules across all **10 standard spans** (including `banner` / `ad_banner` winning before the `ad_` prefix) and reserved `nx10_*` state protection with developer warning logs (`Log.w`).
  - SDK-managed system state (`nx10_screen_brightness` with W15-04 `>= 0.05` delta and `<= 1/s` rate throttling + trailing delivery, and `nx10_screen_locked`).
  - Automatic persistent `CollectionHistory` (`sessions_before`, `collected_seconds_before`, `longest_session_seconds_before`), `settings_label` device share visibility, built-in safety-net settings (no bundled `defaults.json`), guaranteed non-empty `app_version` (plus optional `sdk.appVersionOverride`), W8-26 bounds splitting, persistent disk queue, and jittered retry uploader.
- **Fault-isolated:** Every public entry point catches exceptions so telemetry can never crash the host app.

## Quick Integration

Add the dependency (`com.nx10:asparagus-sdk:0.2.0`) from GitHub Packages or a local composite build:

```kotlin
dependencies {
    implementation("com.nx10:asparagus-sdk:0.2.0")
}
```

Initialize and use `AsparagusSdk` in your Android app:

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var sdk: AsparagusSdk

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sdk = AsparagusSdk.getOrCreate(applicationContext)

        // Optional: override the host app version reported at session start
        // sdk.appVersionOverride = "2.4.1"

        // Defaults to Stage (useProduction = false). Set to true for Production.
        sdk.useProduction = false
        sdk.startSession(apiKey = "YOUR_DEPLOYMENT_API_KEY")

        // Core events & game state
        sdk.coreEvents.levelStarted(level = "level_1", attemptNumber = 1.0)
        sdk.setAttribute("screen", Value.Text("gameplay"))
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        sdk.observeMotionEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
```

### Built-in Session Start Endpoints
- **Stage (`useProduction = false`, default):** `https://control-plane.affectstack-stage.com/routes/asparagus/sessions/start`
- **Production (`useProduction = true`):** `https://control-plane.affectstack.com/routes/asparagus/sessions/start`

## Build, Test & Publish

```bash
# Run all JVM unit & contract end-to-end tests
./gradlew testDebugUnitTest

# Build the release AAR (with bundled consumer-rules.pro)
./gradlew assembleRelease

# Publish com.nx10:asparagus-sdk:0.2.0 to local Maven (~/.m2)
./gradlew publishToMavenLocal

# Publish to GitHub Packages (requires GITHUB_ACTOR/GITHUB_TOKEN or -Pgpr.user/-Pgpr.key)
./gradlew publish
```
