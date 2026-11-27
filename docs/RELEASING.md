# Releasing Herald

A release is one Git tag `v<version>` on `main` with a GitHub release that carries the signed APK
and its checksum. HACS installs the Home Assistant integration from the same tag, so one release
ships both halves. The steps below are the maintainers' runbook; run them in order.

### 1. Prepare the version

Set the same version in every place that names it, in one commit on `main`:

| File | Field |
|---|---|
| `app/AndroidManifest.xml` | `android:versionName` (and raise `android:versionCode`) |
| `custom_components/herald/manifest.json` | `version` |
| `CHANGELOG.md` | a `## <version>` section |
| `docs/SETUP.md` | a `<version>` support matrix with every row `planned` |

`hacs.json` changes only when the minimum Home Assistant version changes. The tag name comes
from `versionName`, so `tools/release.sh` refuses a manifest version that does not match.

### 2. Build the candidate

On a clean checkout of that commit:

```sh
tools/release.sh build
```

This runs `tools/build.sh` (JVM tests, packaging checks, signing) and writes
`out/herald-<version>.apk`, `out/herald-<version>.apk.sha256` and
`out/herald-<version>.release.json` (commit, version, APK and certificate SHA-256). Keep the
three files together and never rebuild the same version: every later step checks the APK
against that manifest. The signing key lives outside the repository in `HERALD_KEYS` (default
`~/.config/herald`); back it up privately. An APK signed with another key cannot update an
installed Herald, so losing the key means every box uninstalls and reinstalls.

### 3. Check the candidate

1. Offline Home Assistant suite: `tests/ha/run.sh`.
2. Android TV emulator: `tools/emulator.sh` (UI checks and the unattended runner).
3. A real box: install the candidate (docs/SETUP.md), pair a test client, and run your device
   checklist (cards, looks, chips, spoken cards on the TV and the box speaker, video, pairing,
   remote keys). Remove the test client and any test categories, pictures and clips afterwards.
4. Record each result in the version's support matrix in `docs/SETUP.md`: passed, partial
   (say which checks did not run) or skipped. Commit that before publishing.

A check that cannot run is recorded as skipped, never as passed.

### 4. Publish

```sh
tools/release.sh publish
tools/release.sh verify
```

`publish` checks the APK and certificate against the manifest, pushes the tag to the recorded
commit and creates the GitHub release with the APK and checksum attached. `verify` downloads the
asset again and checks its hash, its signing certificate and that the remote tag points at the
recorded commit. Both need an authenticated GitHub CLI.

### 5. Roll out

- **Boxes:** install the released APK over the old one (same signing key, `adb install -r`; see
  docs/SETUP.md). A version that changes the settings schema says so in CHANGELOG.md and needs a
  clean install instead.
- **Home Assistant:** HACS shows the new release as an update of the Herald integration
  (it lists the five latest releases and reads the version from the release tag). Download it,
  then restart Home Assistant. Boxes and integration must run the same version.

### Keeping the repository public-safe

This repository is public. Release notes, the support matrix and commits describe behavior and
results, never a particular household: no addresses, host names, people, local paths, tokens,
pairing codes or signing material. Device-specific notes belong in the private repository that
deploys Herald.
