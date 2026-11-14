package com.satnova.alpha

import java.net.URI

/** Like G-MScreen LivePlayActivity.D0: use the connected receiver, not a stored NIC IP. */
object ReceiverStreamUrl {
    fun resolve(raw:String,connectedHost:String):String {
        val uri=URI(raw.trim())
        require(uri.scheme?.lowercase() in listOf("http","https","rtsp") && uri.host!=null)
        require(connectedHost.split('.').let{p->p.size==4 && p.all{it.toIntOrNull() in 0..255}})
        val auth=uri.rawUserInfo?.let{"$it@"}?:""
        val port=if(uri.port>=0)":"+uri.port else ""
        val path=uri.rawPath?.takeIf{it.isNotEmpty()}?:"/"
        return "${uri.scheme}://$auth$connectedHost$port$path"+(uri.rawQuery?.let{"?$it"}?:"")
    }
}
