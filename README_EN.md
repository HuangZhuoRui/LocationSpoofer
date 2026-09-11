<div align="center">

<h1>LocationSpoofer</h1>

<p>Android system-level location spoofing and wireless environment simulation framework based on KernelSU + LSPosed</p>

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![KernelSU](https://img.shields.io/badge/Root-KernelSU-orange.svg)](https://kernelsu.org)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20101%2B-purple.svg)](https://github.com/LSPosed/LSPosed)
[![Telegram](https://img.shields.io/badge/Telegram-Group-blue.svg)](https://t.me/+CsxZGItXdW40ZWVl)

[简体中文](README.md) | [English](README_EN.md)

</div>

---

> **📢 Join our [Telegram Group](https://t.me/+CsxZGItXdW40ZWVl) for** ~~technical discussion, updates,~~ **bragging and shitposting.**
>
> **📖 Read the [LocationSpoofer Detailed Tutorial](https://docs.google.com/document/d/1fFEz3k7ATdN2dwY1L3RJn1QuzgokIsslNa88-vUPxPk/edit?usp=sharing)**

---

## Introduction

In modern Android risk control and anti-cheating environments, standard developer options ("Mock Location") have long been classified as a critical risk factor by commercial positioning and fraud detection SDKs (such as AMap Security, Tencent Positioning, Baidu Maps SDK, NetEase EasyShield, and enterprise attendance verification systems). These detection frameworks do not merely check the `isFromMockProvider` / `isMock` API flags; they actively collect and cross-verify ambient physical signals:

*   **Nearby Wi-Fi access points and BSSID lists** (cross-checked against global Wi-Fi fingerprint databases)
*   **Cellular base stations** (GSM / WCDMA / LTE / 5G NR cell identifiers and carrier profiles)
*   **Local Bluetooth BLE beacons**
*   **Underlying GNSS satellite constellations and visible ephemeris matrices**
*   **Accelerometer and step-counter hardware sensor linkage**
*   Time-series coordinate analysis (FFT and variance checks) to detect artificial static coordinates or deterministic linear trajectories.

**LocationSpoofer** is a **system-level virtual positioning and wireless environment simulation framework** engineered specifically to counter deep anti-cheating detections.
Powered by **KernelSU / APatch / Magisk** for root privileges and **LSPosed (libxposed API 101+)** to hook target processes in the Zygote stage, LocationSpoofer intercepts and fakes all positioning, wireless networking, and motion sensor API responses with physical fidelity. This guarantees that target apps obtain self-consistent, realistic location and radio fingerprints without detecting virtualization.

---

## Features

```
┌─────────────────────────────────────────────────────────────────────────┐
│                             LocationSpoofer                             │
│        System-Level Location & Wireless Environment Simulation          │
└─────────────────────────────────────────────────────────────────────────┘
        │                            │                            │
        ▼                            ▼                            ▼
  【Spatial & Physics】        【Radio & Sensors】            【Anti-Detection】
  • Triple Map Engine          • Wi-Fi Scan & Connection      • Xposed Stack Frame Scrubbing
  • WGS-84/GCJ-02/BD-09 Auto   • 2G-5G NR Cell Towers         • ClassLoader Isolation
  • Ornstein-Uhlenbeck Jitter  • BLE Beacons Filtering        • isFromMockProvider Erased
  • Gait Noise & Drift         • WiGLE / OpenCellID Import    • AppOps OP_MOCK_LOCATION Masked
  • Real Road Snapping         • Spatial IDW Interpolation    • Settings.Secure Key Override
  • Floating Joystick          • Step Counter Sensor Linkage  • MultiDex Dynamic DEX Hooking
```

### 1. Multi-Map Engines & Coordinate Adaptation
* **Triple Engine Support**: Natively integrates AMap 3D, Baidu Maps, and Google Maps for flexible worldwide POI search and road-network routing.
* **Out-of-the-Box & Multilingual Support**: Default "Auto Match" smoothly uses AMap across all languages (including Chinese, English, Arabic, etc.), functioning without requiring Google Play Services or proxy setups.
* **Custom Google API Key**: No default Google Maps API Key is embedded. To use Google Maps rendering and Places global search, simply input your own Google API Key in the Settings page.
* **Smart Automatic Coordinate Adaptation (Smart Auto)**:
  * Native Android framework APIs consistently output standard `WGS-84` physical coordinates and satellite metadata;
  * Third-party map SDKs (AMap, Tencent, Baidu) and rendering layers (e.g., BaiduMap `MyLocationData` blue dot) automatically map to their respective coordinate systems (`GCJ-02` / `BD-09`), eliminating shifts and `(0.0, 0.0)` fallback pulls;
  * Supports manual per-app coordinate system overrides (`WGS-84`, `GCJ-02`, or `BD-09`).
* **Zero-Latency Computation**: Coordinate transformations are pre-computed in memory and fetched directly inside Xposed hooks, avoiding repeated trigonometric calculations in high-frequency callback loops.

### 2. GPS Physics Jitter & Gait Simulation
Real GPS receivers naturally output coordinates with Gaussian white noise caused by ionospheric scintillations, multipath reflections, and clock offsets.
* **Ornstein-Uhlenbeck Process**: Implements a mean-reverting physical random walk model:

  $$\mathrm{d}X_t = -\alpha X_t \mathrm{d}t + \sigma \mathrm{d}W_t$$

  where $\sigma$ is the noise intensity and $\alpha$ is the mean-reversion coefficient (configured to `0.05` to pull drift back by 5% per second). This produces physical, low-frequency drift while strictly bounding displacement within 3-Sigma limits (clamped to 4 meters maximum) to prevent sudden location jumps.

* **Gait Lateral Jitter**: When in walking or running modes, the engine applies a perpendicular lateral offset:

  $$\Delta L_{\text{lateral}} = 0.15 \cdot \mathcal{N}(0, 1) \quad (\text{meters})$$

  synchronized with step cadence, replicating natural human body sway.

* **Altitude & Accuracy (GDOP) Drift**: Horizontal accuracy and altitude fluctuate dynamically to simulate changing satellite geometries and tropospheric delays.

### 3. Anti-Detection & MultiDex Hooking
* **Deep Stack Trace Scrubbing**: Dynamically filters `Throwable.getStackTrace` and `Thread.getStackTrace` to expunge calling frames matching `de.robv.android.xposed`, `io.github.libxposed`, `org.lsposed`, preventing anti-cheat SDKs from discovering hook frameworks in exception traces.
* **ClassLoader Isolation**: Hooks `Class.forName` and `ClassLoader.loadClass` to return `ClassNotFoundException` upon probing for known Xposed classes.
* **MultiDex Dynamic Awareness**: Intercepts `ClassLoader.loadClass` to dynamically detect and install hooks for positioning components loaded from secondary Dex files (e.g., `classes16.dex`), locked to the process host package name via `/proc/self/cmdline` to prevent in-app WebViews or plugins from corrupting the state.
* **Mock Flag Eraser**:
  * Forces `Location.isFromMockProvider()` and `Location.isMock()` to permanently return `false`;
  * Reflectively overwrites private fields `mMock` and `mIsFromMockProvider` inside `Location` instances to `false` and scrubs `mockLocation` flags from Extra Bundles;
  * Intercepts `AppOpsManager`'s `OP_MOCK_LOCATION (58)` checks to return `MODE_IGNORED (1)`;
  * Intercepts `Settings.Secure` queries for `mock_location` and `allow_mock_location` to return `0`;
  * Replaces test/mock providers in `LocationManager` and presents them as native `gps` signals.

### 4. Wi-Fi, Cell Tower & Bluetooth Simulation
* **On-Site Environment Scanner**: Background sweep utility scans and logs physical Wi-Fi APs (SSID/BSSID/RSSI/frequency/channel/standard), Cell Towers (GSM, WCDMA, CDMA, LTE, 5G NR configurations with MCC/MNC/LAC/CID/TAC/PCI/NCI and dBm signals), and BLE Bluetooth beacons.
* **Spatial Inverse Distance Weighting (IDW)**: As coordinates move along a path, the engine retrieves recorded points within 50 meters from the Room database and applies inverse square distance weights:

  $$w_i = \frac{1}{d_i^2}$$

  to smoothly interpolate Wi-Fi RSSI and cell dBm levels, avoiding abrupt signal jumps.
* **Fine-Grained Radio Management**: Manually select specific Wi-Fi APs, Cell Towers, or Bluetooth beacons to broadcast during simulation.
* **Full Wireless API Coverage**:
  * Hooks `WifiManager.getScanResults()`, `getConnectionInfo()`, `getConfiguredNetworks()`, `getDhcpInfo()`;
  * Hooks `TelephonyManager.getAllCellInfo()`, `getCellLocation()`, `getNetworkOperator()`, `getServiceState()`, `getSignalStrength()`, `PhoneStateListener`, `TelephonyCallback`;
  * Uses legitimate manufacturer OUIs (TP-Link, Huawei, Xiaomi, Cisco, etc.) for non-scanned areas.
* **Cloud WiGLE & OpenCellID Integration**: Pulls real-world Wi-Fi and cell tower records around any coordinate globally and stores them locally for offline replay.

### 5. Satellite Matrix & NMEA Generation
* **GnssStatus Matrix Injection**: Hooks `GnssStatus` to simulate 20+ active satellites (GPS, BeiDou, GLONASS) with valid PRNs, signal-to-noise ratios (CNR/SNR), azimuths, elevations, and `usedInFix` flags.
* **Dynamic NMEA Protocol Streaming**: Intercepts `OnNmeaMessageListener` / `GpsStatus.NmeaListener` and dynamically generates compliant raw `$GPGGA`, `$GPRMC`, `$GPGSA`, `$GPGSV` sentences in memory with accurate checksums.
* **Satellite Metadata Keep-Alive**: Injects `satellites=20`, `satellites_in_view=20`, `satellites_used_in_fix=18` into `Location.getExtras()` to prevent map SDKs from discarding GPS signals due to missing satellite locks.

### 6. Step Sensor & Motion Simulation
* **Step Counter Linkage**: Hooks `SensorManager` and emulates `Sensor.TYPE_STEP_COUNTER` (cumulative steps) and `Sensor.TYPE_STEP_DETECTOR` (step triggers).
* **Speed-to-Cadence Translation**: Automatically calculates and triggers smooth step increments based on simulated speed and stride length (`~0.7m/step`), compatible with WeChat Sports, fitness trackers, and campus running apps.

### 7. Route Planning & Traffic Light Simulation
* **Real Road Snapping**: Multi-point routing algorithms snap trajectories to physical streets, preventing straight-line navigation through buildings.
* **Traffic Light Waits**: Automatically identifies intersections and pauses for 15 seconds at traffic light nodes before resuming smooth acceleration.
* **Compose Floating Joystick**: Overlay joystick with speed controls (0 ~ 10 m/s) and steering damping adjustments for live manual navigation.

### 8. User Interface
* Built with Jetpack Compose, featuring frosted glass translucency, inner shadows, and damped drag interactions with a decoupled modular architecture.

---

## System Architecture

Built on modern **MVVM + Clean Architecture**, split into 6 Gradle modules by responsibility:

| Module | Type | Responsibility |
|---|---|---|
| `app` | Android App | The host shell: `Application` / `MainActivity`, signing & packaging config, aggregates every module's Koin DI; only bundles the `xposed` module's artifact (the `LocationHooker` class and `META-INF/xposed/*` metadata) into the final APK for LSPosed to scan — it never calls that code directly |
| `app-ui` | Android Library | All Jetpack Compose UI: screens, dialogs, the custom "liquid glass" widget kit (`ui/liquid`), and the ViewModel layer |
| `service` | Android Library | The foreground service `SpoofingService`, the floating joystick `FloatingJoystickService`, boot-completed receivers, and other background/service-layer code |
| `xposed` | Android Library | The LSPosed/Xposed injection module itself: the `LocationHooker` entry point plus the hook implementations under `hooks/` and `hooks/network/` |
| `core-data` | Android Library | The data/domain layer shared by `app`, `app-ui`, and `service`: the Room database, repositories, and core utilities such as `ConfigManager`, `RootManager`, `EnvironmentScanner` |
| `core-geo` | Pure Kotlin/JVM | The only module in the project with no Android dependency: WGS-84 / GCJ-02 / BD-09 coordinate conversion |

Using root shell privileges to bypass package visibility restrictions and SELinux isolation on Android 11+:

```
┌─────────────────────────────────────────────────────────────────────────┐
│       LocationSpoofer Host Process (app/app-ui/service/core-data)       │
│  ┌─────────────────────────┐  ┌──────────────────────────────────────┐  │
│  │     Triple Map Engine   │  │          RouteStateMachine           │  │
│  │ (AMap / Baidu / Google) │  │     (IDLE / READY / RUN / PAUSE)     │  │
│  └────────────┬────────────┘  └──────────────────┬───────────────────┘  │
│               │                                  │                      │
│  ┌────────────▼──────────────────────────────────▼───────────────────┐  │
│  │                     ConfigManager (core-data)                     │  │
│  │     Serializes config + coord mappings, multi-path + SELinux      │  │
│  └──────────────────────────────────┬────────────────────────────────┘  │
│  ┌──────────────────────────────────▼────────────────────────────────┐  │
│  │                     SpoofingService (service)                     │  │
│  │ Foreground service, floating joystick controller, gait/route calc │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────┬───────────────────────────────────┘
                                      │ (core-data ConfigManager writes, chmod 644 + dedicated SELinux type)
                                      ▼
              ┌─────────────────────────────────────────────────┐
              │ 3 config files (tmp / system / app private dir) │
              └────────────────────────┬────────────────────────┘
                                      │ (LocationHooker daemon polls every 1000ms by default, backs off to 10s/60s on failure)
                                      ▼ LSPosed / libxposed (API 101+) Injection
┌─────────────────────────────────────────────────────────────────────────┐
│                           Target App Process                            │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                          LocationHooker                           │  │
│  │  - Location/GNSS: BaseLocationHooker, GnssStatusHooker, etc.      │  │
│  │  - Map SDKs: AMapHooker / BaiduMapHooker / TencentMapHooker       │  │
│  │  - hooks/network/: Wifi* / Cellular* / Bluetooth*Hooker etc.      │  │
│  │  - SensorStepHooker (steps) / AntiDetectionHooker (anti-detect)   │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

> [!NOTE]
> **IPC Design Decision**:
> Sandboxed app processes cannot query a custom `ContentProvider` on Android 11+ due to package visibility rules and SELinux isolation.
> The `core-data` module's `ConfigManager` uses root privileges to write the config as JSON to **three paths at once** (`/data/local/tmp/`, `/data/system/`, and the app's private `files/` directory, with a fourth read-only fallback at `/sdcard/Download/`), with permissions tightened to `644` (owner-writable only). `RootManager` dynamically injects a dedicated SELinux type, `locationspoofer_config_file` (not the generic `shell_data_file`), granting only `read/open/getattr` to the specific domains that need it (`untrusted_app`, `platform_app`, etc.) instead of a blanket world-readable/writable hack.
> The `xposed` module's `LocationHooker` runs a background daemon thread that picks its read-path priority based on the caller's UID, polling every 1000ms by default into an in-memory cache; on read failure it backs off to 10s (general failures) or 60s (when `com.android.phone` hits a permission denial), avoiding pointless high-frequency retries in a broken state. Hook methods on the main thread only ever read the in-memory cache, achieving 0-IO latency and preventing target-app frame drops.

---

## Requirements

* **OS Version**: Android 8.0 (API 26) or higher.
* **Root Manager**: Root access is required (recommended: [**KernelSU**](https://kernelsu.org) / **APatch** / **Magisk**).
* **Xposed Framework**: Installed and enabled **LSPosed (API 101+)** or compatible libxposed API 101+ environment.

---

## Quick Start

### 1. Build & Install

```bash
# 1. Clone the repository
git clone https://github.com/your-username/LocationSpoofer.git

# 2. Build and install Debug APK
./gradlew installDebug
```

### 2. Activation
1. Open **KernelSU / APatch / Magisk** and grant Root permissions to LocationSpoofer.
2. Open **LSPosed Manager**, find **LocationSpoofer**, and **enable it**.
3. Under the module's scope, **check the target apps** you wish to spoof (e.g. WeChat, DingTalk, XuexiTong, Baidu Maps).
4. **Force stop** target apps or reboot your phone to apply the hooks.

### 3. Usage Scenarios

#### Fixed-Point Simulation & Environment Locking
1. Launch LocationSpoofer, pick a target location on the map.
2. In the bottom drawer, enable spoofing toggles: **Mock Wi-Fi**, **Mock Cell Tower**, **Mock Bluetooth**, **Mock Steps**, and **Enable Jitter**.
3. Tap "Start Simulation" to take over system GPS feeds.
4. To lock to a specific physical radio snapshot, tap the snapshot in "Manage Data".

#### Route Simulation
1. Switch to "Route Planning", mark waypoints on the road.
2. Select navigation mode (Loop / Round-trip / One-way) and speed class.
3. Turn on **"Use Real Route Planning"** (snaps to physical streets and inserts traffic light stops).
4. Tap "Start Simulation". You can toggle the floating joystick to fine-tune coordinates on-the-fly.

#### 🕵️‍♂️ Street Scanning & Custom Radio Fingerprints
1. Toggle **"Environment Map & Street Scan"** in Settings. Walk outdoors while the app silently records Wi-Fi, cell towers, and BLE signals.
2. Open "Manage Collected Data" to review, edit, or **manually customize** Wi-Fi (SSID, BSSID, RSSI) or cell records.
3. Export collected fingerprints as **JSON files** for backup or sharing.

#### 📍 Per-App Coordinate System Adaptation
* If an app experiences coordinate offsets:
  * Go to Settings -> Tap **"Configure App Coordinate System"**;
  * Add the target app's package name;
  * Set the coordinate system to `WGS-84`, `GCJ-02`, or `BD-09`. Hooks will translate coordinates automatically.

---

## 🛠️ Tech Stack

* **Language**: 100% Kotlin
* **Module layout**: 6 Gradle modules — `app` / `app-ui` / `service` / `xposed` / `core-data` / `core-geo` (see [System Architecture](#system-architecture))
* **UI**: Jetpack Compose & Material Design 3, layered with the third-party [Miuix](https://github.com/miuix-kmp/miuix) library (`top.yukonga.miuix.kmp`) for its backdrop-blur primitives, on top of which a custom `ui/liquid` package (adapted from the open-source AndroidLiquidGlass / ILoveWork projects, Apache-2.0) implements the Liquid Glass floating bottom bar, lens/vibrancy effects, and damped-drag interactions
* **Dependency Injection**: Koin, split per module into `coreDataModule` / `serviceModule` / `viewModelModule`, aggregated by `appModules` in the `app` module
* **Local Storage**: Room Database (SQLite) + Spatial Indexing (in `core-data`)
* **Networking & Serialization**: OkHttp 3 + Kotlinx Serialization
* **Map SDKs**: AMap 3DMap SDK / BaiduMap SDK / Google Maps & Places SDK
* **Xposed Hooking**: LSPosed API 101+ / libxposed (Service mode); hook implementations live under `xposed`'s `hooks/` and `hooks/network/` packages

---

## ⚠️ Disclaimer

This program is intended **solely for educational, academic, and developer testing purposes** (such as debugging coordinate-dependent apps).
Do not use this tool for any illegal activities or violations of third-party agreements (including fake attendance checks, exam cheating, commercial fraud, etc.).
The author is not responsible for any banned accounts, data losses, legal issues, or other direct/indirect damages arising from the use of this software.

---

## 📜 License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

```
Copyright (C) 2026 SuseOAA
```
