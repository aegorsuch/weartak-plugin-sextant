# Sextant (WearTAK plugin)

## Purpose and Capabilities

Wi-Fi mapping and triangulation for WearTAK on Wear OS. Survey a building by dropping points
(Wi-Fi fingerprint + barometer floor + steps), then **Locate** to see which point you are nearest.
Points are local only and are never sent as CoT.

Built from the [WearTAK plugin template](https://git.tak.gov/weartak-plugins/weartak-plugin-template).

## Status

Deployment readiness and supported-device certification are not documented.

### Releases

Pushes to `main` on git.tak.gov build a WearTAK production-signed APK and publish it to the
GitHub mirror as **TAK.gov Forge CI/CD Pipeline Release (<version>)**; `vX.Y.Z[.B]` tags
build official releases. Versioning and release rules match WearTAK; see
[ci/README.md](ci/README.md). Release Sextant works with release WearTAK; debug builds
work with WearTAK `.dev`.

## Point of Contacts

Project contacts are not documented in this repository.

## Ports Required

Required network ports are not documented. The Android manifest requests Internet and
network-state access; this is not a verified port inventory. Survey points remain local
and are never sent as CoT.

## Equipment Required

A Wear OS watch with Wi-Fi and WearTAK. The application requires Android API 30 or later
and declares watch hardware as required. Barometer floor estimates and step recording
use pressure and step-counter sensors when available.

## Equipment Supported

Specific tested watch models are not documented. Release and debug WearTAK compatibility
is described under [Releases](#releases).

## Compilation

### WearTAK plugin SDK

The WearTAK plugin SDK is not included in this repository. It is distributed to TAK.gov users through the
plugin template. With git.tak.gov access, fetch it into `app/libs/` before building:

```powershell
./scripts/fetch-sdk.ps1
```

```bash
./scripts/fetch-sdk.sh
```

Both scripts sparse-clone `weartak-plugins/weartak-plugin-template` and copy
`app/libs/weartak-plugin-sdk-{debug,release}.aar`. Pass a ref (branch or tag) to pin a version.

### Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds use the standard Android debug key so WearTAK debug builds can bind the plugin.

## Developer Notes

### Faster Wi-Fi scans (optional)

Android limits apps to about 4 Wi-Fi scans per 2 minutes. Grant this once and Sextant turns the
limit off only while recording, restoring the original setting when recording stops:

```bash
adb shell pm grant com.weartak.plugin.sextant android.permission.WRITE_SECURE_SETTINGS
```

### Data

Survey data and walk logs are written to
`/sdcard/Android/data/com.weartak.plugin.sextant/files/sextant/`
(`sextant.json` plus daily `walk-YYYYMMDD.jsonl`).

## License

The repository owner's original code is licensed under the
[GNU General Public License, version 3](LICENSE), or (at your option) any later
version (**GPL-3.0-or-later**).

This grant covers only the repository owner's original code, not third-party
components or code owned by others. Existing copyright, attribution, and rights
notices remain in effect. Gradle wrapper scripts (`gradlew`, `gradlew.bat`) and
`gradle/wrapper/gradle-wrapper.jar` retain their Apache License 2.0 terms and
bundled notices. The separately distributed WearTAK plugin SDK, third-party
dependencies, and external map data, tiles, and fonts remain subject to their own
licenses and terms; this declaration does not relicense them.
