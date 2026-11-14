package com.satnova.alpha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReceiverExecutorTest {
    @Test fun remotePressesBypassQueuedTelemetryAndKeepTheirOrder(){
        val executor=ReceiverExecutor()
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val result=Collections.synchronizedList(mutableListOf<String>())
        try {
            executor.execute{entered.countDown();release.await(3,TimeUnit.SECONDS)}
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            executor.execute{result.add("telemetry")}
            executor.executeRemote{result.add("up")}
            executor.executeRemote{result.add("ok")}
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS))
            assertEquals(listOf("up","ok","telemetry"),result)
        } finally {release.countDown();executor.shutdownNow()}
    }
}
