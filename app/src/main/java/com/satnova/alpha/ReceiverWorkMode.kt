package com.satnova.alpha

/** Serializes tuner measurement and HTTP startup on firmware that may not support both. */
class ReceiverWorkMode {
    private var measuring=false
    private var readerClosed=false
    private var playing=false
    private var browsing=false
    @Synchronized fun reset(){browsing=false;measuring=false;readerClosed=false;playing=false}
    @Synchronized fun measure(enabled:Boolean){browsing=false;measuring=enabled;readerClosed=false;playing=false}
    @Synchronized fun browse(){browsing=true;measuring=false;playing=false}
    @Synchronized fun readerClosed(){if(measuring)readerClosed=true}
    @Synchronized fun videoStarted(){if(!measuring){playing=true;browsing=false}}
    @Synchronized fun videoStopped(){playing=false}
    @Synchronized fun mayReadSignal()=measuring && readerClosed
    @Synchronized fun mayStartVideo()=!measuring
    @Synchronized fun mayReadCatalogue(buffering:Boolean)=if(measuring)readerClosed else browsing || (playing && !buffering)
}
