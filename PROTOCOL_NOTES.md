# Read-only tuner signal and metadata

SatNova 1.10 queries G-MScreen request 403 for receiver-reported `strength`
and `quality` percentages. This replaces looking only for signal fields in
requests 14/15. A zero-body acknowledgement is not treated as a sample.
One signal request remains outstanding for up to 3.5 seconds; active monitoring
is limited to the Satellites page and stops in the background. Samples older
than 4.5 seconds clear both gauges and silence the optional tone. SNR/dB and
unknown integer scales are never converted into guessed percentages.

Protocol reference and hardware-specific limitation:
https://github.com/Eskander/ha-gmscreen/blob/main/docs/protocol.md
The reference verified 403 on FUN_60 firmware 3.87; it is not evidence that every
G-MScreen receiver supports that request. The interface reports missing or
unrecognized replies rather than asserting that signal measurement is unsupported.

The gauges always describe the receiver's current tuned channel. Selecting a
satellite or TP in the read-only catalogue does not silently retune it. Use the
home remote or a saved channel's tune action to change the receiver's channel.

Channel pages (request 0, at most 100 records) start after playback initializes,
continue until the current channel is identified, and continue through the full
bounded catalogue when Channels is opened. Request 24 supplies TP metadata.
Channel/satellite/TP associations are indexed on a worker and lists render at
most 30 items per page. The live video surface stays outside tab visibility
changes so tab switches do not destroy a running decoder's surface.

## SatNova 1.11

The home control area switches between the same working remote payloads and an
inline channel picker. Filters retain original receiver order and global channel
numbers. Satellite cards rank by actual loaded channel count; partial counts
are identified as loading. Signal gauges and sound control sit with the current
channel, without an EPG placeholder. The original vector launcher icon is restored.

LibVLC keeps the same decoding setup with a 1800 ms network/live cache and HTTP
reconnect. A Buffering event at 100 percent reports playback, not perpetual
buffering; progress notices are throttled. Catalogue paging is paced and deferred
while the player buffers once the current channel is identified.

## SatNova 1.12 — fresh live sessions

Video is not stored on disk. Only the last verified receiver IP, client UUID and
home control preference persist. A URL must now belong to a single pending 1009
request, the current channel and the active TCP session. Empty ACKs keep waiting
for the URL; channel changes drain obsolete replies; old timeouts cannot cancel
new requests. Queued UI playback checks the same session/channel ticket again.

The native HTTP reader finishes closing before request 1012 and a fresh 1009.
Backgrounding closes playback and the control session. Returning logs in again
and reads request 3 before requesting video; it never plays the previous URL.
A media failure or URL timeout permits one fresh-session recovery, with further
retries initiated by touching the player rather than an endless reconnect loop.

A buffering watchdog handles the observed black-screen/0-percent state: fifteen
seconds without leaving buffering triggers the same bounded fresh-session recovery,
even when LibVLC does not raise an error/end event. Successful playback, backgrounding
and session changes cancel that deadline. Repeated buffering notices do not reset it.
