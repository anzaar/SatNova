# Secreto + Zen 4 integration: scope and tested constraints

This is an experimental **reusable Zen receiver module**, NOT a modified
SecretoTVLauncher APK, and does not claim to include Secreto's proprietary source.
Keep Secreto TV Launcher installed and selected as the TV's HOME app. Run
SatNova's Zen activity from Secreto's app list if desired. Do not enable
SatNova's optional HOME override or boot-to-SatNova setting.

## Receiver behavior
- Show disk-cached channel catalogue when Zen 4 is disconnected or offline;
  distinguish cached display from actual online/tunable status.
- Cache logos individually on disk. Warm only a bounded first group of logos
  in the background while online; preserve all previously fetched art offline.
- Retry a previously authenticated receiver IP before starting network-wide
  discovery, with brief delays after transient failures.
- Detect a silent (half-open) receiver connection after 16s without responses
  and reconnect. This timeout is a heuristic and needs Lifemaxx/Zen 4 QA.
- Share a single Zen session between TV UI, foreground connection service,
  and opt-in Accessibility HDMI remote bridge.
- Only forward TV key events under the existing explicit HDMI arming and
  verified-input/foreground gates. Never hijack Secreto or unrelated apps.

## Not yet integrated into Secreto itself
Secreto's v1.3.2 public source archive contains only documentation/images.
Its published APK cannot be rebuilt with unchanged Secreto behavior from those
files. A companion app cannot inject rows directly into Secreto's private HOME
UI unless Secreto supports an integration API or its maintainer supplies source.

The SatNova Islamic background is separately available in the existing
SatNova asset `tvhome/src/main/assets/satnova_wallpaper.webp`; Secreto's
Wallpaper Studio can import local artwork, without removing its backgrounds.
Secreto prayer times, Adhan and weather settings remain untouched.

Builds, signing, HDMI key interception and actual reconnect speed still require
validation on the target Lifemaxx TV and the Zen 4 receiver.
