package com.satnova.alpha

/** A live HTTP reader can stall without producing an EndReached/Error event. */
class LiveStreamWatchdog(private val timeoutMillis:Long=15000L) {
    private var waitingSince:Long?=null
    @Synchronized fun buffering(waiting:Boolean,now:Long){
        if(!waiting)waitingSince=null
        else if(waitingSince==null)waitingSince=now
    }
    @Synchronized fun reset(){waitingSince=null}
    @Synchronized fun takeStall(now:Long):Boolean {
        val since=waitingSince?:return false
        if(now-since<timeoutMillis)return false
        waitingSince=null
        return true
    }
}
