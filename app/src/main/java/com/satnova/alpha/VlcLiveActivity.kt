package com.satnova.alpha

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Native VLC playback for receiver HTTP MPEG-TS with MP2 audio. */
class VlcLiveActivity : Activity() {
    private lateinit var video: VLCVideoLayout
    private lateinit var status: TextView
    private var engine: LibVLC?=null
    private var player: MediaPlayer?=null
    private var url=""
    private val navy=Color.rgb(9,15,29)
    private val accent=Color.rgb(180,174,255)
    private val Int.dp get()=(this*resources.displayMetrics.density+0.5f).toInt()

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=navy
        window.navigationBarColor=navy
        window.decorView.systemUiVisibility=
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR).inv() and
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        url=intent.getStringExtra("stream_url") ?: ""
        val title=intent.getStringExtra("channel_title") ?: "Chaîne en direct"
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(12.dp,48.dp,12.dp,15.dp)
            fitsSystemWindows=true
        }
        val head=TextView(this).apply{
            text="SATNOVA  •  LIVE TV"
            setTextColor(Color.WHITE)
            textSize=23f
            setTypeface(null,Typeface.BOLD)
        }
        root.addView(head)
        root.addView(TextView(this).apply{
            text=title
            textSize=16f
            setTextColor(accent)
            setPadding(0,14.dp,0,14.dp)
        })
        video=VLCVideoLayout(this).apply{
            setBackgroundColor(Color.BLACK)
            keepScreenOn=true
        }
        root.addView(video,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        status=TextView(this).apply{
            text="Initialisation du lecteur VLC • MPEG Layer II pris en charge par le moteur VLC."
            setTextColor(Color.rgb(218,225,246))
            textSize=13f
            setPadding(0,16.dp,0,14.dp)
        }
        root.addView(status)
        root.addView(Button(this).apply{
            text="▮▮  Pause / ▶  Reprendre"
            isAllCaps=false
            setOnClickListener{
                val p=player
                if(p==null)return@setOnClickListener
                if(p.isPlaying)p.pause() else p.play()
            }
        })
        root.addView(Button(this).apply{
            text="Ouvrir avec VLC externe"
            isAllCaps=false
            setOnClickListener{openExternalVlc()}
        })
        root.addView(Button(this).apply{
            text="Retour à SatNova"
            isAllCaps=false
            setOnClickListener{finish()}
        })
        root.addView(TextView(this).apply{
            text="Flux local du récepteur • Le son MP2 utilise le décodage VLC embarqué."
            textSize=11f
            setTextColor(Color.rgb(154,172,204))
        })
        setContentView(root)
    }

    override fun onStart(){
        super.onStart()
        if(url.isBlank()){status.text="Le récepteur n'a fourni aucun flux.";return}
        try{
            val opts=arrayListOf("--network-caching=500", "--file-caching=500", "--no-video-title-show")
            val vlc=LibVLC(this,opts)
            engine=vlc
            val mp=MediaPlayer(vlc)
            player=mp
            mp.attachViews(video,null,false,false)
            mp.setEventListener{event->
                runOnUiThread{
                    status.text=when(event.type){
                        MediaPlayer.Event.Playing -> "LIVE • Lecture vidéo + décodage audio VLC"
                        MediaPlayer.Event.Buffering -> "Chargement du flux local…"
                        MediaPlayer.Event.EncounteredError -> "Erreur de lecture VLC. Vérifier le flux et la chaîne."
                        MediaPlayer.Event.EndReached -> "Flux terminé par le récepteur."
                        else -> status.text.toString()
                    }
                }
            }
            val media=Media(vlc,Uri.parse(url))
            media.addOption(":network-caching=500")
            media.addOption(":live-caching=500")
            mp.media=media
            media.release()
            mp.volume=100
            mp.play()
        }catch(e:Exception){
            status.text="VLC intégré indisponible : "+(e.message?:"Erreur du moteur")
            releasePlayer()
        }catch(e:UnsatisfiedLinkError){
            status.text="Librairie VLC non compatible avec ce téléphone : "+(e.message?:"")
            releasePlayer()
        }
    }

    private fun openExternalVlc(){
        try{
            val intent=Intent(Intent.ACTION_VIEW).apply{
                setDataAndType(Uri.parse(url),"video/*")
                setPackage("org.videolan.vlc")
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            startActivity(intent)
        }catch(e:ActivityNotFoundException){
            status.text="VLC Android non installé (org.videolan.vlc). Aucun navigateur ouvert."
        }catch(e:Exception){status.text="Impossible d'ouvrir VLC : "+(e.message?:"Erreur")}
    }

    private fun releasePlayer(){
        try{player?.stop()}catch(_:Exception){}
        try{player?.detachViews()}catch(_:Exception){}
        try{player?.release()}catch(_:Exception){}
        player=null
        try{engine?.release()}catch(_:Exception){}
        engine=null
    }

    override fun onStop(){
        releasePlayer()
        super.onStop()
    }
}
