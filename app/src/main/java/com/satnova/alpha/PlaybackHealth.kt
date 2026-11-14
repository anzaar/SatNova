package com.satnova.alpha

/** Native Playing/100% buffering does not prove that frames reach the screen. */
class PlaybackHealth(startedAt:Long, private val startupMillis:Long=15000L,
                     private val stallMillis:Long=8000L) {
    enum class Result { WAITING, PROGRESS, STALLED }
    private var lastProgress=startedAt
    private var pictures=0L
    private var audio=0L
    private var videoSeen=false
    private var outputSeen=false
    private var reported=false
    fun sample(now:Long,decodedVideo:Long,displayedPictures:Long,playedAudio:Long):Result {
        if(reported)return Result.WAITING
        videoSeen=videoSeen || decodedVideo>0 || displayedPictures>0
        val advanced=if(videoSeen)displayedPictures>pictures else playedAudio>audio
        pictures=displayedPictures;audio=playedAudio
        if(advanced){lastProgress=now;outputSeen=true;return Result.PROGRESS}
        val timeout=if(outputSeen)stallMillis else startupMillis
        if(now-lastProgress>=timeout){reported=true;return Result.STALLED}
        return Result.WAITING
    }
}
