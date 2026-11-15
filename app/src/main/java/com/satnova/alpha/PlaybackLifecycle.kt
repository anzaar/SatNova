package com.satnova.alpha

/** Foreground must not reopen a receiver session while native shutdown is pending. */
class PlaybackLifecycle {
    private var generation=0L
    private var foreground=true
    private var closing=false
    @Synchronized fun background():Long {
        foreground=false;closing=true
        return ++generation
    }
    @Synchronized fun foreground():Boolean {
        val returning=!foreground
        foreground=true
        return returning && !closing
    }
    @Synchronized fun ready()=foreground && !closing
    @Synchronized fun closed(ticket:Long):Boolean {
        if(ticket!=generation || !closing)return false
        closing=false
        return foreground
    }
}
