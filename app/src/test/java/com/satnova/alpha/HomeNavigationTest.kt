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
    private fun texts(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else emptyList())

    @Test fun unifiedRemoteReplacesLiveVideoAndWorksWithoutReceiver() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.getSharedPreferences("satnova",0).edit().clear().commit()
        val screen = SatNovaScreen(activity,
            onDiscover={},onAdvancedScan={},onSelectReceiver={},onDisconnect={},onConnect={},
            onWatch={},onRefresh={},onReadSatellites={},onReadTransponders={},onReadSignal={},
            onWatchMode={},onSignalMode={},onRestart={},
            onServers={},onNetwork={},onScan={},onChannelTune={_,_->},onChannelRefresh={},
            onEpgRemote={},onTabChanged={},onKey={},onTvRemote={},onTvKey={},onTvApp={})
        try {
            activity.setContentView(screen.root)
            screen.setReceiverOnline(false)
            val all = texts(screen.root)
            assertTrue(all.any { it.isShown && it.text.toString().contains("Lifemaxx") })
            assertTrue(all.any { it.isShown && it.text.toString().contains("YouTube") })
            assertTrue(all.any { it.isShown && it.text.toString().contains("AltaPlay") })
            assertTrue(all.any { it.isShown && it.text.toString().contains("Shahid") })
            assertFalse(screen.inlineVideo.isShown)
            assertTrue(all.any { it.isShown && it.text.toString().contains("المفضلة") || it.isShown && it.text.toString().contains("مجموعات") })
            screen.setReceiverOnline(true)
            screen.setReceiverDetails("192.168.1.2","unit-test-serial")
            screen.setReceiverIdentity("ECHOLINK ZEN 4")
            screen.updateRemoteNow("1","MBC 1 HD","N° 1","Nilesat","11470 MHz",
                "Programme de test","Programme suivant")
            screen.showSignalReady()
            assertFalse(screen.inlineVideo.isShown)
            assertTrue(texts(screen.root).any { it.isShown && it.text.toString() == "MBC 1 HD" })
            assertTrue(texts(screen.root).any { it.isShown && it.text.toString().contains("Programme de test") })
            val remoteTab=texts(screen.root).first { it.text.toString() == "▣\nTélécommande" }
            assertTrue(remoteTab.isShown)
            remoteTab.performClick()
            assertFalse(screen.inlineVideo.isShown)
            val options=texts(screen.root).first { it.text.toString() == "⚙\nOptions" }
            options.performClick()
            assertTrue(texts(screen.root).any{it.isShown && it.text.toString()=="Connexion"})
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }
}
