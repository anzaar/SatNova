# SatNova 1.16 — full-height signal workspace

Signal is the first mode button and is selected after a successful receiver login.
Measurement waits for the native HTTP reader to close. The preview, home controls,
channel list and bottom navigation are hidden while Signal uses the available
height; Regarder and Redémarrer remain accessible above it. Regarder restores the
same native preview, remote and ordered channel picker.

Two native gauges show actual request 403 quality/strength percentages, with no
synthetic samples. Stale/missing values become a dash and silence the optional
quality tone. The panel also shows the current channel/satellite/frequency and
the verified receiver name, actual control-connection IP and SerialNumber from
receiver info. Unknown serial numbers remain pending, rather than using the
phone's or application's identity.

The existing direct LibVLC transport (550 ms network/live cache), control payloads,
session guards and application reconnect behavior are unchanged in this UI update.
The video-buffering failure remains unresolved on the user's receiver. Copied
video diagnostics retain credential-free stream/control hosts and the raw SAT>IP
indicator for further investigation.
