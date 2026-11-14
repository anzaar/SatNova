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
            onServers={},onNetwork={},onScan={},onChannelTune={_,_->},onChannelRefresh={},
            onEpgRemote={},onTabChanged={},onKey={})
        try {
            activity.setContentView(screen.root)
            screen.setReceiverOnline(true)
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
}
