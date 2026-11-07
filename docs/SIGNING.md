# Release signing with GitHub Secrets

Keystores and passwords are never committed (`.gitignore` blocks `*.jks`, `*.keystore`). The Gradle build reads
signing data only from environment variables, which the workflows fill from GitHub Secrets.

## 1. Create an upload keystore (once, on your computer)

```bash
keytool -genkeypair -v -keystore sonycam-release.jks -alias sonycam \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep this file and its passwords in a password manager. Losing it means you cannot update the app on Play.

## 2. Add repository secrets

GitHub → repository → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | output of `base64 -w0 sonycam-release.jks` (macOS: `base64 -i sonycam-release.jks`) |
| `SIGNING_KEYSTORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `sonycam` (the alias you chose) |
| `SIGNING_KEY_PASSWORD` | key password |

## 3. What the workflows do

- **android-ci.yml** (every push / PR): tests, lint, debug APK, release APK, AAB → uploaded as run artifacts
  (Actions → run → *Artifacts*). Release outputs are signed if the secrets exist, otherwise unsigned
  (the debug APK is always installable).
- **android-release.yml** (tag `vX.Y.Z` or manual): fails fast if any secret is missing, builds a signed APK and
  AAB, attaches them with SHA-256 sums to a GitHub Release. `versionCode = X*10000 + Y*100 + Z`.

```bash
git tag v0.1.0 && git push origin v0.1.0
```

## Local signed build (optional)

```bash
export SIGNING_KEYSTORE_PATH=/path/to/sonycam-release.jks
export SIGNING_KEYSTORE_PASSWORD=… SIGNING_KEY_ALIAS=sonycam SIGNING_KEY_PASSWORD=…
./gradlew :app:assembleRelease :app:bundleRelease
```

For Google Play, enrol in Play App Signing and use this keystore as the upload key.
