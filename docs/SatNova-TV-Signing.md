# SatNova TV Home — permanent APK signing (v0.5.14 onward)

This is the documented **new** signing identity for TV package
\`com.satnova.tvhome.tvbridge\`. It replaces temporary GitHub Actions debug
signatures **only for future fresh installations**; v0.5.12 and v0.5.13 debug
builds are signed with unrelated keys and CANNOT be updated in-place.

- Key algorithm: RSA 4096 / SHA256withRSA, Java JKS (created 2026-11-19).
- Alias: \`satnova-tv-release\`.
- Pinned signer certificate SHA-256:
  \`4c4373d5917ec908c6f12310531dee83ac4bf613680e59aef59e0935ddcf3118\`.
- The original private key and password **must never enter the repository**.
- The original JKS and its password must be backed up by the owner in separate,
  secure locations. Losing the JKS or password prevents compatible updates.

## Owner setup (GitHub Actions)

1. Open <https://github.com/anzaar/SatNova/settings/secrets/actions>.
2. Create two **repository secrets** using the private credentials delivered to
   the owner, never through a pull request, commit, issue, or public message:
   - \`SATNOVA_KEYSTORE_B64\`: the base64-encoded, password-protected JKS file.
   - \`SATNOVA_SIGNING_PASSWORD\`: the JKS + key password (same strong password).
3. Open <https://github.com/anzaar/SatNova/actions/workflows/satnova-signed-release.yml>.
4. Choose **Run workflow → main → Run workflow**.
5. The workflow checks out the **reviewed immutable v0.5.14 commit**
   \`367aa0b0169edae554f6be9093bb41cf12767ef3\`, builds
   \`:tvhome:assembleTvbridgeRelease\`, checks exact package, version code 20,
   verified APK signature, and the pinned certificate SHA-256. If the signing
   key differs, publication is blocked.
6. On success, download
   <https://github.com/anzaar/SatNova/releases/tag/v0.5.14-tvbridge-signed>.
   The artifact filename is
   \`SatNova-TV-Home-HDMI-Bridge-0.5.14-SIGNED.apk\`.

## Safety and migration

- The test artifact \`v0.5.14-TEST.apk\` is **debug signed**, not the same as the
  permanent-signature release. Do **not** distribute it as an updatable beta.
- For a device currently using v0.5.12/v0.5.13 debug signing: keep Google TV
  Launcher available and confirm that it responds to HOME, back up settings
  before uninstalling the old SatNova. Then install the signed beta using a
  normal Android APK installer and opt in to HOME/Accessibility as supported.
- Do not disable the original launcher until SatNova is observed to work after
  HOME, reboot, HDMI switching, and recovery testing.
- This process verifies the APK build and signature, **not** hardware remote
  compatibility or zero-ADB HOME setup on all Android TVs.
- Each future release must increase \`versionCode\` and reuse the same JKS.
- Never save permanent keys in GitHub artifacts, release assets, app assets,
  repository code, or unencrypted action logs.

## Workflow architecture

The manual release workflow is installed on the default \`main\` branch so
GitHub Actions exposes **Run workflow**. It checks out a pinned source commit
instead of a moving feature branch, to prevent unreviewed branch edits from
accessing the signing secret. A separate push-only smoke job checks Gradle's
release signing on an ephemeral test JKS and NEVER publishes that APK.
