package com.satnova.alpha

object ReceiverLoadPolicy {
    fun catalogue(watching:Boolean,listVisible:Boolean,buffering:Boolean,pendingVideo:Boolean,currentKnown:Boolean):Boolean =
        !watching || (!buffering && !pendingVideo && (listVisible || !currentKnown))
}
