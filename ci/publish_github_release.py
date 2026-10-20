"""Publish only the verified main_release APK; no code/tag synchronization."""

import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


REPO = "aegorsuch/weartak-plugin-sextant"
# Git tags cannot contain spaces or "/", so the tag is the title in tag-safe form.
TAG = "TAK.gov-Forge-CI-CD-Pipeline-Release"
TITLE = "TAK.gov Forge CI/CD Pipeline Release"
MARKER = "sextant-rolling-v1"
ASSET_PATTERN = re.compile(r"Sextant-[0-9A-Za-z._-]*-p([1-9][0-9]*)\.apk")
LEGACY_ASSET_PATTERN = re.compile(r"sextant-development-p([1-9][0-9]*)-([0-9a-f]{64})\.apk")
LABEL_PREFIX = "sextant-rolling-v1:"
# Handled by .github/workflows/release-testing.yml on the GitHub default branch.
DISPATCH_EVENT = "sextant-release-published"
OUTPUT = Path("app/build/outputs/apk/release")


class PublishError(Exception):
    pass


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise PublishError("Refusing non-HTTPS asset redirect.")
        redirect = super().redirect_request(req, fp, code, msg, headers, newurl)
        # Asset downloads can redirect to signed storage URLs. Never forward secrets.
        if redirect is not None:
            redirect.remove_header("Authorization")
        return redirect


class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(SafeRedirect())

    def request(self, method, path, data=None, *, binary=False, missing_ok=False):
        host = "https://uploads.github.com" if binary else "https://api.github.com"
        url = f"{host}/repos/{REPO}" + (f"/{path}" if path else "")
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "Sextant-GitLab-release",
        }
        if data is not None:
            if binary:
                headers["Content-Type"] = "application/vnd.android.package-archive"
            else:
                headers["Content-Type"] = "application/json"
                data = json.dumps(data).encode()
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=120) as response:
                content = response.read()
                return json.loads(content) if content else None
        except urllib.error.HTTPError as error:
            if error.code == 404 and missing_ok:
                return None
            # Do not print request headers, tokens, signed URLs, or raw API responses.
            raise PublishError(
                f"GitHub {method} {path.split('?')[0]} returned HTTP {error.code}. "
                "Check the protected SEXTANT_GITHUB_TOKEN (repository Contents: write), "
                "repository access, rolling tag, and API availability."
            ) from None
        except urllib.error.URLError:
            raise PublishError(
                "Cannot reach the GitHub API. Check runner DNS, HTTPS egress, TLS trust, "
                "and GitHub availability; retry only after connectivity is restored."
            ) from None

    def download_digest(self, asset_id):
        request = urllib.request.Request(
            f"https://api.github.com/repos/{REPO}/releases/assets/{asset_id}",
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/octet-stream",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "Sextant-GitLab-release",
            },
        )
        try:
            with self.opener.open(request, timeout=120) as response:
                digest = hashlib.sha256()
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    digest.update(chunk)
                return size, digest.hexdigest()
        except urllib.error.HTTPError as error:
            raise PublishError(f"Asset verification download failed: HTTP {error.code}.") from None
        except urllib.error.URLError:
            raise PublishError(
                "Cannot download uploaded APK for verification. Check HTTPS access to "
                "GitHub asset storage endpoints; previous assets will not be deleted."
            ) from None

    def assets(self, release_id):
        result = []
        page = 1
        while True:
            batch = self.request("GET", f"releases/{release_id}/assets?per_page=100&page={page}")
            result.extend(batch)
            if len(batch) < 100:
                return result
            page += 1


