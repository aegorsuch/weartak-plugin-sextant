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
- [ ] **Back button:** every screen except the address list shows a small back
  button at the top, and it returns to the previous screen.
- [ ] **Drop points:** drop points on at least two floors and rooms; each point
  saves a Wi-Fi fingerprint, floor (barometer) and name. **Office** and **Parking**
  are available as room presets.
- [ ] **Floor labels:** floors show as two digits without `L` (e.g. `06`), and
  rooms with the same name show their floor at the end (e.g. `Stairs 06`, `Stairs 07`).
- [ ] **Rescan room:** open a room (including one with 0 scans), tap **Rescan room**,
  stand still for the capture; the scan count updates and name, floor and map pin are unchanged.
- [ ] **Icons:** stats show as icons (Wi-Fi, floor, GPS, barometer, steps); record/stop,
  cancel, skip and set-here are icon buttons; no leftover helper text duplicates an icon.
- [ ] **Icon chips:** Mark room is `+ room`, Add address is `+ home`, Locate, Rescan,
  Correct position and Delete are icon-only (TalkBack still reads their names).
- [ ] **No 0-scan rooms:** with recording **stopped**, mark a room; it still captures
  scans. If no scan arrives in 15 s, the ring turns blue and waits up to 30 s more.
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