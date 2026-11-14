package com.satnova.alpha

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Native network/decoder shutdown must never block the Android event loop. */
class InlineVlcPlayer(private val context:Context, private val view:VLCVideoLayout,
                      private val status:(String)->Unit,private val buffering:(Boolean)->Unit={},private val failed:()->Unit={}) {
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val generation=AtomicLong()
    private var engine:LibVLC?=null
    private var player:MediaPlayer?=null
    private var currentUrl:String?=null
    private var closed=false
    private var requestedUrl:String?=null

    // Only the media worker waits. The UI thread always remains free to handle input.
    private fun onMain(action:()->Unit){
        val done=CountDownLatch(1)
        main.post{try{action()}finally{done.countDown()}}
        done.await()
    }
    fun start(url:String){
        if(closed)return
        val ticket=if(requestedUrl==url)generation.get() else generation.incrementAndGet()
        requestedUrl=url
        worker.execute {
            if(ticket!=generation.get())return@execute
            if(currentUrl==url && player!=null){
                player?.play()
                return@execute
            }
            releasePlayer()
            if(ticket!=generation.get())return@execute
            try {
                val vlc=LibVLC(context.applicationContext,arrayListOf(
                    "--network-caching=1800","--live-caching=1800","--no-video-title-show","--http-reconnect"))
                engine=vlc
                val mp=MediaPlayer(vlc)
                player=mp
                currentUrl=url
                onMain{mp.attachViews(view,null,false,false)}
                var lastMessage=""
                var lastBufferState:Boolean?=null
                var lastNoticeAt=0L
                mp.setEventListener { event ->
                    val progress=if(event.type==MediaPlayer.Event.Buffering)event.buffering else 100f
                    val waiting=event.type==MediaPlayer.Event.Buffering && progress<100f
                    val message=when(event.type){
                        MediaPlayer.Event.Playing->"LIVE • lecture du flux récepteur"
                        MediaPlayer.Event.Buffering->if(waiting)"Chargement vidéo • "+progress.toInt().coerceIn(0,100)+" %"
                            else "LIVE • lecture du flux récepteur"
                        MediaPlayer.Event.EncounteredError->"Flux interrompu • toucher la vidéo pour relancer"
                        MediaPlayer.Event.EndReached->"Diffusion terminée • toucher pour relancer"
                        else->null
                    }
                    if(message!=null){
                        val now=android.os.SystemClock.elapsedRealtime()
                        val bufferChanged=lastBufferState!=waiting
                        if(bufferChanged || (message!=lastMessage && (!waiting || now-lastNoticeAt>400L))){
                            lastMessage=message;lastNoticeAt=now;lastBufferState=waiting
                            main.post{if(ticket==generation.get()){
                                buffering(waiting);status(message)
                                if(event.type==MediaPlayer.Event.EncounteredError || event.type==MediaPlayer.Event.EndReached)failed()
                            }}
                        }
                    }
                }
                val media=Media(vlc,Uri.parse(url))
                try {
                    media.addOption(":network-caching=1800")
                    media.addOption(":live-caching=1800")
                    media.addOption(":http-reconnect")
                    mp.media=media
                } finally {media.release()}
                mp.volume=100
                mp.play()
            } catch(e:Exception){
                main.post{if(ticket==generation.get())status("Lecture vidéo impossible : "+e.message)}
                releasePlayer()
            } catch(e:UnsatisfiedLinkError){
                main.post{if(ticket==generation.get())status("Bibliothèque VLC incompatible avec l'appareil")}
                releasePlayer()
            }
        }
    }
    fun pause(){if(!closed)worker.execute{try{player?.pause()}catch(_:Exception){}}}
    fun resume(){if(!closed)worker.execute{try{player?.play()}catch(_:Exception){}}}
    fun stop(onStopped:()->Unit={}){
        if(closed)return
        generation.incrementAndGet()
        requestedUrl=null
        worker.execute{releasePlayer();main.post{if(!closed)onStopped()}}
    }
    fun close(){
        if(closed)return
        generation.incrementAndGet()
        closed=true
        worker.execute{releasePlayer()}
        worker.shutdown()
    }
    private fun releasePlayer(){
        val old=player
        player=null
        currentUrl=null
        try{old?.setEventListener(null)}catch(_:Exception){}
        try{old?.stop()}catch(_:Exception){}
        try{onMain{old?.detachViews()}}catch(_:Exception){}
        try{old?.release()}catch(_:Exception){}
        try{engine?.release()}catch(_:Exception){}
        engine=null
    }
}
