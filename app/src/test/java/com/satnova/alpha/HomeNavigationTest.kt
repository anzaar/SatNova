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
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HomeNavigationTest {
    private fun texts(view:View):List<TextView> =
        (if(view is TextView)listOf(view) else emptyList())+
        (if(view is ViewGroup)(0 until view.childCount).flatMap{texts(view.getChildAt(it))} else emptyList())

    @Test fun homeSignalWatchAndOptionsHaveIndependentContent(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        activity.getSharedPreferences("satnova",0).edit().clear().commit()
        lateinit var screen:SatNovaScreen
        val restarts=mutableListOf<String>()
        var ready=false
        screen=SatNovaScreen(activity,
            onDiscover={},onAdvancedScan={},onSelectReceiver={},onDisconnect={},onConnect={},
            onWatch={},onRefresh={},onReadSatellites={},onReadTransponders={},onReadSignal={},
            onWatchMode={},onSignalMode={},onRestart={restarts.add(it)},
            onServers={},onNetwork={},onScan={},onChannelTune={_,_->},onChannelRefresh={},
            onEpgRemote={},onTabChanged={tab->
                if(ready)when(tab){
                    0->screen.setUseMode("signal")
                    2->screen.setUseMode("watch")
                    else->screen.setUseMode("none")
                }
            },onKey={})
        ready=true
        try {
            activity.setContentView(screen.root)
            fun text(label:String)=texts(screen.root).first{it.text.toString()==label}
            screen.setReceiverOnline(false)
            assertTrue(text("◎  Satellite • caméra / GPS").isShown)
            assertTrue(text("☪  القبلة • Direction Qibla").isShown)
            assertFalse(texts(screen.root).any{
                it.text.toString().contains("Niveau et inclinomètre") ||
                    it.text.toString().contains("Mesurer une distance")
            })
            assertFalse(text("CH +").isShown)
            screen.setReceiverOnline(true)
            screen.showSignalReady()
            screen.setReceiverIdentity("ECHOLINK ZEN 4")
            screen.setReceiverDetails("192.168.1.2","240117402977")
            assertFalse(screen.inlineVideo.isShown)
            assertFalse(text("CH +").isShown)
            assertTrue(text("▷\nRegarder").isShown)
            assertTrue(texts(screen.root).any{it.isShown && it.text.toString().contains("IP : 192.168.1.2") && it.text.toString().contains("Série : 240117402977")})
            text("▷\nRegarder").performClick()
            assertTrue(screen.inlineVideo.isShown)
            assertTrue(text("CH +").isShown)
            text("Liste").performClick()
            assertFalse(text("CH +").isShown)
            assertTrue(text("Tous les satellites ▾").isShown)
            text("Télécommande").performClick()
            assertTrue(text("CH +").isShown)
            text("⚙\nOptions").performClick()
            // Native surface remains attached until its asynchronous stop callback completes.
            assertTrue(screen.inlineVideo.isShown)
            screen.showBrowseReady()
            assertFalse(screen.inlineVideo.isShown)
            assertTrue(text("Connexion").isShown)
            assertTrue(text("⌄   Informations techniques").isShown)
            screen.fields["Dernier diagnostic vidéo"]?.text="FAILED VIDEO • source 192.168.1.2:8080 • 1234 octets"
            screen.showInlineState("Mode réglage • vidéo en pause")
            text("⌄   Informations techniques").performClick()
            text("Copier le diagnostic vidéo").performClick()
            val clipboard=activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("FAILED VIDEO • source 192.168.1.2:8080 • 1234 octets",clipboard.primaryClip?.getItemAt(0)?.text.toString())
            assertFalse(text("CH +").isShown)
            assertTrue(texts(screen.root).none{it.text.toString() in listOf("État du récepteur","Récepteur & installation")})
            listOf("device","application","all").forEachIndexed{i,target->
                text("⟳  Redémarrer").performClick()
                val dialog=ShadowAlertDialog.getLatestAlertDialog()
                dialog.listView.performItemClick(null,i,i.toLong())
                assertEquals(target,restarts.last())
            }
            text("⌂\nAccueil").performClick()
            screen.showSignalReady()
            assertFalse(screen.inlineVideo.isShown)
            assertFalse(text("CH +").isShown)
            assertTrue(text("◈\nSatellites").isShown)
            activity.window.decorView.measure(
                View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY))
            activity.window.decorView.layout(0,0,480,800)
            fun gauges(v:View):List<SignalGaugeView> =
                (if(v is SignalGaugeView)listOf(v) else emptyList())+
                (if(v is ViewGroup)(0 until v.childCount).flatMap{gauges(v.getChildAt(it))} else emptyList())
            assertTrue(gauges(screen.root).all{it.isShown && it.height>100 && it.width>100})
        } finally {screen.close();controller.pause().stop().destroy()}
    }
}
