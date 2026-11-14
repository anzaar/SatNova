# SatNova 1.13 — live receiver transport

Remote keys and the confirmed request 403 tuner signal retain their existing TCP
session when HTTP playback fails. Automatic HTTP recovery no longer closes and
relogs the control connection or sends request 1012, because the user's recording
showed that forced recovery lost the otherwise-working receiver connection.
Backgrounding closes only playback; resuming reads channel 3 before asking for a
new URL. A 1009 response timeout requires explicit reconnection rather than
misidentifying a later response on an unnumbered protocol.

One owned HTTP relay forwards the receiver's actual response and body to VLC
through a loopback socket. It never starts a parallel probe or video consumer.
It preserves error statuses and stream bytes, does not send Range requests, and
reports status, content type and received byte count under video diagnostics.
URLs/query credentials are never included in that diagnostic. Socket closure
precedes native VLC shutdown, so a stalled upstream cannot keep the old reader
open during replacement. RTSP/HTTPS retain direct VLC handling.

The existing channel/session ticket gate still drains obsolete-channel replies
and rejects playback callbacks from an obsolete control session. Empty 1009 ACKs
do not consume the pending URL request. A fifteen-second buffering watchdog and
a media error allow one playback-only retry; additional retries are manual.

Satellite/TP association indexing runs on a worker. The home remote/list switch
preserves receiver order and global channel positions; satellites rank by loaded
channel count. Request 403 measurements expire rather than displaying guessed
percentages.

Protocol reference for the dedicated signal query, verified on another firmware:
https://github.com/Eskander/ha-gmscreen/blob/main/docs/protocol.md
