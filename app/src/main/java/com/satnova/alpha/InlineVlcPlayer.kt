package com.satnova.alpha

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Inline receiver stream. Owns one LibVLC player and releases it on disconnect. */
class InlineVlcPlayer(private val context:Context,private val view:VLCVideoLayout,
                      private val status:(String)->Unit) {
    private var engine:LibVLC?=null
    private var player:MediaPlayer?=null
    private var currentUrl:String?=null

    fun start(url:String){
        if(url==currentUrl && player!=null){
            try{player?.play()}catch(_:Exception){}
            return
        }
        stop()
        currentUrl=url
        try {
            val vlc=LibVLC(context,arrayListOf(
                "--network-caching=550", "--live-caching=550",
                "--no-video-title-show"
            ))
            engine=vlc
            val mediaPlayer=MediaPlayer(vlc)
            player=mediaPlayer
            mediaPlayer.attachViews(view,null,false,false)
            mediaPlayer.setEventListener { event ->
                when(event.type) {
                    MediaPlayer.Event.Playing -> status("LIVE • lecture du flux récepteur")
                    MediaPlayer.Event.Buffering -> status("LIVE • mise en mémoire tampon réseau…")
                    MediaPlayer.Event.EncounteredError -> status("Flux indisponible • vérifier la chaîne")
                    MediaPlayer.Event.EndReached -> status("Diffusion terminée")
                }
            }
            val media=Media(vlc,Uri.parse(url))
            media.addOption(":network-caching=550")
            media.addOption(":live-caching=550")
            mediaPlayer.media=media
            media.release()
            mediaPlayer.volume=100
            mediaPlayer.play()
        } catch(e:Exception) {
            status("Lecture vidéo impossible : "+(e.message?:"Erreur"))
            stop()
        } catch(e:UnsatisfiedLinkError){
            status("Bibliothèque VLC incompatible avec l'appareil")
            stop()
        }
    }
    fun pause() {
        try{player?.pause()}catch(_:Exception){}
    }
    fun resume(){
        try{player?.play()}catch(_:Exception){}
    }
    fun stop(){
        val old=player
        player=null
        try{old?.stop()}catch(_:Exception){}
        try{old?.detachViews()}catch(_:Exception){}
        try{old?.release()}catch(_:Exception){}
        try{engine?.release()}catch(_:Exception){}
        engine=null
        currentUrl=null
    }
}
