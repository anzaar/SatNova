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

## 1.20 — verified playback, smoother buffering and durable catalogues

The user confirmed that hardware receiver restart restored playback: 1.19 native
counters showed received bytes, displayed pictures and played audio. Later
reports came from separate attempts and cannot be compared as throughput samples.
The direct URI/player/protocol path is retained. Network/live caching increases
from 550 ms to 1500 ms; no clock/codec/decoder mode changes or new HTTP relay.
Diagnostics add elapsed attempt time, native lost/corrupt counters and buffering
episodes, so future recordings can distinguish stalls from initial buffering.

A reconnect previously unconditionally cleared all channels, satellites, TP and
paging progress. Catalogues now persist atomically per validated login hardware
identity (STB serial plus chip identity and model; never IP alone). Reconnect
restores receiver order, associations and the next missing page. Changed IP on
the same hardware preserves the cache; a different receiver at the same IP does
not borrow it. The reported channel count and 2002 notification still invalidate
changed data. The channel list overflow adds explicit receiver refresh, including
satellite/TP reload, for changes that cannot be detected from count alone.

Catalogue writes are coalesced, snapshot on the control worker, and serialize on
a separate disk worker. Corrupt/missing files are ignored. During known-channel
Watch with the list hidden, catalogue reads are paused. Visible-list reads also
wait while the video buffers/negotiates; current-state/remote commands continue.

## 1.21 — channel-switch teardown and frozen-output recovery

The recording shows 2M freezing while the UI still says LIVE. Native Playing
and Buffering(100) are no longer treated as proof of rendered output: sampled
picture/audio counters establish progress. An eight-second loss of picture
progress (even with continuing audio) or fifteen-second empty startup triggers
one fresh stream request per channel, with software decoding on output stalls.
A second stall stops playback and offers an explicit retry, avoiding a restart
loop. Radio uses played audio until a video stream is observed.

List tunes and CH/up/down commands close the owned native reader before sending
the channel command. Fresh playback waits 400 ms after teardown and checks the
latest preparation generation, channel, connection and work mode. The original
1009 URI, 1500 ms buffer, remote payloads and passive signal protocol remain.
Tests exercise frozen output with continuing decode/audio, startup timeout,
radio, advancing still-picture frames and fresh-session counters. Receiver
hardware validation is still required; these tests do not reproduce firmware.
