package com.satnova.alpha

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

/** One upstream receiver reader. Measures and forwards the same bytes to VLC; never probes in parallel. */
class ReceiverHttpRelay(private val source:String,private val report:(String)->Unit):Closeable {
    private val uri=URI(source)
    private val token=UUID.randomUUID().toString()
    private val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
    @Volatile private var closed=false
    @Volatile private var upstream:Socket?=null
    @Volatile private var downstream:Socket?=null
    val localUrl="http://127.0.0.1:${server.localPort}/$token"
    init {
        require(uri.scheme.equals("http",true))
        require(uri.host!=null)
        server.soTimeout=1000
        Thread({serve()},"satnova-live-relay").apply{isDaemon=true;start()}
    }
    private fun header(input:InputStream):ByteArray {
        val bytes=ByteArrayOutputStream()
        var match=0
        val end=byteArrayOf(13,10,13,10)
        while(bytes.size()<32768){
            val b=input.read()
            if(b<0)throw java.io.EOFException("HTTP header closed")
            bytes.write(b)
            match=if(b==end[match].toInt())match+1 else if(b==13)1 else 0
            if(match==4)return bytes.toByteArray()
        }
        throw java.io.IOException("HTTP header too large")
    }
    private fun serve(){
        try{
            while(!closed){
                val client=try{server.accept()}catch(_:SocketTimeoutException){continue}
                downstream=client
                client.use{
                    client.soTimeout=7000
                    var responseStarted=false
                    try{
                        val request=String(header(client.getInputStream()),StandardCharsets.ISO_8859_1)
                        if(!request.startsWith("GET /$token ")){
                            client.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return@use
                        }
                        val remote=Socket()
                        upstream=remote
                        remote.use{
                            report("HTTP • connexion au service vidéo du récepteur…")
                            remote.connect(InetSocketAddress(uri.host,if(uri.port<0)80 else uri.port),5000)
                            remote.soTimeout=7000
                            val path=(uri.rawPath?.takeIf{it.isNotEmpty()}?:"/")+(uri.rawQuery?.let{"?$it"}?:"")
                            val host=uri.host+(if(uri.port<0)"" else ":${uri.port}")
                            val get="GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: SatNova-Live/1.13\r\nAccept: */*\r\nConnection: close\r\n\r\n"
                            remote.getOutputStream().write(get.toByteArray(StandardCharsets.UTF_8))
                            remote.getOutputStream().flush()
                            val received=header(remote.getInputStream())
                            val lines=String(received,StandardCharsets.ISO_8859_1).split("\r\n")
                            val code=lines.first().split(' ').getOrNull(1)?.toIntOrNull()?:0
                            val type=lines.firstOrNull{it.startsWith("Content-Type:",true)}?.substringAfter(':')?.trim()?:"type non fourni"
                            // VLC follows redirects itself; prohibit an unverified second upstream route.
                            if(code in 300..399){
                                report("HTTP $code • redirection du service vidéo à examiner")
                                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                responseStarted=true
                                return@use
                            }
                            val forwarded=(lines.filter{it.isNotBlank() && !it.startsWith("Connection:",true) &&
                                !it.startsWith("Keep-Alive:",true)}+"Connection: close").joinToString("\r\n")+"\r\n\r\n"
                            val out=client.getOutputStream()
                            out.write(forwarded.toByteArray(StandardCharsets.ISO_8859_1));out.flush()
                            responseStarted=true
                            report("HTTP $code • $type • 0 octet vidéo reçu")
                            remote.soTimeout=2000
                            var total=0L
                            var lastNotice=System.nanoTime()
                            val buffer=ByteArray(32768)
                            while(!closed){
                                val count=try{remote.getInputStream().read(buffer)}catch(_:SocketTimeoutException){
                                    report("HTTP $code • $type • $total octets reçus • attente de données")
                                    continue
                                }
                                if(count<0)break
                                out.write(buffer,0,count);out.flush()
                                total+=count
                                val now=System.nanoTime()
                                if(total==count.toLong() || now-lastNotice>=1000000000L){
                                    report("HTTP $code • $type • $total octets reçus")
                                    lastNotice=now
                                }
                            }
                            if(!closed)report("HTTP $code • fin du flux après $total octets")
                        }
                    }catch(e:Exception){
                        if(!closed){
                            report("HTTP vidéo • "+when(e){
                                is SocketTimeoutException->"le service vidéo ne répond pas"
                                is java.net.ConnectException->"connexion au service vidéo refusée"
                                else->"connexion vidéo interrompue"
                            })
                            if(!responseStarted)try{client.getOutputStream().write(
                                "HTTP/1.1 504 Gateway Timeout\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())}catch(_:Exception){}
                        }
                    }finally{try{upstream?.close()}catch(_:Exception){};upstream=null}
                }
                downstream=null
            }
        }catch(_:Exception){}finally{close()}
    }
    override fun close(){
        closed=true
        try{upstream?.close()}catch(_:Exception){}
        try{downstream?.close()}catch(_:Exception){}
        try{server.close()}catch(_:Exception){}
    }
}