def configuration(env):
    expected = {
        "CI_SERVER_HOST": "git.tak.gov",
        "CI_PROJECT_PATH": "weartak-plugins/weartak-plugin-sextant",
        "CI_COMMIT_BRANCH": "main",
        "CI_COMMIT_REF_PROTECTED": "true",
        "CI_PIPELINE_SOURCE": "push",
    }
    for name, value in expected.items():
        if env.get(name) != value:
            raise PublishError(f"Publishing requires {name}={value}.")
    required = ("SEXTANT_GITHUB_TOKEN", "CI_PROJECT_ID", "CI_PIPELINE_IID",
                "CI_PIPELINE_ID", "CI_PIPELINE_URL", "CI_COMMIT_SHA")
    for name in required:
        if not env.get(name):
            raise PublishError(
                f"Missing {name}. Configure SEXTANT_GITHUB_TOKEN as a masked, protected "
                "GitLab CI variable with access only to the destination repository."
            )
    for name in ("CI_PROJECT_ID", "CI_PIPELINE_IID", "CI_PIPELINE_ID"):
        if not re.fullmatch(r"[1-9][0-9]*", env[name]):
            raise PublishError(f"Invalid {name}.")
    if not re.fullmatch(r"[0-9a-f]{40}", env["CI_COMMIT_SHA"]):
        raise PublishError("Invalid source commit SHA.")
    expected_url = (
        f"https://git.tak.gov/weartak-plugins/weartak-plugin-sextant/-/pipelines/{env['CI_PIPELINE_ID']}"
    )
    if env["CI_PIPELINE_URL"] != expected_url:
        raise PublishError("Pipeline URL does not match the canonical source project.")
    return {
        "project_id": env["CI_PROJECT_ID"],
        "pipeline_iid": int(env["CI_PIPELINE_IID"]),
        "pipeline_id": env["CI_PIPELINE_ID"],
        "pipeline_url": env["CI_PIPELINE_URL"],
        "source_sha": env["CI_COMMIT_SHA"],
    }


def artifact(root):
    directory = root / OUTPUT
    apks = list(directory.glob("*.apk"))
    if len(apks) != 1 or apks[0].is_symlink() or not apks[0].is_file():
        raise PublishError("Expected exactly one signed release APK from main_release.")
    apk = apks[0]
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    checksum = (directory / "verified-apk.sha256").read_text().strip()
    if checksum != f"{digest}  {apk.relative_to(root).as_posix()}":
        raise PublishError("APK does not match the production-signature verification checksum.")
    metadata = json.loads((directory / "output-metadata.json").read_text())
    elements = metadata.get("elements", [])
    if (metadata.get("applicationId") != "com.weartak.plugin.sextant"
            or metadata.get("variantName") != "release" or len(elements) != 1
            or elements[0].get("outputFile") != apk.name):
        raise PublishError("Gradle metadata must describe the single Sextant release APK.")
    version = elements[0].get("versionName")
    code = elements[0].get("versionCode")
    if not isinstance(version, str) or not re.fullmatch(r"[0-9A-Za-z.+_-]+", version):
        raise PublishError("Invalid APK versionName.")
    if type(code) is not int or code <= 0:
        raise PublishError("Invalid APK versionCode.")
    return apk, digest, version, code


def state_from(release, project_id):
    matches = re.findall(rf"<!-- {MARKER} (.*?) -->", release.get("body") or "")
    if len(matches) != 1:
        raise PublishError("Rolling release is not automation-owned; refusing to modify it.")
    state = json.loads(matches[0])
    if (not isinstance(state, dict) or state.get("project_id") != project_id
            or type(state.get("pipeline_iid")) is not int
            or state["pipeline_iid"] <= 0):
        raise PublishError("Rolling release provenance is invalid or belongs to another project.")
    if release.get("tag_name") != TAG or release.get("prerelease"):
        raise PublishError("Unexpected rolling release tag/classification; refusing to modify it.")
    return state


def display_version(version, pipeline_iid):
    """User-facing build id, e.g. 5.8.0.3-p105 (drops +sha build metadata)."""
    short = version.split("+", 1)[0]
    suffix = f"-p{pipeline_iid}"
    return short if short.endswith(suffix) else short + suffix


def asset_pipeline(asset):
    """Pipeline IID of an asset this publisher owns, or None for anything else."""
    name = asset.get("name", "")
    match = ASSET_PATTERN.fullmatch(name)
    if match and asset.get("label") == name:
        return int(match[1])
    legacy = LEGACY_ASSET_PATTERN.fullmatch(name)
    if legacy and asset.get("label") == f"{LABEL_PREFIX}{legacy[2]}":
        return int(legacy[1])
    return None


def managed_asset(asset):
    return asset_pipeline(asset) is not None


