# Sextant (WearTAK plugin)

GNSS-denied indoor positioning for WearTAK on Wear OS. Survey a building by dropping points
(Wi-Fi fingerprint + barometer floor + steps), then **Locate** to see which point you are nearest.
Points are local only and are never sent as CoT.

Built from the [WearTAK plugin template](https://git.tak.gov/weartak-plugins/weartak-plugin-template).

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds use the standard Android debug key so WearTAK debug builds can bind the plugin.

### Faster Wi-Fi scans (optional)

Android limits apps to about 4 Wi-Fi scans per 2 minutes. Grant this once and Sextant turns the
limit off only while recording, restoring the original setting when recording stops:

```bash
adb shell pm grant com.weartak.plugin.sextant android.permission.WRITE_SECURE_SETTINGS
```

## Data

Survey data and walk logs are written to
`/sdcard/Android/data/com.weartak.plugin.sextant/files/sextant/`
(`sextant.json` plus daily `walk-YYYYMMDD.jsonl`).
