# PhoneProof

PhoneProof is an Android library and test harness I'm building. It runs hardware diagnostics on a second-hand
phone, grades the result, and produces a signed PDF report that a buyer, seller or repair shop can hand over.

A used-phone listing can say "battery 90%, genuine, no repairs" and nobody can check it. PhoneProof measures what
the hardware reports (battery capacity, bootloader state, real RAM and storage, display refresh rate, charging power,
throttling, radios) and writes down the raw numbers behind every verdict, so a grade can be argued line by line.

It is still early. The diagnostics, grading, PDF and signing code works and is unit-tested, but there is no user
interface yet: the app module only shows the word "PhoneProof". Guided tests that need a person (touch, buttons,
camera, audio) are not written. See [What works](#what-works).

## What works

| Area | State |
|---|---|
| 13 automatic tests in `core-diagnostics` (identity, bootloader/root/ROM integrity, spec verification against a catalogue, battery health, charging power, storage speed, thermal throttling, display capability, sensor inventory, cellular, Wi-Fi/Bluetooth/NFC, GPS, device age) | Written. Run on physical phones during development through the instrumented tests; not re-run for this release |
| Test framework: runner, dependency ordering, permissions, timeouts | Written; ordering and result invariants have JVM unit tests |
| Grading engine with traceable deductions, confidence caps and per-measurement confidence | Written; JVM unit tests |
| PDF renderer (A4, pagination, charts, touch heatmap, photos) | Written; exercised only by the instrumented end-to-end test, which needs a device |
| Report signing (EC P-256 key in the Android Keystore) and a device fingerprint | Written; round-trip and tamper detection checked by the instrumented test, which needs a device |
| Device spec catalogue (`device_specs.json`, 47 models) | Partial. Only one row is marked `verified`; the rest are compiled from published specifications and the counterfeit check softens its findings for them |
| App UI, `:feature-wizard`, `:feature-shop` | Placeholders (empty modules) |
| Guided tests, audio loopback, camera analysis | Not started (only the `GuidedTest` / `InteractionHost` contract exists) |
| Price matrix and shop mode | Only the data models exist; no price engine, no UI |
| IMEI entry/check, hosted report verification, Play Integrity | Not started |

## Rules the code enforces

- A `Pass`, `Warn` or `Fail` result can't be constructed without at least one measurement, and a `Pass` can't
  carry a major or critical finding (`TestResult`).
- A test that throws, a denied permission and a timeout all become skipped results, so nothing turns into a pass
  by accident.
- Each measurement carries a confidence level. A figure the test itself contradicted (for example a battery figure
  that disagrees with the cycle count) is reported but costs nothing in the grade. A low-confidence one costs a
  quarter.
- Skipped and unsupported tests lower the report's confidence and cap the best possible grade. They never count as
  passes.

## Storage speed and the page cache

Apps can't drop Android's page cache, so a file the storage test has just written often reads back from RAM. The
test labels a read as an upper bound when it is over 1200 MB/s and more than twice the fsync'd write, and the
verdict comes from the writes. The bar used to be 2500 MB/s, until a budget phone read at 2242 MB/s against a
418 MB/s write and slipped under it.

## Build

You need a JDK 21 and the Android SDK (platform 36). Set `ANDROID_HOME`, or create a `local.properties` file with
`sdk.dir=/path/to/sdk`.

```bash
./gradlew test                   # JVM unit tests, no device needed
./gradlew :app:assembleDebug     # builds the (placeholder) app
```

`./build.sh <gradle args>` does the same with `JAVA_HOME` and `ANDROID_HOME` defaulted to common macOS locations if
you haven't set them.

The instrumented tests (`app/src/androidTest`) run the full suite, grade it, sign it, render a PDF and check the
signature on whatever phone is connected. They write a text dump and a PDF into the app's external files directory
on that phone.

```bash
./gradlew :app:connectedDebugAndroidTest
```

Toolchain: Gradle 8.14.3 (wrapper), Android Gradle Plugin 8.13.2, Kotlin 2.2.21, Jetpack Compose (BOM 2026.06.01),
`minSdk` 26, `compileSdk`/`targetSdk` 36. Exact versions are in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Layout

| Module | What it holds |
|---|---|
| `core-diagnostics` | Test framework, the 13 automatic tests, the device spec catalogue |
| `core-report` | Grading engine, PDF renderer, signing and device fingerprint |
| `app` | Manifest, placeholder activity, the instrumented tests |
| `feature-wizard`, `feature-shop` | Empty placeholders for the guided-test wizard and shop mode |

## Permissions, and why

The manifest declares more than the current code uses, because I wrote it ahead of the guided tests. Each permission
is requested at runtime only by the tests that need it.

| Permission | Used today by |
|---|---|
| `READ_PHONE_STATE` | Cellular test: SIM slot count and state, carrier name per slot. The app never reads your phone number or contacts |
| `ACCESS_FINE_LOCATION` | GPS test (satellite fix), and Android requires it to read cell bands and list Wi-Fi networks. Position is measured on the device and is not written into the report; only fix time, satellite counts and accuracy are |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | Wireless test: counts paired devices to show the radio works. Names are not recorded |
| `ACCESS_WIFI_STATE` | Wireless test: reads the number of visible networks |
| `NFC` | Wireless test: checks whether NFC is present and switched on |
| `ACCESS_COARSE_LOCATION`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CAMERA`, `RECORD_AUDIO`, `VIBRATE`, `USE_BIOMETRIC`, `POST_NOTIFICATIONS` | Declared for planned guided tests. No current code uses them (camera enumeration in the spec check needs no permission) |
| `INTERNET` | Declared, but **nothing in this repository makes a network call**. The optional verification URL in a report is only text printed in the PDF. Remove this permission if you do not plan to add hosted verification |

## What a report contains

A report holds the model and build information, the measured values and verdicts (including the carrier name for
each SIM slot and root/ROM evidence), a hardware fingerprint and the public half of the signing key. The
fingerprint is a SHA-256 of device attributes such as model, board, SoC, screen and rounded RAM and storage, mixed
with the report time. A report has no IMEI, serial number, advertising ID, phone number or location. Android's
default app backup is left on.

## Limits

- Android hides a lot from apps. Battery capacity, bootloader state and thermal data aren't available on every
  phone, and the tests then report "unsupported" or lower their confidence.
- Root detection is best effort, and a well-hidden root can pass. A clean result only means no evidence of root was
  found.
- The device age is an estimate from firmware dates and install times. A factory reset hides the strongest signal.
- The signature proves a report wasn't edited and was made on the phone in your hand. Whether the phone tells the
  truth about itself is what the measurements check.

## Tech

Kotlin, Android SDK, Jetpack Compose (placeholder UI only), kotlinx.serialization, Android Keystore, `PdfDocument`,
[RootBeer](https://github.com/scottyab/rootbeer) (root heuristics), CameraX (declared for the camera tests that are
not written yet). Tests use JUnit 4.

## Licence

MIT, see [LICENSE](LICENSE). `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are the unmodified
Gradle wrapper (Apache-2.0).