def publish(api, state, apk, digest, version, code):
    repository = api.request("GET", "")
    if (repository.get("full_name") != REPO or
            repository.get("archived") is not False or
            repository.get("disabled") is not False):
        raise PublishError(
            "Destination must be the explicitly approved, active repository "
            f"{REPO}; archived, disabled or different repositories are not allowed."
        )
    # Require an administrator-provisioned fixed tag. Never create/move a ref.
    if not api.request("GET", f"git/ref/tags/{TAG}", missing_ok=True):
        raise PublishError(
            f"Pre-create the fixed {TAG} tag on a commit already in the GitHub mirror. "
            "The publisher never pushes source commits or creates/moves tags."
        )
    release = api.request("GET", f"releases/tags/{TAG}", missing_ok=True)
    if release:
        previous = state_from(release, state["project_id"])
        if previous["pipeline_iid"] > state["pipeline_iid"]:
            print("Skipping older pipeline: a newer pipeline already owns the rolling release.")
            return
        if (previous["pipeline_iid"] == state["pipeline_iid"]
                and (previous.get("source_sha") != state["source_sha"]
                     or previous.get("sha256") != digest)):
            raise PublishError("Same pipeline has different source/artifact; refusing replacement.")

    state = dict(state, sha256=digest)
    short = display_version(version, state["pipeline_iid"])
    title = f"{TITLE} ({short})"
    body = (
        "Automatically published stable Sextant main-channel build.\n\n"
        f"- APK version: **{version}** (versionCode **{code}**)\n"
        f"- Build: GitLab pipeline **{state['pipeline_id']}** "
        f"(project pipeline **{state['pipeline_iid']}**)\n"
        f"- Pipeline: {state['pipeline_url']}\n"
        f"- Source commit: `{state['source_sha']}` on TAK.gov `main`\n"
        f"- SHA256: `{digest}`\n\n"
        "Install the APK above. GitHub's \"Source code\" archives come from the fixed release "
        "anchor tag, not from this build; the source commit above is authoritative and may "
        "not yet be mirrored to GitHub.\n\n"
        f"<!-- {MARKER} {json.dumps(state, sort_keys=True)} -->"
    )
    if not release:
        release = api.request("POST", "releases", {
            "tag_name": TAG, "name": title, "body": body,
            "draft": True, "prerelease": False, "make_latest": "false",
        })
    release_id = release["id"]
    assets = api.assets(release_id)
    name = f"Sextant-{short}.apk"
    # GitHub shows the label instead of the file name, so keep it human-readable.
    label = name
    existing = [item for item in assets if item["name"] == name]
    if existing:
        uploaded = existing[0]
        if not managed_asset(uploaded):
            raise PublishError("Asset name collision with an unrelated asset; refusing replacement.")
    else:
        query = urllib.parse.urlencode({"name": name, "label": label})
        uploaded = api.request(
            "POST", f"releases/{release_id}/assets?{query}", apk.read_bytes(), binary=True
        )
    size = apk.stat().st_size
    if (uploaded.get("state") != "uploaded" or uploaded.get("size") != size
            or api.download_digest(uploaded["id"]) != (size, digest)):
        raise PublishError(
            "Uploaded APK failed size/SHA256 download verification. Previous assets retained; "
            "inspect only this pipeline's new asset before retrying."
        )
    api.request("PATCH", f"releases/{release_id}", {
        "name": title, "body": body, "draft": False,
        "prerelease": False, "make_latest": "false",
    })
    confirmed = api.request("GET", f"releases/{release_id}")
    confirmed_state = state_from(confirmed, state["project_id"])
    if (confirmed.get("draft") or confirmed.get("name") != title or confirmed_state != state
            or not any(a["id"] == uploaded["id"] and a.get("state") == "uploaded"
                       and a.get("size") == size for a in api.assets(release_id))):
        raise PublishError("Release update could not be verified; previous assets retained.")
    # Delete only older assets explicitly owned by this publisher, after verification.
    for old in assets:
        owner = asset_pipeline(old)
        if owner is not None and owner < state["pipeline_iid"]:
            api.request("DELETE", f"releases/assets/{old['id']}")
    print(f"Published {title}: version {version} ({code}), SHA256 {digest}")
    # Only a verified build reaches testers. The workflow re-reads the release itself and
    # deduplicates per pipeline, so retrying a failed notification is safe.
    try:
        api.request("POST", "dispatches", {
            "event_type": DISPATCH_EVENT,
            "client_payload": {"pipeline_id": state["pipeline_id"], "release_id": release_id},
        })
    except PublishError:
        raise PublishError(
            "The release is published, but the release-testing notification failed. "
            "Retry this job; the release and tester checklist are not duplicated."
        ) from None


def main():
    try:
        state = configuration(os.environ)
        apk, digest, version, code = artifact(Path.cwd())
        publish(GitHub(os.environ["SEXTANT_GITHUB_TOKEN"]), state, apk, digest, version, code)
    except (PublishError, OSError, ValueError, KeyError, TypeError, urllib.error.URLError) as error:
        # urllib transport errors can contain signed URLs; do not echo their details.
        message = str(error) if isinstance(error, PublishError) else type(error).__name__
        print(
            f"ERROR: {message} Publication incomplete; existing usable assets are retained "
            "until verified replacement. See ci/README.md for setup/recovery.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
