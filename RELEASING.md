# Releasing

Releases are built by GitHub Actions (`.github/workflows/release.yml`) when a `v*` tag is pushed.
The installed app checks `releases/latest` once a day and offers the update.

## One-time setup: signing secrets

The release must be signed with the same key as the installed app, otherwise Android refuses
the update. In the repository: Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -i release.jks` (one line) |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

## Making a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add a `## <versionName> — <date>` section to `CHANGELOG.md`: it becomes the release notes.
3. Commit, then tag and push: `git tag v<versionName> && git push origin main v<versionName>`.

The workflow fails if the tag doesn't match `versionName`. Running it by hand
(Actions → Release → Run workflow) only builds the APK as a run artifact and publishes nothing.
