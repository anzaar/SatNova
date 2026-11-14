package com.satnova.alpha

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ReceiverHttpRelayTest {
    private fun readHeader(input:InputStream):String {
        val out=ByteArrayOutputStream()
        while(out.size()<32768){
            val b=input.read();if(b<0)break
            out.write(b)
            if(out.toString("ISO-8859-1").endsWith("\r\n\r\n"))break
        }
        return out.toString("ISO-8859-1")
    }
    private fun client(relay:ReceiverHttpRelay):Socket {
        val url=URI(relay.localUrl)
        val socket=Socket("127.0.0.1",url.port);socket.soTimeout=3000
        socket.getOutputStream().write("GET ${url.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
        return socket
    }
    @Test fun forwardsSameStreamBytesWithOneReceiverReaderAndPreservesQuery(){
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use{receiver->
            val count=AtomicInteger();val gotRequest=CountDownLatch(1)
            var request=""
            val body=ByteArray(376){(it%256).toByte()}
            Thread{
                receiver.accept().use{s->
                    count.incrementAndGet();request=readHeader(s.getInputStream());gotRequest.countDown()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()+body)
                }
            }.apply{isDaemon=true;start()}
            val reports=Collections.synchronizedList(mutableListOf<String>())
            ReceiverHttpRelay("http://127.0.0.1:${receiver.localPort}/live?channel=A%2FB",reports::add).use{relay->
                client(relay).use{socket->
                    assertTrue(readHeader(socket.getInputStream()).startsWith("HTTP/1.1 200"))
                    assertArrayEquals(body,socket.getInputStream().readBytes())
                }
                assertTrue(gotRequest.await(2,TimeUnit.SECONDS))
                assertTrue(request.startsWith("GET /live?channel=A%2FB HTTP/1.1"))
                assertFalse(request.contains("Range:"))
                assertEquals(1,count.get())
                assertTrue(reports.any{it.contains("HTTP 200") && it.contains("376")})
            }
        }
    }
    @Test fun preservesHttpErrorsInsteadOfReportingSuccessfulPlayback(){
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use{receiver->
            Thread{receiver.accept().use{s->readHeader(s.getInputStream());s.getOutputStream().write(
                "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray())}}.apply{isDaemon=true;start()}
            ReceiverHttpRelay("http://127.0.0.1:${receiver.localPort}/live",{}).use{relay->
                client(relay).use{s->assertTrue(readHeader(s.getInputStream()).startsWith("HTTP/1.1 503"))}
            }
        }
    }
    @Test fun closingStalledRelayReleasesBothVideoSockets(){
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use{receiver->
            val released=CountDownLatch(1)
            Thread{receiver.accept().use{s->
                readHeader(s.getInputStream());s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                s.soTimeout=3000
                if(s.getInputStream().read()==-1)released.countDown()
            }}.apply{isDaemon=true;start()}
            val relay=ReceiverHttpRelay("http://127.0.0.1:${receiver.localPort}/live",{})
            try{client(relay).use{s->
                assertTrue(readHeader(s.getInputStream()).startsWith("HTTP/1.1 200"))
                relay.close()
                assertEquals(-1,s.getInputStream().read())
                assertTrue(released.await(2,TimeUnit.SECONDS))
            }}finally{relay.close()}
        }
    }
}
