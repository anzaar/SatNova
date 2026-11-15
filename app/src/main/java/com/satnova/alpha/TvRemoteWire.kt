package com.satnova.alpha

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/** Minimal protobuf wire codec for the local Android TV Remote v2 protocol. */
internal object TvRemoteWire {
    data class Value(val number: Long? = null, val bytes: ByteArray? = null)

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var n = value
        while ((n and -128L) != 0L) {
            out.write(((n and 127L) or 128L).toInt())
            n = n ushr 7
        }
        out.write(n.toInt())
    }

    fun number(field: Int, value: Long): ByteArray {
        require(field > 0)
        return ByteArrayOutputStream().apply {
            writeVarint(this, (field.toLong() shl 3))
            writeVarint(this, value)
        }.toByteArray()
    }

    fun bytes(field: Int, data: ByteArray): ByteArray {
        require(field > 0)
        return ByteArrayOutputStream().apply {
            writeVarint(this, (field.toLong() shl 3) or 2L)
            writeVarint(this, data.size.toLong())
            write(data)
        }.toByteArray()
    }

    fun string(field: Int, text: String): ByteArray = bytes(field, text.toByteArray(Charsets.UTF_8))

    fun join(vararg messages: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { messages.forEach { write(it) } }.toByteArray()

    private fun readUnsigned(data: ByteArray, at: Int): Pair<Long, Int> {
        var result = 0L
        var pos = at
        for (shift in 0..63 step 7) {
            if (pos >= data.size) throw EOFException("Truncated varint")
            val b = data[pos++].toInt() and 255
            result = result or ((b and 127).toLong() shl shift)
            if ((b and 128) == 0) return result to pos
        }
        throw IllegalArgumentException("Varint too long")
    }

    fun fields(data: ByteArray): Map<Int, List<Value>> {
        val result = mutableMapOf<Int, MutableList<Value>>()
        var at = 0
        while (at < data.size) {
            val (tag, next) = readUnsigned(data, at)
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            if (field <= 0) throw IllegalArgumentException("Invalid field")
            at = next
            val value = when (wire) {
                0 -> {
                    val (number, end) = readUnsigned(data, at)
                    at = end
                    Value(number = number)
                }
                2 -> {
                    val (length, begin) = readUnsigned(data, at)
                    if (length > data.size - begin || length < 0) throw EOFException("Invalid field size")
                    at = begin + length.toInt()
                    Value(bytes = data.copyOfRange(begin, at))
                }
                1 -> {
                    if (at + 8 > data.size) throw EOFException("Truncated 64-bit value")
                    at += 8
                    Value()
                }
                5 -> {
                    if (at + 4 > data.size) throw EOFException("Truncated 32-bit value")
                    at += 4
                    Value()
                }
                else -> throw IllegalArgumentException("Unsupported wire type")
            }
            result.getOrPut(field) { mutableListOf() }.add(value)
        }
        return result
    }

    fun firstBytes(data: ByteArray, field: Int): ByteArray? = fields(data)[field]?.firstOrNull()?.bytes
    fun firstNumber(data: ByteArray, field: Int): Long? = fields(data)[field]?.firstOrNull()?.number

    fun readFrame(input: InputStream): ByteArray {
        var length = 0
        var shift = 0
        while (shift < 35) {
            val octet = input.read()
            if (octet < 0) throw EOFException("TV disconnected")
            length = length or ((octet and 127) shl shift)
            if ((octet and 128) == 0) {
                if (length < 0 || length > 131072) throw IllegalArgumentException("TV message too large")
                val payload = ByteArray(length)
                var received = 0
                while (received < length) {
                    val count = input.read(payload, received, length - received)
                    if (count < 0) throw EOFException("TV message truncated")
                    received += count
                }
                return payload
            }
            shift += 7
        }
        throw IllegalArgumentException("TV frame prefix too large")
    }

    fun writeFrame(output: OutputStream, payload: ByteArray) {
        require(payload.size <= 131072)
        val frame = ByteArrayOutputStream()
        writeVarint(frame, payload.size.toLong())
        frame.write(payload)
        output.write(frame.toByteArray())
        output.flush()
    }

    private fun unsigned(value: java.math.BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    /** The first byte in the six-hex-character on-screen code is a checksum. */
    fun pairingSecret(client: RSAPublicKey, tv: RSAPublicKey, entered: String): ByteArray {
        val code = entered.trim().uppercase(java.util.Locale.ROOT)
        require(Regex("[0-9A-F]{6}").matches(code)) { "Le code doit contenir 6 caractères hexadécimaux." }
        val nonce = byteArrayOf(code.substring(2, 4).toInt(16).toByte(), code.substring(4, 6).toInt(16).toByte())
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(client, tv).forEach { key ->
            digest.update(unsigned(key.modulus))
            digest.update(unsigned(key.publicExponent))
        }
        digest.update(nonce)
        return digest.digest().also {
            require((it[0].toInt() and 255) == code.substring(0, 2).toInt(16)) {
                "Code incorrect. Vérifiez les caractères sur le téléviseur."
            }
        }
    }
}
