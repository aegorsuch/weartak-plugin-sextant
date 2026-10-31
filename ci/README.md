# Sextant CI/CD and releases

Same model as WearTAK WearOS (`core/weartak-core/wearos-tak-civ`) and the WearTAK plugin
template. TAK.gov (`git.tak.gov/weartak-plugins/weartak-plugin-sextant`) is canonical; GitHub
(`aegorsuch/weartak-plugin-sextant`) is a mirror.

## Pipeline

Gradle jobs exchange a per-job token using
`devsecops/gitlab-ci-common`'s `/template/artifactory_token_service.yml`.
The non-root launcher uses `.artifactory_service_create_token_no_depends_bash`.
Both test and release jobs run this setup; release signing setup follows it.
Non-Gradle jobs retain an empty global `before_script`.
No bundled repository init script is used with `FAIL_ON_PROJECT_REPOS`.

Gradle checks the approved TAK Artifactory mirror
(`https://artifacts.tak.gov/artifactory/maven-release`) first for plugins and dependencies
when credentials are available. Username precedence is `ARTIFACTORY_CONSUME_USER`,
`ARTIFACTORY_USER`, then `TAK_USERNAME`; password precedence independently follows
`ARTIFACTORY_CONSUME_PASSWORD`, `ARTIFACTORY_PASSWORD`, then `TAK_PASSWORD`.
Both must be nonblank or the mirror is omitted. Logs report only enabled/disabled,
never credential values. Public repositories remain available as fallbacks.
An unresolved-plugin error alone does not establish an upstream outage; inspect the
job trace before changing repository configuration or dependency versions.
Robolectric's independent downloader receives the same repository through test JVM
system properties. Never print tokens or credential values. Expected safe markers are
`Token exchange successful` and `takArtifactory for plugins: enabled`.
If token exchange requires an initial login, sign into `artifacts.tak.gov` once as the
pipeline user before retrying; do not weaken permissions or expose tokens.

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

### Launcher icon compatibility

The application manifest uses `@mipmap/sextant_launcher`: packaged RGBA PNGs at
mdpi (48x48), hdpi (72x72), xhdpi (96x96), xxhdpi (144x144), and xxxhdpi (192x192).
They rasterize the unchanged 48dp `drawable/sextant_launcher.xml` artwork, including
its pivot/scale transform, paths, colors, and rounded strokes. The vector remains
available for internal use. There is no adaptive/vector override of the launcher mipmap.
`SextantLauncherIconTest` checks the manifest icon and bitmap dimensions/densities.

This addresses a suspected Knox Manage vector-icon extraction incompatibility, not
a confirmed root cause. Acceptance still requires uploading a new, unmodified,
production-signed TAK.gov APK to Knox Manage and confirming the invalid-icon warning
is absent and Icon image selection works. Local resource checks do not prove Knox acceptance.

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
fixed tag `TAK.gov-Forge-CI-CD-Pipeline-Release`, asset
`WearTAK-Plugin-Sextant-<base-version>-p<pipeline-iid>.apk`
(e.g. `WearTAK-Plugin-Sextant-5.8.4-p42.apk`). Build metadata (`+sha`) is omitted,
and the pipeline suffix appears exactly once. A stamped version must match the publishing
pipeline. Android app label, package, versionName and versionCode remain unchanged.
It is a stable release (`prerelease=false`)
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
- Naming migration retains same-pipeline unprefixed APKs, preserving existing download
  links. Release testing prefers prefixed assets, with a legacy-name fallback.

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