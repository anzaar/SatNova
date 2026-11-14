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
