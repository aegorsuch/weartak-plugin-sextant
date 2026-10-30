# Sextant CI/CD and releases

Same model as WearTAK WearOS (`core/weartak-core/wearos-tak-civ`) and the WearTAK plugin
template. TAK.gov (`git.tak.gov/weartak-plugins/weartak-plugin-sextant`) is canonical; GitHub
(`aegorsuch/weartak-plugin-sextant`) is a mirror.

## Pipeline

| Trigger | Jobs |
| --- | --- |
| Merge request | `release_publisher_tests`, `unit_tests` (debug build, no production signing) |
| Push to protected `main` | tests, `main_release` (production-signed APK, 30-day artifact), then `publish_github_main` |
| Tag `vX.Y.Z[.B]` | tests, `official_release` (production-signed APK, kept forever) |

`unit_tests` runs `:app:testDebugUnitTest`, and a failure blocks the release. It covers the
plugin binder (`SextantPluginBinderTest`), floor/room labels, room presets and sort order
(`SextantLabelsTest`), and engine logic: address rename and migration, room rescan and
persistence, barometer floor estimate, and Wi-Fi locate (`SextantEngineTest`). Run it locally with
`./gradlew :app:testDebugUnitTest`.

Release builds are signed with the **WearTAK production key** and the job fails unless the
APK signer SHA-256 is `1579b600…348d`. Release WearTAK only binds plugins with the same
signature (signature-level `BIND_PLUGIN`), so debug builds only work with WearTAK `.dev`.

The WearTAK plugin SDK is not in this repository; `scripts/fetch-sdk.sh` pulls it from
`weartak-plugins/weartak-plugin-template` (`WEARTAK_SDK_REF`, default `master`) using
`SDK_READ_TOKEN` if set, otherwise `CI_JOB_TOKEN`.

## Versioning

Same scheme as WearTAK (WEARTAK-239), in `app/build.gradle.kts`:

- `baseVersionCode` / `baseVersionName` (currently `584` / `5.8.4`), aligned with the
  WearTAK 5.8 line. Bump them for each official `vX.Y.Z[.B]` tag.
- CI builds: versionCode = `base * 10000 + CI_PIPELINE_IID` (build fails at IID ≥ 10000),
  versionName = `<base>-p<IID>+<short sha>`, e.g. `5.8.4-p12+1a2b3c4d`. Every pipeline is
  therefore an in-place upgrade (MDMs such as Knox accept it).
- Local builds: versionCode = base, versionName = base.

## Rolling GitHub release

After **all** earlier jobs pass, `publish_github_main` publishes the verified `main_release`
APK to the GitHub mirror as **TAK.gov Forge CI/CD Pipeline Release (<version>)** on the
fixed tag `TAK.gov-Forge-CI-CD-Pipeline-Release`, asset `Sextant-<version>.apk`
(`<version>` is the versionName without `+sha`). It is a stable release (`prerelease=false`)
that does not take GitHub's "Latest" flag. The body records versionName/versionCode,
pipeline ID/IID and URL, source SHA and APK SHA-256.

Safety rules (identical to WearOS, see `ci/publish_github_release.py`):

- Runs only for protected `main` push pipelines of this exact project; the script
  re-checks the gates.
- Uploads only the signed APK, verified against `verified-apk.sha256` and Gradle metadata;
  it downloads the upload again and checks size + SHA-256 before publishing.
- Never pushes commits or creates/moves tags; the anchor tag must already exist.
- Older pipelines never replace newer ones; only older publisher-owned assets are deleted,
  after verification. Resource group `sextant-github-main` serializes writers.

### Administrator setup

1. Signing variables: `WEARTAK_RELEASE_KEYSTORE_B64`, `RELEASE_STORE_PASSWORD`,
   `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (protected, masked). Projects in the
   `weartak-plugins` group inherit them; elsewhere they need separate approval.
2. Protect `main` on git.tak.gov.
3. Create tag `TAK.gov-Forge-CI-CD-Pipeline-Release` on a commit already in the GitHub mirror.
4. GitHub token able to write releases on `aegorsuch/weartak-plugin-sextant`: a classic
   token with only the `public_repo` scope (the mirror is public), or a fine-grained token
   scoped to this repository with **Contents: read and write**. Add it as GitLab variable
   `SEXTANT_GITHUB_TOKEN` (masked, protected, hidden, no expansion).
5. If `CI_JOB_TOKEN` cannot read the template, add `SDK_READ_TOKEN` (read_repository).

## Release testing

After a verified publish, the job sends `repository_dispatch` `sextant-release-published`.
`.github/workflows/release-testing.yml` (on the GitHub default branch) re-reads the release
and opens one issue per pipeline, `Release testing: <release title>`, with the APK link and
`ci/release-testing/checklist.md`, assigned to the repository variable `RELEASE_TESTER`
(needs Write access). Existing issues, including closed ones, are never recreated.
Edit the checklist when features change.

## Local validation

```text
python -m unittest discover -s ci/tests -v
node --test ci/release-testing/create_release_test_issue.test.cjs
```