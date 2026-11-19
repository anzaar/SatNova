# AltaPlay Launcher · Zen add-on — protects unmodified Secreto v1.3.2

The project user's explicit requirement is to keep Secreto TV Launcher exactly
as installed — adhan, prayer times, weather behavior, favorite apps,
navigation, speed, wallpapers and system-specific HOME key behavior. Neither
Secreto's package nor its signing certificate is modified here.

This proof-of-concept is therefore a **separately signed add-on** installed
alongside official Secreto, not a repack of its proprietary APK.

## Behavior currently implemented (unverified on Lifemaxx hardware)
- No HOME intent filter and no boot receiver in the add-on's tvbridge APK.
- Accessibility-based Zen channel strip is visible only when a *recognized*
  Secreto HOME app is foreground. Other apps and HDMI get no overlay.
- The original Secreto HOME interface remains underneath. A GUIDE press opts
  into Zen channel selection; BACK cancels it. Normal D-pad input is delivered
  to Secreto outside channel selection.
- Five channel tiles with whole-logo FIT_CENTER artwork. Cached channel lists
  and cached logos survive receiver disconnection. Local logo/cache population
  requires one successful connection after installing this new add-on.
- Authenticated Zen 4 shared session, fast known-IP retry and offline detection.
- Zen key mapping on selected HDMI is unchanged from SatNova. HOME, SOURCE,
  volume, power and BACK stay with Android TV. The existing 2-hour arm expiry
  and confirmed HDMI input/foreground safeguards remain in effect.
- On channel OK the existing SatNova tune-then-connected-HDMI logic is applied.
- Secreto background, weather, prayer settings and APK are untouched.

## Theme
The user's original Islamic artwork is already checked in at
`tvhome/src/main/assets/satnova_wallpaper.webp`. To add it to the
unaltered Secreto application, use Secreto Wallpaper Studio to import it
as a personal image; it remains in addition to all Secreto wallpapers.

## Limitations
- Unlike editing Secreto's unpublished source, an overlay cannot integrate
  channels into Secreto's own widget hierarchy or app settings.
- Remote key filtering by third-party Accessibility may be blocked by
  Android firmware or conflict with another active key-filter service.
  Test HOME key behavior carefully and disable this add-on's Accessibility
  service immediately if Secreto's original HOME behavior regresses.
- Package recognition uses the foreground app label or package substring
  'secreto'. For non-standard TV builds, 'secreto_home_package' must be
  configured after the actual package is confirmed.
- Debug CI signing is for TEST only and not a stable update certificate.
  The production Secreto APK is never rebuilt, modified or redistributed.
- Current status: compilation / manifest tests in GitHub; not hardware tested.
