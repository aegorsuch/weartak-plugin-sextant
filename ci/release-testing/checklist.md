## Test setup

Record the exact build before starting. The rolling release replaces its APK on
every pipeline, so copy the version and SHA-256 from the release details above.

| Field | Value |
| --- | --- |
| Sextant version (app info) / pipeline / SHA-256 | |
| WearTAK version (release, Settings > About) | |
| Watch model / Wear OS version | |
| Building / floors surveyed | |
| Tester / date | |

Install as an update (`adb install -r` or the normal update path). **Do not
uninstall or clear app data**: the update must keep surveyed addresses and points.
Release Sextant must be tested with **release WearTAK** (both production-signed).
Do not attach precise home/work locations, Wi-Fi names, or survey files to this issue.

## Core acceptance checklist

Check an item only after it passes. For unavailable hardware or unsupported
features, leave it unchecked and record **N/A** with the reason in the results.

- [ ] **Update in place:** the new version installs over the previous one. Saved
  addresses, points and floor names are still present.
- [ ] **WearTAK binds the plugin:** with release WearTAK installed, Sextant appears
  in WearTAK's plugin list and stays connected (no trust/bind failure).
- [ ] **Add address:** tapping **Add address** opens the system keyboard directly
  (no chooser); voice and typed names both save, and the new name shows immediately.
- [ ] **Record and scan limit:** start recording; Wi-Fi scans run about every 6 s
  while recording. After **Stop**, `wifi_scan_throttle_enabled` is restored (1).
- [ ] **Drop points:** drop points on at least two floors and rooms; each point
  saves a Wi-Fi fingerprint, floor (barometer) and name.
- [ ] **Map placement / correct position:** set a point's position on the map with
  the crosshair **Set here**, and use the correct-position flow when GPS is wrong.
- [ ] **Locate:** walk between surveyed points; **Locate** picks the nearest point
  and the right floor most of the time. Record misses with where you actually were.
- [ ] **Local only:** no CoT/markers are sent to the TAK network from Sextant.
- [ ] **Stop and battery:** stop recording; the foreground notification and scans
  stop. Over a 30-60 minute walk, record unusual heat, battery drain, or any crash.

## Results

**Overall:** PASS / FAIL / BLOCKED

**N/A items and reasons:**

**Failures:** for each failure, include steps, expected/actual behavior,
timestamp, and a screenshot or short recording. Attach sanitized logs where
available.

**Release blockers:** lost surveys after update, release WearTAK cannot bind the
plugin, scan limit left disabled after Stop, data sent off-device, or crashes
preventing use.