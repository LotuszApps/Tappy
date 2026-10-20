# Releasing Tappy

## One-time setup: signing key

Every update must be signed with the same key. **If you lose it, you can never update the
GitHub release of the app again**: users would have to uninstall and reinstall. Keep a backup
somewhere safe (password manager, encrypted USB stick), and never commit it.

1. Create the key (needs a JDK; any computer is fine):

   ```sh
   keytool -genkeypair -v -keystore tappy-release.jks -alias tappy \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Turn it into text for GitHub:

   ```sh
   base64 -w0 tappy-release.jks > tappy-release.jks.b64     # macOS: base64 -i tappy-release.jks
   ```

3. In GitHub: **Settings → Secrets and variables → Actions → New repository secret**, add:

   | Secret | Value |
   | --- | --- |
   | `TAPPY_KEYSTORE_BASE64` | contents of `tappy-release.jks.b64` |
   | `TAPPY_KEYSTORE_PASSWORD` | the keystore password you chose |
   | `TAPPY_KEY_ALIAS` | `tappy` |
   | `TAPPY_KEY_PASSWORD` | the key password (same as keystore password if you didn't set one) |

   Then delete the `.b64` file.

## Each release

1. In `app/build.gradle.kts`, bump `versionCode` by 1 and set `versionName` (e.g. `1.1.0`).
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` with what changed.
3. Commit, then tag and push:

   ```sh
   git tag v1.1.0
   git push origin v1.1.0
   ```

4. The **Release** workflow builds a signed APK and publishes it on the GitHub Releases page,
   with the changelog as release notes. It fails if the tag doesn't match `versionName`.

F-Droid picks up new tags by itself (`UpdateCheckMode: Tags`) once Tappy is in F-Droid; no
extra steps needed.

## Getting into F-Droid (first time only)

Requirements: the GitHub repo is **public**, and a `v1.0.0` tag exists.

1. Create a GitLab account and fork https://gitlab.com/fdroid/fdroiddata.
2. Copy `docs/fdroid-metadata.yml` to `metadata/io.github.lotusz.tappy.yml` in your fork.
3. Open a merge request. F-Droid's bot test-builds it; maintainers review it. Answer any
   questions in the merge request. Expect a few weeks.

F-Droid builds Tappy from source and signs it with **its own key**, so the F-Droid version and
the GitHub version can't update each other. Users pick one and stick with it.

## Screenshots

Add PNG screenshots to `fastlane/metadata/android/en-US/images/phoneScreenshots/`
(e.g. `1.png`, `2.png`). F-Droid shows them on the app page.
