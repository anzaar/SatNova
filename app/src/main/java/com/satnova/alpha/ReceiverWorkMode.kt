package com.satnova.alpha

/** Serializes tuner measurement and HTTP startup on firmware that may not support both. */
class ReceiverWorkMode {
    private var measuring=false
    private var readerClosed=false
    private var playing=false
    @Synchronized fun reset(){measuring=false;readerClosed=false;playing=false}
    @Synchronized fun measure(enabled:Boolean){measuring=enabled;readerClosed=false;playing=false}
    @Synchronized fun readerClosed(){if(measuring)readerClosed=true}
    @Synchronized fun videoStarted(){if(!measuring)playing=true}
    @Synchronized fun videoStopped(){playing=false}
    @Synchronized fun mayReadSignal()=measuring && readerClosed
    @Synchronized fun mayStartVideo()=!measuring
    @Synchronized fun mayReadCatalogue(buffering:Boolean)=if(measuring)readerClosed else playing && !buffering
}
