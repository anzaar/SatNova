package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class VideoAttemptDiagnosticsTest {
    @Test fun videoEndpointAndRealCountersSurviveAnErrorAndLaterModeMessages(){
        val report=VideoAttemptDiagnostics()
        report.begin("192.168.1.2","Al Aoula • ID 00020013500007","20:18:38")
        report.source("http://192.168.1.2:8080/live.ts?token=PRIVATE")
        report.counters("VLC : 123456 octets reçus • vidéo décodée : 0 • audio décodé : 0")
        report.finish("Erreur lecteur")
        report.state("Mode réglage • vidéo en pause")
        report.counters("Compteurs remis à zéro")
        val saved=report.render()
        assertTrue(saved.contains("http://192.168.1.2:8080"))
        assertTrue(saved.contains("123456"))
        assertTrue(saved.contains("Erreur lecteur"))
        assertFalse(saved.contains("Mode réglage"))
        assertFalse(saved.contains("PRIVATE"))
    }
    @Test fun urlCredentialsAndParametersAreNeverCopied(){
        val report=VideoAttemptDiagnostics()
        report.begin("192.168.1.2","2M","20:00:00")
        report.source("http://user:password@192.168.1.2:80/secret-path.ts?private-token=secret")
        assertFalse(report.render().contains("password"))
        assertFalse(report.render().contains("secret"))
        assertTrue(report.render().contains("Source vidéo : http://192.168.1.2:80"))
    }
    @Test fun aNewAttemptUsesItsOwnChannelRatherThanOldPlaybackState(){
        val report=VideoAttemptDiagnostics()
        report.begin("192.168.1.2","OLD","20:00:00")
        report.source("http://192.168.1.2:80/live-old")
        report.finish("Old failure")
        report.begin("192.168.1.2","NEW","20:10:00")
        assertFalse(report.render().contains("OLD"))
        assertFalse(report.render().contains("Old failure"))
        assertTrue(report.render().contains("En attente du lien 1009"))
        report.source("http://192.168.1.2:8080/live-new")
        assertTrue(report.render().contains(":8080"))
    }
}
