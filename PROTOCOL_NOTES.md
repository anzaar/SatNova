# SatNova 1.18 — receiver media lifecycle and transport evidence

The user's recording 110737.mp4 shows working 1040 channel changes while VLC
remains buffering at zero, followed by finder START returning status 1. This
proves control is still live; it does not prove that HTTP media bytes arrive.

Static interoperability references from the current G-MScreen Android APK:
- SatRequestManager: bare START 401, SQ 403, END 405.
- LivePlayActivity.V0/W0: native/RTSP teardown followed by bare PLAY_STOP 1012.
- LivePlayActivity.D0: rewrite a stale receiver URL IP to the connected STB IP,
  keeping the returned port, path and channel parameters.
- GsDebugActivity: bare hardware restart 1043, distinct from standby key 42.

SatNova now releases its own 1009 stream with 1012 before entering finder,
changing the streamed channel or leaving Watch. It never sends STOP before
this control session has requested video. Finder status 1 gets one END/START
recovery attempt; a final rejection leaves an explicit retry, not a stuck flag.
Pending URL replies from an invalidated playback generation are drained and
cannot revive an old reader, even when the channel ID did not change.

An uncached loopback HTTP relay owns one receiver GET. Its raw upstream socket
can be closed immediately before native VLC shutdown; this avoids depending on
a blocked decoder/network read to finish a navigation callback. It reports HTTP
status, actual transferred bytes, and bounded connection/read timeouts. Its
reports omit the URL path, query and tokens. These observations are preserved in
Options / copied diagnostics; they distinguish absent receiver bytes from later
playback/decoding failure. RTSP continues through LibVLC directly.

Tests use a local fake receiver for forwarded current bytes, HTTP 503, HTTP 200
with no body, and cancellation while awaiting headers. They also cover stale URL
NIC correction, one STOP per owned stream, invalidated late replies, and the
four-page navigation/restart picker. No physical receiver is available in CI;
video and signal recovery still require validation on the user's firmware.
