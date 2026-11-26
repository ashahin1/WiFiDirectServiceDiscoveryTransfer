# WiFiDirectServiceDiscoveryTransfer

An Android research testbed implementing the **ADS (Alert Dissemination using Service Discovery)** protocol over Wi-Fi Direct DNS-SD, originally developed for PhD research and modernized to Android 16 (API 36).

Reference paper: <http://ieeexplore.ieee.org/document/7249445/>

> **Branch Note:** The `master` branch preserves the original 2014 PhD snapshot (`API 21`, Gradle `2.2.1`, Google Play Services `6.1.71`). All Android 16 modernization work lives on `modernize/android-16`.

---

## Architecture Overview

The app is 100% **Google Play Services–free** and cleanly modularized for protocol experimentation and physical-device field testing:

| Package / Component | Responsibility |
|---|---|
| `protocol/` | Pure-JVM deterministic ADS engine (`AdsProtocolEngine`), validated configuration (`AdsPolicyConfig`), and `Locale.US` chunked DNS-SD codec (`AdsWireCodec`) |
| `transport/` | Pluggable `AlertTransport` interface + serialized `WifiDirectDnsSdTransport` (`_presence._tcp` DNS-SD command queue, API 33+ `startListening`/`stopListening`, API 35+ `WifiP2pListener`) |
| `location/` | Play-Services-free `PlatformLocationSource` using `LocationManagerCompat` (`FUSED_PROVIDER` on API 31+, GPS/Network on API 26–30) |
| `service/` | `AdsDisseminationService` (`LifecycleService`, foreground types `connectedDevice`, `location`) driving 1s TTL decay, periodic discovery scans, and live `StateFlow<AdsUiState>` |
| `map/` | Pluggable `MapController` interface + `OsmDroidMapController` (OpenStreetMap `MAPNIK` tiles cached in `cacheDir/osmdroid`, zero API keys or billing) |
| `telemetry/` | Bounded ring-buffer `ExperimentLogger` feeding the timestamped live log panel and Storage Access Framework (SAF) CSV / JSONL exports |
| `ui/` | Material 3 DayNight + Edge-to-Edge (`WindowInsets`) `MainActivity`, `SettingsActivity`, and `SettingsFragment` (`PreferenceFragmentCompat`) |

---

## Build Requirements & Commands

- **JDK:** 17 or 21
- **Android SDK:** `compileSdk = 36`, `targetSdk = 36` (Android 16), `minSdk = 26` (Android 8.0+)
- **Gradle / AGP:** Gradle `9.6.0` wrapper + Android Gradle Plugin `9.5.3` (built-in Kotlin)

Run all verification checks from the repository root:

```bash
# Run pure-JVM unit tests & 3-node multi-hop ADS simulation
./gradlew :app:testDebugUnitTest

# Build debug APK
./gradlew :app:assembleDebug

# Run Android Lint static analysis
./gradlew :app:lintDebug

# Verify release build / R8 rules compile
./gradlew :app:assembleRelease
```

---

## Runtime Permissions & Device Setup (Android 8–16)

1. **Runtime Permissions Requested in-App:**
   - **Android 13+ (API 33–36):** `NEARBY_WIFI_DEVICES` (`neverForLocation`), `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (for hazard GPS coordinates), and `POST_NOTIFICATIONS` (for foreground dissemination status & Stop button).
   - **Android 8–12 (API 26–32):** `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (required by Android for Wi-Fi Direct discovery and GPS fixes).
2. **System Location Mode:** Android still requires **System Location** to be toggled **ON** at the OS level for Wi-Fi Direct peer and service discovery scans on physical hardware. The app displays a warning banner with a shortcut to System Location settings whenever Location Mode is off.
3. **Offline Map Usage:** `osmdroid` caches viewed OpenStreetMap tiles automatically in app-private cache (`cacheDir/osmdroid/tiles`). Pan over the experiment area once while online before taking devices into field tests without cellular connectivity.

---

## ADS v2 DNS-SD Wire Format

To eliminate comma/decimal-locale corruption, multi-hazard map overwrites, and Wi-Fi Direct randomized P2P MAC address drift, alerts are serialized on DNS-SD service type `_presence._tcp` across chunked instances named `_wfdsdt_<senderNodeId>_<chunkIndex>`:

- **`v`**: `"2"` (protocol version)
- **`s`**: `<senderId>` (8-character hex node identifier of the transmitting device)
- **`h0`, `h1`, ...**: `<originId>,<seqNo>,<lat6dp>,<lng6dp>,<valid01>,<hopCount>` (formatted and parsed strictly in `Locale.US`, chunked so each TXT packet remains `<= 200` wire bytes)

Invalidated local hazards remain advertised with `valid=0` tombstones until `max_ttl_seconds` expires so neighboring devices propagate the removal without resurrecting cleared alerts.

---

## Experiment Settings & Telemetry Export

Configure dissemination parameters in **Settings**:
- **Allow forwarding hazards** (`allow_forwarding_checkbox`)
- **Accept higher sequence numbers only** (`enforce_higher_seq_checkbox`)
- **Update hazards from original sender only** (`update_from_original_source_checkbox`)
- **Hazard TTL (seconds)** (`max_ttl_seconds`, range `5–3600`, default `30`)
- **Discovery interval (seconds)** (`discovery_period_seconds`, range `2–300`, default `5`)

Export recorded experiment events from the toolbar overflow menu (**Export CSV Log** / **Export JSONL Log**):
- **Columns / JSON fields:** `wall_time_utc`, `monotonic_ms`, `local_node_id`, `event_type`, `origin_id`, `sender_id`, `seq_no`, `latitude`, `longitude`, `is_valid`, `hop_count`, `detail`.
