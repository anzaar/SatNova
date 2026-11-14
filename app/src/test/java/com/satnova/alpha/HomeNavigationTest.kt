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
            assertTrue(text("Force  —").isShown)
            text("◈\nSatellites").performClick()
            assertTrue(text("Force  —").isShown)
            text("Regarder").performClick()
            assertTrue(screen.inlineVideo.isShown)
            text("⟳ Redémarrer").performClick()
            assertEquals(1,restarts)
            assertFalse(screen.inlineVideo.isShown)
            assertTrue(text("Choisissez Regarder ou Signal").isShown)
        } finally {screen.close();controller.pause().stop().destroy()}
    }
}
