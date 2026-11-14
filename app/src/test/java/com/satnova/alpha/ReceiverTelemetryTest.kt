package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class ReceiverTelemetryTest {
    @Test fun readsActual403ValuesIncludingZero(){
        val sample=ReceiverTelemetry.signal("""[{"strength":96,"quality":0}]""")!!
        assertEquals(96,sample.strength)
        assertEquals(0,sample.quality)
    }
    @Test fun acceptsObjectAndNestedArrayWithPercentStrings(){
        val sample=ReceiverTelemetry.signal("""{"array":[{"SignalStrength":"82%","SignalQuality":"64","BER":"0"}]}""")!!
        assertEquals(82,sample.strength)
        assertEquals(64,sample.quality)
        assertEquals("0",sample.ber)
    }
    @Test fun doesNotInventPercentagesFromSnrOrUnsupportedScales(){
        assertNull(ReceiverTelemetry.signal("""[{"SNR":12.5,"strength":65535,"quality":-1}]"""))
        assertNull(ReceiverTelemetry.signal("""[{"Command":""}]"""))
        assertNull(ReceiverTelemetry.signal("""[{"strength":"NaN","quality":"Infinity"}]"""))
        assertNull(ReceiverTelemetry.signal("not JSON"))
    }
}
