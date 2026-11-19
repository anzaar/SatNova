# AltaPlay Launcher — agreed scope and release gates
**Branch:** `feature/altaplay-launcher-secreto-zen-20261119`  
**Product display name:** **AltaPlay Launcher**  
**Package (TVBridge prototype):** `com.altaplay.launcher` (separate from SecretoTVLauncher, SatNova and AltaPlay IPTV)
  
## Product that the user requested — KEEP Secreto's UX, add Zen 4 only
- **HOME**: must behave like the successfully working Secreto HOME implementation on Lifemaxx 55.
- **No feature loss**: keep Secreto's app management, folders/favorites if present, D-pad navigation, move apps, settings, lightweight startup, multilingual UI, wallpaper studio, photo/video wallpapers, prayer time, city selection, automatic Adhan, and all existing weather behavior. Do not substitute SatNova's prayer/weather implementation for Secreto's.
- **Islamic theme**: add the original SatNova Islamic wallpaper **as an additional choice** beside all Secreto wallpapers. Asset in `tvhome/src/main/assets/satnova_wallpaper.webp`.
- **Zen channels**: present grouped TV channels and separate radio row and logos **within HOME**, plus custom favorites and quick access to active HDMI.
- **Offline first**: keep last verified channel catalogue, IDs, ordering, manual logos and disk-cached fetched logos visible even if Zen 4 is powered off or Wi-Fi fails. Show a small offline state. Do not misrepresent disconnected channels as tuned; disallow/clearly fail tune while offline. Never delete cache upon loss of connection.
- **Fast reattachment**: try previously authenticated receiver first, backoff on repeated failures, then authenticated UDP/LAN discovery. Silent socket detection; reconnect without launching a wizard, and never block UI.
- **HDMI remote**: share the same one-session receiver client; opt-in Accessibility redirects D-pad/OK/channel/menu keys ONLY while a confirmed HDMI viewer is in foreground and the selected physical HDMI input is connected. HOME, SOURCE, physical TV volume/power must remain TV keys. Restore native key routing immediately on leaving HDMI.
- **System safety**: keep Secreto / Google TV recovery available; no automatic debloat, permission overreach, or disabling Google HOME. Any ADB optimisation needs device-specific package checks and explicit user choice.
- **Performance**: no receiver scan, logo download or network operation on HOME UI thread. Cache logo decode and throttle redraws. Compare HOME startup, focus latency, RAM and return from HDMI to baseline Secreto on real Lifemaxx hardware.
  
## Development truth
The public SecretoTVLauncher v1.3.2 source archives do **not** include its proprietary modified app code. A ZIP/TAR containing images and README does not allow preserving every Secreto implementation. Based on current available source, the original MIT-licensed **CleanYourTV** project can be studied/reused with attribution. Reproducing Secreto-only features or modifying its shipped APK requires an APK technical review (and may require permission, app-signing migration and reimplementation).
  
**Current state of this branch is an AltaPlay-branded Zen *prototype* based on the existing SatNova TV implementation**, with offline cache and fast recovery. It is NOT yet the requested fully Secreto-based launcher; Secreto prayer/weather/wallpaper/optimisation feature parity has not been established. Keep the branch separate from SatNova production, do not advertise as a Secreto-based build or publish as a final APK.
  
## First prototype QA
1. TVBridge Android debug compilation / Android manifest / APK cert verification.
2. Install alongside Secreto & SatNova (different package). Confirm Android HOME defaults unchanged until explicitly selected.
3. Test receiver power-off/on while channel list and logos stay on-screen.
4. Disable Wi-Fi, restore Wi-Fi and observe time to reconnect; record time and any stale status.
5. Switch to active HDMI via channel; test remote mapping; return to HOME; ensure no keys are hijacked within Secreto, YouTube or settings.
6. Only start full branded launcher build after Secreto source parity or APK-based feature audit and explicit validation of each Secreto-only feature.
