# AltaPlay Receiver

Standalone Android TV application for the Echolink Forever Zen 4 receiver.

## Isolation
- Application ID: `com.altaplay.receiver`, separate from AltaPlay Launcher and SatNova.
- Separate Android app data and preferences.
- This module does not replace HOME, change HDMI input, or modify the stable SatNova release.
- Development branch: `feature/altaplay-receiver`.
- Reference transport implementation: SatNova tag `v0.5.12-tv-hdmi-bridge`.

## First alpha milestone
- Standalone Android TV launch activity.
- Reuses the authenticated G-MScreen discovery/session and channel-tuning implementation from the 0.5.12 source baseline.
- Displays only channels actually returned by the connected receiver.
- Allows a channel selection command to be sent to the receiver.
- Does not play encrypted channels or claim a tune command is successful before receiver confirmation.

## Next implementation stages
1. Move the G-MScreen session into a foreground connected-device service so the connection survives activity changes.
2. Add the full TV remote, favourites, channel search, and grouped channel families.
3. Add receiver-confirmed channel metadata and picon matching; never invent channel entries.
4. Integrate the supplied AltaPlay artwork as launcher icon, banner, and in-app header.
5. Build and test on the user's Zen 4 before publishing any APK.

## Branding
The official AltaPlay logo was supplied in this conversation. The original artwork must be copied into app resources without redrawing or substituting a generated mark. This first code commit does not yet include the binary artwork.


## CI validation
The dedicated GitHub Actions workflow builds this module on every change under `receiver/`. The APK is considered usable only after a successful CI build and a real Zen 4 connection test.
