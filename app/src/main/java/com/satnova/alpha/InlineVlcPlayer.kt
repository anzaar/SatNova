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
                      private val status:(String)->Unit) {
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
                    "--network-caching=550","--live-caching=550","--no-video-title-show"))
                engine=vlc
                val mp=MediaPlayer(vlc)
                player=mp
                currentUrl=url
                onMain{mp.attachViews(view,null,false,false)}
                mp.setEventListener { event ->
                    val message=when(event.type){
                        MediaPlayer.Event.Playing->"LIVE • lecture du flux récepteur"
                        MediaPlayer.Event.Buffering->"LIVE • mémoire tampon…"
                        MediaPlayer.Event.EncounteredError->"Flux indisponible • vérifier la chaîne"
                        MediaPlayer.Event.EndReached->"Diffusion terminée"
                        else->null
                    }
                    if(message!=null)main.post{if(ticket==generation.get())status(message)}
                }
                val media=Media(vlc,Uri.parse(url))
                try {
                    media.addOption(":network-caching=550")
                    media.addOption(":live-caching=550")
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
    fun stop(){
        if(closed)return
        generation.incrementAndGet()
        requestedUrl=null
        worker.execute{releasePlayer()}
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
