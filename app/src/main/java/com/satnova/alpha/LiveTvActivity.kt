package com.satnova.alpha

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.AudioAttributes
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Local RTSP preview. Requires a real SAT>IP URL returned by the receiver.
 * No guessed addresses and no streams are shared off the local network.
 */
class LiveTvActivity : Activity() {
    private var exo: ExoPlayer? = null
    private lateinit var info: TextView
    private lateinit var audioInfo: TextView
    private lateinit var video: PlayerView
    private var sourceUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 15, 29)
        window.navigationBarColor = Color.rgb(9, 15, 29)
        sourceUrl = intent.getStringExtra("stream_url") ?: ""
        val title = intent.getStringExtra("channel_title") ?: "Current channel"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 15, 29))
            setPadding(12.dp, 14.dp, 12.dp, 18.dp)
            setOnApplyWindowInsetsListener { view, insets ->
                val topInset=if(android.os.Build.VERSION.SDK_INT>=30)
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                else insets.systemWindowInsetTop
                val bottomInset=if(android.os.Build.VERSION.SDK_INT>=30)
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                else insets.systemWindowInsetBottom
                view.setPadding(12.dp,topInset+14.dp,12.dp,bottomInset+18.dp)
                insets
            }
        }
        root.addView(TextView(this).apply {
            text = "SATNOVA  •  LIVE TV"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(Color.rgb(165, 159, 255))
            setPadding(0, 12.dp, 0, 12.dp)
        })
        video = PlayerView(this).apply {
            useController = true
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
        }
        root.addView(video, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        info = TextView(this).apply {
            text = "Connexion au flux vidéo du récepteur…"
            textSize = 14f
            setTextColor(Color.rgb(231, 235, 246))
            setPadding(0, 16.dp, 0, 16.dp)
        }
        root.addView(info)
        audioInfo=TextView(this).apply{
            text="Audio : lecture de la piste en cours…"
            textSize=13f
            setTextColor(Color.rgb(175,189,221))
            setPadding(0,0,0,14.dp)
        }
        root.addView(audioInfo)
        root.addView(Button(this).apply {
            text = "Tester le son avec VLC (lecteur externe)"
            isAllCaps = false
            setOnClickListener { openExternalPlayer() }
        })
        root.addView(Button(this).apply {
            text = "Back to SatNova"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "Preview LAN : HTTP MPEG-TS ou RTSP. Si le flux ne démarre pas, tester VLC."
            textSize = 12f
            setTextColor(Color.rgb(162, 175, 197))
        })
        setContentView(root)
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        if (!sourceUrl.startsWith("rtsp://", true) &&
            !sourceUrl.startsWith("http://", true) && !sourceUrl.startsWith("https://", true)) {
            info.text = "Unsupported / missing stream URL"
            return
        }
        val player = ExoPlayer.Builder(this).build()
        player.setAudioAttributes(AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build(),true)
        player.volume=1f
        exo = player
        video.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                info.text = when (playbackState) {
                    Player.STATE_BUFFERING -> "Buffering live video…"
                    Player.STATE_READY -> "LIVE • Image reçue. Son à vérifier ci-dessous."
                    Player.STATE_ENDED -> "Receiver ended the stream"
                    else -> "Preparing video stream…"
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                val audioGroups=tracks.groups.filter{it.type==C.TRACK_TYPE_AUDIO}
                val descriptors=audioGroups.flatMap{group->
                    (0 until group.length).map{ix->
                        val format=group.getTrackFormat(ix)
                        val codec=format.sampleMimeType ?: format.codecs ?: "inconnu"
                        val support=if(group.isTrackSupported(ix))"compatible" else "non compatible"
                        val selected=if(group.isTrackSelected(ix))"sélectionnée" else "non sélectionnée"
                        codec+" · "+support+" · "+selected
                    }
                }
                audioInfo.text=if(descriptors.isEmpty())
                    "Audio : aucune piste détectée dans le flux. Tester VLC."
                else "Piste(s) audio : "+descriptors.joinToString(" ; ")+
                    "\nSi l'image marche sans son, essayer VLC pour comparer les décodeurs."
            }
            override fun onPlayerError(error: PlaybackException) {
                info.text = "Playback error: " + error.errorCodeName +
                    "\nCodec ou conteneur non compatible ? Utiliser VLC."
            }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.parse(sourceUrl)))
        player.prepare()
        player.playWhenReady = true
    }

    private fun openExternalPlayer() {
        if (sourceUrl.isBlank()) return
        val uri = Uri.parse(sourceUrl)
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            i.setPackage("org.videolan.vlc")
            startActivity(i)
        } catch (_: ActivityNotFoundException) {
            info.text = "Application VLC (org.videolan.vlc) non installée. Ce bouton ne lance pas un navigateur."
        } catch (e: Exception) {
            info.text = "Could not open external player: " + (e.message ?: "Unknown error")
        }
    }

    override fun onStop() {
        video.player = null
        exo?.release()
        exo = null
        super.onStop()
    }
}
