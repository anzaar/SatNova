package com.satnova.alpha

import java.net.URI

/** Video evidence belongs to its attempt, not to the page currently on screen. */
class VideoAttemptDiagnostics {
    private var header=""
    private var endpoint="En attente du lien 1009"
    private var stage=""
    private var counts="Compteurs VLC : en attente"
    private var frozen=false
    @Synchronized fun begin(receiver:String,channel:String,at:String){
        header="SatNova 1.19 • $at\nRécepteur : $receiver\nChaîne : $channel"
        endpoint="En attente du lien 1009";stage="Demande de lecture en cours";counts="Compteurs VLC : en attente";frozen=false
    }
    @Synchronized fun source(url:String){
        if(header.isEmpty() || frozen)return
        endpoint=try{
            val u=URI(url)
            val port=if(u.port>=0)u.port else if(u.scheme=="rtsp")554 else if(u.scheme=="https")443 else 80
            "Source vidéo : ${u.scheme}://${u.host}:$port"
        }catch(_:Exception){"Source vidéo non reconnue"}
    }
    @Synchronized fun state(value:String){if(header.isNotEmpty() && !frozen)stage=value}
    @Synchronized fun counters(value:String){if(header.isNotEmpty() && !frozen)counts=value}
    @Synchronized fun finish(reason:String){if(header.isNotEmpty() && !frozen){stage=reason;frozen=true}}
    @Synchronized fun render():String=if(header.isEmpty())"" else "$header\n$endpoint\nLecture directe VLC • référence 1.10\n$stage\n$counts"
}
