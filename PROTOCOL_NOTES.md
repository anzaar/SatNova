# SatNova 1.14 — restore the working transport

Playback uses the original 1.2 direct receiver URL, Activity context, network/live
caching of 550 ms and the same LibVLC view attachment/media options. The HTTP relay,
forced control-session reconnections and automatic media restarts have been removed.
Native cleanup still runs off the UI thread to retain the ANR fixes.

The original 1040 key payload uses a dedicated serial writer with frame writes
serialized against other control requests. Follow-up current-channel/mute reads
are coalesced 900 ms after a press burst instead of interleaving each key with
probes. The existing pending-URL/session guards remain.

Signal measurement is now an explicit tuning mode. Startup does not issue 403;
only after the HTTP reader closes does tuning mode read 403 and permit catalogue
requests. Tapping Return video stops measurement and reads the current channel
before restarting direct playback. The sound shortcut also enters tuning mode.
This tests a possible firmware interaction, rather than claiming that concurrent
signal/video support is known to be unavailable on all receivers.

Large channel/satellite/TP requests wait for an actual MediaPlayer.Playing event
or explicit tuning mode. Buffering-complete UI notices alone do not unlock them.
The current native UI, original icon, ordered list, satellite ranking, asynchronous
catalogue index and real signal values remain.
