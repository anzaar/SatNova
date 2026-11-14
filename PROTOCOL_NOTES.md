# SatNova 1.19 — return to the observed working 1.10 playback path

User screenshots of 1.10 show working picture/audio. Comparing that commit
157883927771 with 1.18 confirms that its 1009 JSON/XML payload, direct URI passed
to LibVLC, 550 ms network/live caches, SurfaceView attachment, and volume 100 were
the working path. The 1.19 player retains asynchronous native teardown to keep
Android's event loop free; generation guards still reject stale replies.

The 1.18 loopback HTTP relay and automatic URL-host rewriting are removed from
playback. The validated local URL returned by 1009 reaches VLC unchanged. Real
LibVLC Media Stats (readBytes, decodedVideo, displayedPictures, decodedAudio,
playedAbuffers) replace the relay's inference about whether data arrives.

Signal reads the current tuner using request 403, as in 1.10. It does not enter
the separate 401/402/405 installation session: no frequency is being edited in
this UI. The reference SatRequestManager uses those commands for a distinct
SatFinderActivity; their presence alone does not require entering that mode to
read current signal. This change restores the earlier successful read path.

Reference LivePlayActivity.V0/W0 sends 1012 when its explicit Sat2IP_Rtsp setup
exists. 1.19 limits that ownership/stop command to an RTSP stream, and does not
apply it to the direct HTTP path that worked on the user's receiver.

Copied diagnostics now belong to the last video attempt. They include receiver
IP, channel, request time, credential-free endpoint, native state and real VLC
counters. Options/Signal, disconnect and application restart do not replace this
report with 'video paused' or login capability flags. Path/query/user info are
not included. Tests cover report retention, credential redaction, new attempts,
copying after switching pages, late reply invalidation and UI navigation.

Physical video playback remains unverified in CI. This restores a user-observed
working path; it does not prove that the receiver service currently responds.
