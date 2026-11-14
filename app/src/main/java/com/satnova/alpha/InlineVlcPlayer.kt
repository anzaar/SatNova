package com.satnova.alpha

import android.app.Activity
import android.content.Context
import android.media.AudioManager
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
    private var pendingStartUrl:String?=null
    private var surfaceWaitCount=0
    private val audioManager:AudioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val audioFocusListener=AudioManager.OnAudioFocusChangeListener { change ->
        if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){
            status("Audio temporairement occupé par une autre application")
        }
    }
    private fun acquireAudioFocus(){
        try{
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)
        }catch(_:Exception){}
    }
    private fun releaseAudioFocus(){
        try{
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }catch(_:Exception){}
    }

    fun start(url:String){
        if(pendingStartUrl!=url){
            pendingStartUrl=url
            surfaceWaitCount=0
        }
        // The 0.8 VLC Activity started only after its surface was attached.
        // Inline Accueil can receive 1009 before layout: retry until ready.
        if(!vlcView.isAttachedToWindow || vlcView.width<2 || vlcView.height<2){
            surfaceWaitCount++
            if(surfaceWaitCount>80){
                status("Zone vidéo indisponible : retourner sur Accueil puis relancer")
                return
            }
            handler.postDelayed({
                if(!activity.isFinishing && pendingStartUrl==url)start(url)
            },250L)
            return
        }
        pendingStartUrl=null
        surfaceWaitCount=0
        if(url==currentUrl && (vlc!=null || exo!=null)){
            resume()
            return
        }
        stopEngines()
        generation++
        acquireAudioFocus()
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
            mp.attachViews(vlcView,null,false,false)
            mp.setEventListener{event->
                activity.runOnUiThread{
                    if(token!=generation || usingFallback)return@runOnUiThread
                    when(event.type){
                        MediaPlayer.Event.Playing ->
                            status("VLC • flux reçu • MP2 pris en charge, son à vérifier")
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
        // Keep VLC 0.8 on-screen rather than automatically replacing its MP2
        // audio decoder with Android just as a video frame becomes available.
        handler.postDelayed({
            if(token==generation && !videoReady && !usingFallback && currentUrl==url){
                status("VLC : pas de sortie vidéo confirmée après 12 s. "+
                    "Essayer Lecteur Android ou le lecteur VLC classique.")
            }
        },12000L)
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
        releaseAudioFocus()
    }
    fun stop(){
        pendingStartUrl=null
        surfaceWaitCount=0
        generation++
        currentUrl=null
        videoReady=false
        usingFallback=false
        stopEngines()
    }
}
