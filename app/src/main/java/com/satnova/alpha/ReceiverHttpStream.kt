package com.satnova.alpha

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

/** One receiver GET, relayed to VLC. Closing raw sockets cancels a stalled read immediately. */
class ReceiverHttpStream(
    private val url:String,
    private val report:(String)->Unit,
    private val failure:(String)->Unit,
    private val timeoutMillis:Int=6000
):Closeable {
    private val closed=AtomicBoolean(false)
    private val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
    @Volatile private var downstream:Socket?=null
    @Volatile private var upstream:Socket?=null
    val localUrl="http://127.0.0.1:${server.localPort}/live"
    fun start():String {
        Thread({serve()},"SatNova-video-transport").apply{isDaemon=true;start()}
        return localUrl
    }
    private fun header(input:InputStream):ByteArray {
        val bytes=ByteArrayOutputStream()
        var ending=0
        while(bytes.size()<32768){
            val next=input.read()
            check(next>=0){"HTTP fermé avant les en-têtes"}
            bytes.write(next)
            ending=when {
                ending==0 && next==13->1
                ending==1 && next==10->2
                ending==2 && next==13->3
                ending==3 && next==10->return bytes.toByteArray()
                next==13->1
                else->0
            }
        }
        error("En-têtes HTTP trop longs")
    }
    private fun serve(){
        var httpStatus:Int?=null
        var received=0L
        try {
            server.soTimeout=timeoutMillis
            val local=server.accept()
            downstream=local
            if(closed.get())return
            local.soTimeout=timeoutMillis
            header(local.getInputStream())
            val uri=URI(url)
            val port=if(uri.port>=0)uri.port else if(uri.scheme=="https")443 else 80
            val remote=Socket()
            upstream=remote
            if(closed.get())return
            remote.connect(InetSocketAddress(uri.host,port),timeoutMillis)
            remote.soTimeout=timeoutMillis
            val inputSocket=if(uri.scheme=="https"){
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(remote,uri.host,port,true).also{upstream=it;it.soTimeout=timeoutMillis}
            }else remote
            if(closed.get())return
            val path=(uri.rawPath?.takeIf{it.isNotEmpty()}?:"/")+(uri.rawQuery?.let{"?$it"}?:"")
            val request="GET $path HTTP/1.1\r\nHost: ${uri.host}:$port\r\n"+
                "User-Agent: VLC/3.0 LibVLC/3.0\r\nAccept: */*\r\nConnection: close\r\n\r\n"
            inputSocket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            inputSocket.getOutputStream().flush()
            val input=inputSocket.getInputStream()
            val headers=header(input)
            val statusLine=headers.toString(Charsets.ISO_8859_1).substringBefore("\r\n")
            httpStatus=statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            check(httpStatus!=null && httpStatus in 200..299){"Service vidéo : HTTP ${httpStatus?:"invalide"}"}
            // Preserve chunked framing/content headers exactly; never decode or cache the stream.
            val output=local.getOutputStream()
            output.write(headers);output.flush()
            report("HTTP $httpStatus • en attente des données vidéo")
            val buffer=ByteArray(32768)
            var lastReport=0L
            while(!closed.get()){
                val count=input.read(buffer)
                if(count<0)break
                if(count==0)continue
                output.write(buffer,0,count);output.flush()
                received+=count
                val now=System.nanoTime()/1_000_000L
                if(lastReport==0L || now-lastReport>=1000L){
                    lastReport=now
                    report("HTTP $httpStatus • flux reçu : ${received/1024} Ko")
                }
            }
            if(!closed.get())error(if(received==0L)"HTTP $httpStatus • flux vide" else "Flux fermé après ${received/1024} Ko")
        }catch(e:Exception){
            if(!closed.get()){
                val reason=when(e){
                    is java.net.SocketTimeoutException->if(httpStatus!=null)
                        "HTTP $httpStatus • aucune donnée vidéo depuis ${timeoutMillis/1000} s (${received/1024} Ko reçus)"
                        else "Service vidéo sans réponse après ${timeoutMillis/1000} s"
                    else->e.message?:e.javaClass.simpleName
                }
                report(reason);failure(reason)
            }
        }finally{close()}
    }
    override fun close(){
        closed.set(true)
        try{upstream?.close()}catch(_:Exception){}
        try{downstream?.close()}catch(_:Exception){}
        try{server.close()}catch(_:Exception){}
    }
}
