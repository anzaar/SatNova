package com.satnova.alpha

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Restores SatNova 0.8's proven VLC options for MPEG-TS/MP2 video/audio.
 * If no video output appears, fall back once to the Android Media3 player
 * used by the working 0.6 preview, without leaving the main screen.
 */
class InlineVlcPlayer(
    private val activity:Activity,
    private val vlcView:VLCVideoLayout,
    private val androidView:PlayerView,
    private val status:(String)->Unit
) {
    private val handler=Handler(Looper.getMainLooper())
    private var engine:LibVLC?=null
    private var vlc:MediaPlayer?=null
    private var exo:ExoPlayer?=null
    private var currentUrl:String?=null
    private var generation=0
    private var videoReady=false
    private var usingFallback=false

    fun start(url:String){
        // VLC's TextureView requires a measured inline surface. SurfaceView can render
        // behind rounded/scrolling Android containers and leave a black rectangle.
        if(vlcView.width<=0 || vlcView.height<=0){
            handler.postDelayed({
                if(activity.isFinishing)return@postDelayed
                if(vlcView.width>0 && vlcView.height>0)start(url)
                else status("Zone vidéo en préparation • vérifiez l'onglet Accueil")
            },350L)
            return
        }
        if(url==currentUrl && (vlc!=null || exo!=null)){
            resume()
            return
        }
        stopEngines()
        generation++
        val token=generation
        currentUrl=url
        videoReady=false
        usingFallback=false
        vlcView.visibility=View.VISIBLE
        androidView.visibility=View.GONE
        androidView.player=null
        status("VLC : ouverture du flux du récepteur…")
        try {
            // Same options as the earlier working VlcLiveActivity (0.8–1.1).
            val lib=LibVLC(activity,arrayListOf(
                "--network-caching=500",
                "--file-caching=500",
                "--no-video-title-show"
            ))
            engine=lib
            val mp=MediaPlayer(lib)
            vlc=mp
            mp.attachViews(vlcView,null,true,false)
            mp.setEventListener{event->
                activity.runOnUiThread{
                    if(token!=generation || usingFallback)return@runOnUiThread
                    when(event.type){
                        MediaPlayer.Event.Playing ->
                            status("VLC : lecture en cours • attente de l'image…")
                        MediaPlayer.Event.Vout -> {
                            videoReady=true
                            status("VLC • sortie vidéo initialisée • vérifiez que l’image apparaît")
                        }
                        MediaPlayer.Event.Buffering ->
                            status("VLC • chargement du flux local…")
                        MediaPlayer.Event.EncounteredError ->
                            switchToAndroid(url,token,"VLC ne décode pas ce flux")
                        MediaPlayer.Event.EndReached ->
                            status("Le récepteur a terminé le flux vidéo")
                    }
                }
            }
            val media=Media(lib,Uri.parse(url))
            media.addOption(":network-caching=500")
            media.addOption(":live-caching=500")
            mp.media=media
            media.release()
            mp.volume=100
            mp.play()
        }catch(e:Exception){
            switchToAndroid(url,token,"VLC : "+(e.message?:"erreur"))
        }catch(e:UnsatisfiedLinkError){
            switchToAndroid(url,token,"LibVLC indisponible sur ce téléphone")
        }
        // No looping retries. A single fallback only if the decoder never outputs video.
        handler.postDelayed({
            if(token==generation && !videoReady && !usingFallback && currentUrl==url){
                switchToAndroid(url,token,"Aucune image VLC après 9 secondes")
            }
        },9000L)
    }

    private fun switchToAndroid(url:String,token:Int,reason:String){
        if(token!=generation || usingFallback)return
        usingFallback=true
        stopVlc()
        vlcView.visibility=View.GONE
        androidView.visibility=View.VISIBLE
        status(reason+" • test du lecteur Android…")
        try{
            val player=ExoPlayer.Builder(activity).build()
            exo=player
            androidView.player=player
            player.addListener(object:Player.Listener{
                override fun onPlaybackStateChanged(playbackState:Int){
                    if(token!=generation || !usingFallback)return
                    when(playbackState){
                        Player.STATE_READY -> {
                            videoReady=true
                            status("LIVE • Image Android reçue (audio selon codec)")
                        }
                        Player.STATE_BUFFERING -> status("Android • chargement du flux…")
                        Player.STATE_ENDED -> status("Le flux du récepteur est terminé")
                    }
                }
                override fun onPlayerError(error:PlaybackException){
                    if(token!=generation)return
                    status("VLC et Android n'ont pas pu lire le flux : "+error.errorCodeName+
                        ". Vérifier DVB To IPTV, ou relancer le flux.")
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            player.prepare()
            player.playWhenReady=true
        }catch(e:Exception){
            status("Lecteur Android indisponible : "+(e.message?:"erreur"))
        }
    }

    fun tryAndroid(){
        val url=currentUrl
        if(url.isNullOrBlank()){
            status("Lecteur Android : aucune URL vidéo reçue du récepteur")
            return
        }
        if(usingFallback){
            status("Le lecteur Android est déjà en cours de test")
            return
        }
        switchToAndroid(url,generation,"Test manuel")
    }
    fun pause(){
        try{exo?.pause()}catch(_:Exception){}
        try{vlc?.pause()}catch(_:Exception){}
    }
    fun resume(){
        try{exo?.play()}catch(_:Exception){}
        try{vlc?.play()}catch(_:Exception){}
    }
    private fun stopVlc(){
        val old=vlc
        vlc=null
        try{old?.setEventListener(null)}catch(_:Exception){}
        try{old?.stop()}catch(_:Exception){}
        try{old?.detachViews()}catch(_:Exception){}
        try{old?.release()}catch(_:Exception){}
        try{engine?.release()}catch(_:Exception){}
        engine=null
    }
    private fun stopEngines(){
        androidView.player=null
        try{exo?.stop()}catch(_:Exception){}
        try{exo?.release()}catch(_:Exception){}
        exo=null
        stopVlc()
    }
    fun stop(){
        generation++
        currentUrl=null
        videoReady=false
        usingFallback=false
        stopEngines()
    }
}
