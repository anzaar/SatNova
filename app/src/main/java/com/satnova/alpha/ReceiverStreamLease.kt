package com.satnova.alpha

/** 1012 releases only a live stream requested by this control session. */
class ReceiverStreamLease {
    private var owned=false
    @Synchronized fun opened(){owned=true}
    @Synchronized fun takeStop():Boolean=owned.also{owned=false}
    @Synchronized fun reset(){owned=false}
    companion object {const val STOP=1012}
}
