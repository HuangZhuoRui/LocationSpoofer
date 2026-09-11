[简体中文](CONTRIBUTING.md) | [English](CONTRIBUTING_EN.md)

---

# Contributing to LocationSpoofer

First off, thank you for considering contributing to LocationSpoofer! 🎉

Contributions from the community help make LocationSpoofer more stable, reliable, and effective.

---

## 📑 Table of Contents

1. [Code of Conduct](#code-of-conduct)
2. [How Can I Contribute?](#how-can-i-contribute)
   * [Reporting Bugs](#reporting-bugs)
   * [Suggesting Enhancements](#suggesting-enhancements)
   * [Pull Requests](#pull-requests)
3. [Development Setup](#development-setup)
4. [Architecture & Guidelines](#architecture--guidelines)
   * [Coding Conventions](#coding-conventions)
5. [Commit Message Conventions](#commit-message-conventions)

---

## Code of Conduct

This project and everyone participating in it is governed by the [LocationSpoofer Code of Conduct](CODE_OF_CONDUCT_EN.md). By participating, you are expected to uphold this code. Please report unacceptable behavior to the project maintainers.

---

## How Can I Contribute?

### Reporting Bugs

Before creating a bug report, please:
* Check the [existing Issues](https://github.com/your-username/LocationSpoofer/issues) to ensure the problem hasn't already been reported.
* Ensure you are running a supported environment (**Android 8.0+**, **KernelSU / APatch / Magisk**, **LSPosed API 101+**).

When filing a bug report via the **Bug Report Template**, please provide:
* **Device & Environment**: Android OS version, ROM/device model, Root solution (KernelSU/APatch/Magisk version), LSPosed/libxposed version.
* **Target Application**: App name and version code/name where the issue occurs.
* **Steps to Reproduce**: Detailed step-by-step description.
* **Logs & Behavior**: Logcat snippets or LSPosed module logs (especially crash traces or unexpected fallback coordinates).

### Suggesting Enhancements

Feature requests are welcome! When opening an issue via the **Feature Request Template**, please explain:
* The problem or limitation you are experiencing.
* The proposed solution or behavior.
* Potential edge cases or considerations.

### Pull Requests

1. **Fork the repository** and create your branch from `main`:
   ```bash
   git checkout -b feat/your-feature-name
   ```
2. **Make your changes** following our code style and architecture.
3. **Verify the build**:
   ```bash
   ./gradlew assembleDebug
   ```
4. **Commit your changes** using clear commit messages (see [Commit Message Conventions](#commit-message-conventions)).
5. **Push to your fork** and submit a Pull Request targeting the `main` branch.
6. Complete the PR template checklist and describe your changes clearly.

---

## Development Setup

### Prerequisites
* **Android Studio**: Android Studio Hedgehog / Iguana / Jellyfish or newer.
* **JDK**: OpenJDK 17 or OpenJDK 21.
* **Android SDK**: compileSdk `37` (minSdk `26`); Build Tools version is resolved automatically by AGP, no need to pin it manually.
* **Testing Device**: A rooted device with **KernelSU / APatch / Magisk** and **LSPosed (API 101+)** installed.

### Building
```bash
# Clone the repository
git clone https://github.com/your-username/LocationSpoofer.git

# Open directory
cd LocationSpoofer

# Build debug APK
./gradlew assembleDebug

# Install directly to connected device
./gradlew installDebug
```

---

## Architecture & Guidelines

LocationSpoofer is structured using **MVVM + Clean Architecture**, split into 6 Gradle modules by responsibility:

| Module | Type | Responsibility |
|---|---|---|
| `app` | Android App | The host shell: `Application` / `MainActivity`, signing & packaging, aggregates every module's Koin DI; only bundles the `xposed` module's artifact for LSPosed to scan — it never calls that code directly |
| `app-ui` | Android Library | All Compose UI (screens/dialogs/the `ui/liquid` kit) and the ViewModel layer |
| `service` | Android Library | The foreground service, floating joystick service, boot-completed receiver, etc. |
| `xposed` | Android Library | The LSPosed/Xposed injection module itself, with hooks split across `hooks/` and `hooks/network/` |
| `core-data` | Android Library | The data/domain layer shared by `app`/`app-ui`/`service`: Room database, repositories, and core utilities such as `ConfigManager`/`RootManager`/`EnvironmentScanner` |
| `core-geo` | Pure Kotlin/JVM | The only module with no Android dependency; coordinate-system conversion |

Before adding new code, decide which module it belongs in: pure business logic/persistence goes in `core-data`; anything Compose-UI-only goes in `app-ui`; hook implementations only go in `xposed` (and `xposed` may only depend on `core-geo` — never depend back on `app-ui`/`core-data`, or you'll drag Room/Compose and other heavyweight dependencies into the target app's process).

* **Language**: 100% Kotlin with Coroutines and StateFlow.
* **UI**: Jetpack Compose, Material Design 3, layered with the third-party [Miuix](https://github.com/miuix-kmp/miuix) library (`top.yukonga.miuix.kmp`) for its blur/frosted-glass primitives; `app-ui/ui/liquid` builds the "Liquid Glass" floating bottom bar, lens, and damped-drag effects on top of it. Prefer Miuix's existing widgets for new basic components instead of reinventing them.
* **Dependency Injection**: Koin, split per module into `coreDataModule` (`core-data`), `serviceModule` (`service`), and `viewModelModule` (`app-ui`), aggregated by `appModules` (a `List<Module>`) in the `app` module and started via `startKoin` in `LocationApp.onCreate()`. Add new injectable types to the matching module's `di/XxxModule.kt` — don't bypass DI with a manual `new`.
* **Database**: Room Database (in `core-data`) with spatial index optimizations.
* **Xposed Hook Layer**:
  * Lives in the `xposed` module's `com.suseoaa.locationspoofer.xposed` package, entry class `LocationHooker`; concrete hooks are split by category across `hooks/` (location/GNSS/map SDKs/steps/anti-detection) and `hooks/network/` (Wi-Fi/cell/Bluetooth/connectivity) — file new hooks into the matching existing subpackage rather than piling them into the `xposed` root package or `LocationHooker.kt`.
  * Adheres to **LSPosed API 101+ / libxposed (Service mode)** specifications.
  * Zero-IO on high-frequency hook threads: `LocationHooker`'s background daemon thread polls several config-file paths by caller UID (1000ms by default, backing off to 10s/60s on read failure) into an in-memory cache; hook methods only ever read that cache, no synchronous IO.
  * Cross-process config delivery avoids `ContentProvider` (which stalls the main thread under Android 11+ package-visibility rules); instead `core-data`'s `ConfigManager` uses root to write the JSON config to `/data/local/tmp/`, `/data/system/`, and the app's private directory at once, permissions tightened to `644`, with `RootManager` dynamically injecting a dedicated SELinux type (not the generic `shell_data_file`) — don't fall back to `777` or a generic SELinux type for convenience.
  * MultiDex safety: Dynamic ClassLoader hooking locked to the host package via `/proc/self/cmdline`.
  * Maintain clean stack traces and avoid leaving observable inspection points.

### Coding Conventions

* **ViewModel organization**: only `app-ui`'s `MainViewModel` — the single "god" ViewModel backing the whole main screen — is split across multiple files (`MainViewModel.kt` holds only fields/constructor; behavior lives entirely as `internal fun MainViewModel.xxx()` extension functions spread across `MainViewModelSpoofing.kt` / `MainViewModelDataIO.kt` / `MainViewModelLocation.kt` / `MainViewModelRoute.kt` / `MainViewModelSettings.kt`). This is specific to `MainViewModel`, **not** a project-wide convention — single-purpose ViewModels like `ManageDataViewModel` or `UpdateViewModel` should stay single-file with methods as regular class members; don't force the extension-function split onto them. When adding logic to `MainViewModel`, put it in whichever `MainViewModelXxx.kt` matches its concern (spoofing/import-export/location/route/settings) rather than in `MainViewModel.kt` itself.
* **Comment style**: comments in this codebase are overwhelmingly Chinese and overwhelmingly explain *why* something is written the way it is (a hidden constraint, a bug once hit, a check that looks redundant but isn't) rather than restating what the code does — e.g. why a specific SELinux attribute is used instead of enumerating variants one by one, why a check's ordering can't be swapped, why Gaussian noise is used instead of a deterministic sine wave. Before adding a comment, ask: if this line were deleted, would a future reader miss the underlying reason and risk breaking it again? If yes, write it; a comment that only restates what the code already says should not be added.
* **`app-ui/ui/` package layout**: `components/` (dialogs/widgets reused across screens), `components/map/` (per-map-engine adapters), `liquid/` (the custom Liquid Glass kit), `theme/` (colors/theming), `screen/` (individual screens); when a screen's own logic grows complex, open a subpackage under `screen/` for it (e.g. `screen/managedata/`, `screen/settings/`, `screen/tabs/`) and keep that screen's own sub-components, dialogs, and UI state inside it rather than flattening everything into the `screen/` root.
* **Static analysis**: the project currently has **no** ktlint/detekt or similar linting configured — style is enforced by manual review only, so check your changes against this document and the existing code style before submitting rather than waiting on CI to flag it.
* **Localization resources**: the default `res/values/strings.xml` under `app-ui` and `service` is actually **English**; `values-zh` holds the Chinese translation and `values-ar` the Arabic one (currently behind the other two — missing strings fall back to the English default automatically). When adding new UI copy, add the English original to `values/strings.xml` first, then the Chinese translation to `values-zh/strings.xml`. Updating only the Chinese translation and forgetting the default English resource is a common oversight — watch for it.

---

## Commit Message Conventions

We follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>(<scope>): <subject>
```

### Allowed Types:
* `feat`: A new feature
* `fix`: A bug fix
* `docs`: Documentation only changes
* `style`: Formatting, missing semi-colons, whitespace, etc. (no code change)
* `refactor`: A code change that neither fixes a bug nor adds a feature
* `perf`: A code change that improves performance
* `test`: Adding missing tests or correcting existing tests
* `chore`: Build process, dependencies, or auxiliary tool changes

### Examples:
```
feat(hook): add support for dynamic MultiDex location listener hooking
fix(coords): resolve coordinate shift on Baidu Map rendering layer
docs: update README with API 101+ specifications
```

---

Thank you for contributing to LocationSpoofer!
