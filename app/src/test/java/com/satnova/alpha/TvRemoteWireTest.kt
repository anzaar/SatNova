package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.MessageDigest

class TvRemoteWireTest {
    @Test fun framedProtobufHandlesTwoByteLengthAndSplitInput() {
        val payload = TvRemoteWire.bytes(10, ByteArray(200) { it.toByte() })
        val stream = ByteArrayOutputStream()
        TvRemoteWire.writeFrame(stream, payload)
        val read = TvRemoteWire.readFrame(ByteArrayInputStream(stream.toByteArray()))
        assertArrayEquals(payload, read)
        assertEquals(200, TvRemoteWire.firstBytes(read, 10)?.size)
    }

    @Test fun nestedFieldsAreReadCorrectly() {
        val nested = TvRemoteWire.join(TvRemoteWire.number(1, 42), TvRemoteWire.string(2, "Lifemaxx"))
        val root = TvRemoteWire.bytes(1, nested)
        assertEquals(42L, TvRemoteWire.firstNumber(TvRemoteWire.firstBytes(root, 1)!!, 1))
        assertEquals("Lifemaxx", String(TvRemoteWire.firstBytes(nested, 2)!!))
    }

    @Test fun pairingChecksumRejectsMistypedCode() {
        val client = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public as RSAPublicKey
        val tv = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public as RSAPublicKey
        val nonce = byteArrayOf(0x12, 0x34)
        fun unsigned(value: java.math.BigInteger): ByteArray = value.toByteArray().let {
            if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
        val md = MessageDigest.getInstance("SHA-256")
        listOf(client, tv).forEach { key ->
            md.update(unsigned(key.modulus))
            md.update(unsigned(key.publicExponent))
        }
        md.update(nonce)
        val digest = md.digest()
        val valid = "%02X1234".format(digest[0].toInt() and 255)
        assertArrayEquals(digest, TvRemoteWire.pairingSecret(client, tv, valid))
        val invalid = (if (valid.startsWith("00")) "01" else "00") + "1234"
        try {
            TvRemoteWire.pairingSecret(client, tv, invalid)
            fail("A code with the wrong checksum must be rejected")
        } catch (_: IllegalArgumentException) {}
    }
}
