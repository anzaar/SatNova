package com.satnova.alpha

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
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
            text = "Connecting to receiver's RTSP stream…"
            textSize = 14f
            setTextColor(Color.rgb(231, 235, 246))
            setPadding(0, 16.dp, 0, 16.dp)
        }
        root.addView(info)
        root.addView(Button(this).apply {
            text = "Open in VLC / another player"
            isAllCaps = false
            setOnClickListener { openExternalPlayer() }
        })
        root.addView(Button(this).apply {
            text = "Back to SatNova"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "Preview is LAN-only. MPEG-TS over RTSP or unsupported video codecs may need VLC."
            textSize = 12f
            setTextColor(Color.rgb(162, 175, 197))
        })
        setContentView(root)
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        if (!sourceUrl.startsWith("rtsp://", ignoreCase = true)) {
            info.text = "Unsupported / missing RTSP stream URL"
            return
        }
        val player = ExoPlayer.Builder(this).build()
        exo = player
        video.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                info.text = when (playbackState) {
                    Player.STATE_BUFFERING -> "Buffering live video…"
                    Player.STATE_READY -> "LIVE • Video and audio are from receiver"
                    Player.STATE_ENDED -> "Receiver ended the stream"
                    else -> "Preparing video stream…"
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                info.text = "Playback error: " + error.errorCodeName +
                    "\nIf the stream is MPEG-TS/unsupported RTSP, use the VLC button."
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
            startActivity(Intent.createChooser(i, "Select video player"))
        } catch (_: ActivityNotFoundException) {
            info.text = "No compatible player installed. Install VLC to test this RTSP stream."
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
