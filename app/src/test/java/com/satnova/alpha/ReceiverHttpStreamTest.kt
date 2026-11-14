package com.satnova.alpha

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test

class ReceiverHttpStreamTest {
    private class Source(private val response:String):Closeable {
        val server=ServerSocket(0)
        val accepted=CountDownLatch(1)
        val disconnected=CountDownLatch(1)
        @Volatile var client:Socket?=null
        val url="http://127.0.0.1:${server.localPort}/live.ts?channel=real"
        init {
            Thread {
                try {
                    val socket=server.accept();client=socket
                    val input=socket.getInputStream()
                    var tail=""
                    while(!tail.endsWith("\r\n\r\n")){
                        val b=input.read();if(b<0)return@Thread
                        tail=(tail+b.toChar()).takeLast(4)
                    }
                    socket.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                    socket.getOutputStream().flush();accepted.countDown()
                    while(input.read()!=-1){}
                    disconnected.countDown()
                }catch(_:Exception){disconnected.countDown()}
            }.apply{isDaemon=true;start()}
        }
        override fun close(){try{client?.close()}catch(_:Exception){};server.close()}
    }
    private fun reader(url:String):Socket {
        val uri=java.net.URI(url)
        return Socket(uri.host,uri.port).apply{
            soTimeout=2000
            getOutputStream().write("GET /live HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            getOutputStream().flush()
        }
    }
    @Test fun forwardsCurrentBytesWithoutCachingAndClosesReceiverReadImmediately(){
        Source("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n\u0047CURRENT").use{source->
            val reports=CopyOnWriteArrayList<String>()
            val errors=CopyOnWriteArrayList<String>()
            ReceiverHttpStream(source.url,{reports.add(it)},{errors.add(it)}).use{relay->
                reader(relay.start()).use{local->
                    val input=local.getInputStream()
                    val received=StringBuilder()
                    while(!received.endsWith("GCURRENT"))received.append(input.read().toChar())
                    assertTrue(received.contains("HTTP/1.1 200 OK"))
                    assertTrue(source.accepted.await(2,TimeUnit.SECONDS))
                    relay.close()
                    assertTrue("close must cancel the receiver socket",source.disconnected.await(2,TimeUnit.SECONDS))
                    assertEquals(-1,input.read())
                    assertTrue(errors.isEmpty())
                }
            }
        }
    }
    @Test fun reportsHttpFailureRatherThanInfiniteZeroPercent(){
        Source("HTTP/1.1 503 Busy\r\nContent-Length: 0\r\n\r\n").use{source->
            val failed=CountDownLatch(1);var message=""
            ReceiverHttpStream(source.url,{}, {message=it;failed.countDown()},500).use{relay->
                reader(relay.start()).use{
                    assertTrue(failed.await(2,TimeUnit.SECONDS))
                    assertTrue(message.contains("HTTP 503"))
                }
            }
        }
    }
    @Test fun distinguishesHttpSuccessWithNoVideoData(){
        Source("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n").use{source->
            val failed=CountDownLatch(1);var message=""
            ReceiverHttpStream(source.url,{}, {message=it;failed.countDown()},500).use{relay->
                reader(relay.start()).use{
                    assertTrue(failed.await(2,TimeUnit.SECONDS))
                    assertTrue(message.contains("HTTP 200"))
                    assertTrue(message.contains("aucune donnée vidéo"))
                }
            }
        }
    }
    @Test fun closeDuringHeadersDoesNotNeedReceiverReboot(){
        Source("").use{source->
            ReceiverHttpStream(source.url,{},{}).use{relay->
                reader(relay.start()).use{
                    assertTrue(source.accepted.await(2,TimeUnit.SECONDS))
                    relay.close()
                    assertTrue(source.disconnected.await(2,TimeUnit.SECONDS))
                }
            }
        }
    }
}
