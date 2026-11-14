package com.satnova.alpha

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HomeNavigationTest {
    private fun texts(view:View):List<TextView> {
        val own=if(view is TextView)listOf(view) else emptyList()
        return own+if(view is ViewGroup)(0 until view.childCount).flatMap{texts(view.getChildAt(it))} else emptyList()
    }
    @Test fun switchesBetweenRemoteAndInlineListWithoutHidingTheVideoSurface(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        activity.getSharedPreferences("satnova",0).edit().clear().commit()
        val screen=SatNovaScreen(activity,
            onDiscover={},onAdvancedScan={},onSelectReceiver={},onDisconnect={},onConnect={},
            onWatch={},onRefresh={},onReadSatellites={},onReadTransponders={},onReadSignal={},
            onWatchMode={},onSignalMode={},onRestart={},
            onServers={},onNetwork={},onScan={},onChannelTune={_,_->},onChannelRefresh={},
            onEpgRemote={},onTabChanged={},onKey={})
        try {
            activity.setContentView(screen.root)
            screen.setReceiverOnline(true)
            screen.setUseMode("watch")
            fun text(label:String)=texts(screen.root).first{it.text.toString()==label}
            assertTrue(text("CH +").isShown)
            text("Liste").performClick()
            assertFalse(text("CH +").isShown)
            assertTrue(text("Tous les satellites ▾").isShown)
            assertTrue(screen.inlineVideo.isShown)
            text("Télécommande").performClick()
            assertTrue(text("CH +").isShown)
            text("◈\nSatellites").performClick()
            assertTrue(screen.inlineVideo.isShown)
            assertTrue(texts(screen.root).none{it.text.toString()=="☷\nChaînes"})
            text("⌂\nAccueil").performClick()
            assertTrue(text("CH +").isShown)
        } finally {screen.close();controller.pause().stop().destroy()}
    }

    @Test fun explicitModesAndRestartAreSeparateActions(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        activity.getSharedPreferences("satnova",0).edit().clear().commit()
        lateinit var screen:SatNovaScreen
        var restarts=0
        screen=SatNovaScreen(activity,
            onDiscover={},onAdvancedScan={},onSelectReceiver={},onDisconnect={},onConnect={},
            onWatch={},onRefresh={},onReadSatellites={},onReadTransponders={},onReadSignal={},
            onWatchMode={screen.setSignalMonitoring(false);screen.setUseMode("watch")},
            onSignalMode={screen.setUseMode("signal");screen.setSignalMonitoring(true)},
            onRestart={restarts++;screen.setSignalMonitoring(false);screen.setUseMode("none")},
            onServers={},onNetwork={},onScan={},onChannelTune={_,_->},onChannelRefresh={},
            onEpgRemote={},onTabChanged={},onKey={})
        try {
            activity.setContentView(screen.root)
            screen.setReceiverOnline(true)
            fun text(label:String)=texts(screen.root).first{it.text.toString()==label}
            assertFalse(screen.inlineVideo.isShown)
            text("Regarder").performClick()
            assertTrue(screen.inlineVideo.isShown)
            text("Signal").performClick()
            // A mode click alone must not destroy a surface while native playback is still closing.
            assertTrue(screen.inlineVideo.isShown)
            screen.showSignalReady()
            assertFalse(screen.inlineVideo.isShown)
            assertFalse(text("CH +").isShown)
            assertFalse(text("◈\nSatellites").isShown)
            assertTrue(text("Regarder").isShown)
            screen.setReceiverIdentity("ECHOLINK ZEN 4")
            screen.setReceiverDetails("192.168.1.2","240117402977")
            assertTrue(texts(screen.root).any{it.isShown && it.text.toString()=="ECHOLINK ZEN 4"})
            assertTrue(texts(screen.root).any{it.isShown && it.text.toString().contains("IP : 192.168.1.2") && it.text.toString().contains("Série : 240117402977")})
            activity.window.decorView.measure(
                View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY))
            activity.window.decorView.layout(0,0,480,800)
            fun gauges(v:View):List<SignalGaugeView> =
                (if(v is SignalGaugeView)listOf(v) else emptyList())+
                (if(v is ViewGroup)(0 until v.childCount).flatMap{gauges(v.getChildAt(it))} else emptyList())
            val meters=gauges(screen.root)
            assertEquals(2,meters.size)
            assertTrue(meters.all{it.isShown && it.height>100 && it.width>100})
            text("Regarder").performClick()
            assertTrue(screen.inlineVideo.isShown)
            text("⟳ Redémarrer").performClick()
            assertEquals(1,restarts)
            assertFalse(screen.inlineVideo.isShown)
            assertTrue(text("Choisissez Regarder ou Signal").isShown)
        } finally {screen.close();controller.pause().stop().destroy()}
    }
}
