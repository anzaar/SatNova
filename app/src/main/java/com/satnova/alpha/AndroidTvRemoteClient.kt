package com.satnova.alpha

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.KeyEvent
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Secure Wi-Fi TV remote. Does not reference or require a satellite receiver session.
 * 6467 = code pairing; 6466 = paired TLS remote channel. Local addresses only.
 */
internal class AndroidTvRemoteClient(
    context: Context,
    private val status: (String) -> Unit,
    private val enterCode: () -> Unit,
    private val readyChanged: (Boolean) -> Unit
) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("satnova_tv_remote", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor()
    private val writer = Executors.newSingleThreadExecutor()
    private val codeQueue = LinkedBlockingQueue<String>(1)
    @Volatile private var active: SSLSocket? = null
    @Volatile private var ready = false
    @Volatile private var appLinksEnabled = false
    @Volatile private var disposed = false

    private fun notify(message: String) { main.post { if (!disposed) status(message) } }
    private fun setReady(value: Boolean) {
        ready = value
        if (!value) appLinksEnabled = false
        main.post { if (!disposed) readyChanged(value) }
    }

    fun isPaired(host: String) = preferences.contains("tv_cert_$host")
    fun lastHost(): String = preferences.getString("tv_host", "") ?: ""

    fun submitCode(code: String) {
        if (!codeQueue.offer(code.trim().uppercase(java.util.Locale.ROOT))) {
            codeQueue.clear()
            codeQueue.offer(code.trim().uppercase(java.util.Locale.ROOT))
        }
    }

    fun pair(host: String) = open(host, true)
    fun connect(host: String) = open(host, false)

    private fun open(host: String, pairing: Boolean) {
        if (disposed) return
        // Never treat satellite receiver IP/state as TV discovery or authentication.
        active?.closeQuietly()
        codeQueue.clear()
        setReady(false)
        reader.execute {
            try {
                if (pairing) doPair(host)
                doRemote(host)
            } catch (e: Exception) {
                if (!disposed) notify("Télécommande TV : " + (e.message ?: "connexion impossible"))
            } finally {
                active?.closeQuietly()
                active = null
                setReady(false)
            }
        }
    }

    private fun keyMaterial(): Pair<PrivateKey, X509Certificate> {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val alias = "satnova_tv_remote_rsa"
        if (!store.containsAlias(alias)) {
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=SatNova-" + UUID.randomUUID().toString()))
                .setCertificateSerialNumber(java.math.BigInteger.valueOf(now.coerceAtLeast(1L)))
                .setCertificateNotBefore(Date(now - 3600000L))
                .setCertificateNotAfter(Date(now + 315360000000L))
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        return (store.getKey(alias, null) as PrivateKey) to (store.getCertificate(alias) as X509Certificate)
    }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Pairing trusts the visible on-TV PIN; connected sessions trust the pinned certificate. */
    private fun secureSocket(host: String, port: Int, pairing: Boolean): SSLSocket {
        val (key, cert) = keyMaterial()
        val manager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf("satnova")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = "satnova"
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == "satnova") arrayOf(cert) else null
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == "satnova") key else null
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = "satnova"
        }
        val trusted = if (pairing) null else (preferences.getString("tv_cert_$host", null)
            ?: throw IOException("Téléviseur non associé. Appuyez sur Associer."))
        val verifier = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val received = chain?.firstOrNull() ?: throw CertificateException("Certificat TV absent")
                if (trusted != null && fingerprint(received) != trusted)
                    throw CertificateException("Identité TV modifiée : nouvelle association nécessaire")
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(arrayOf(manager), arrayOf(verifier), java.security.SecureRandom())
        }
        val tcp = Socket()
        try {
            tcp.connect(InetSocketAddress(host, port), 5000)
            tcp.soTimeout = 20000
            val socket = ssl.socketFactory.createSocket(tcp, host, port, true) as SSLSocket
            socket.soTimeout = 20000
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            tcp.closeQuietly()
            throw e
        }
    }

    private fun exchange(socket: SSLSocket, field: Int, message: ByteArray) {
        val payload = TvRemoteWire.join(TvRemoteWire.number(1, 2), TvRemoteWire.number(2, 200),
            TvRemoteWire.bytes(field, message))
        synchronized(socket) { TvRemoteWire.writeFrame(socket.outputStream, payload) }
    }

    private fun requirePairReply(socket: SSLSocket, expected: Int) {
        val reply = TvRemoteWire.readFrame(socket.inputStream)
        val fields = TvRemoteWire.fields(reply)
        val statusCode = fields[2]?.firstOrNull()?.number
        if (statusCode != null && statusCode != 200L)
            throw IOException("Association refusée (code $statusCode)")
        if (fields[expected].isNullOrEmpty())
            throw IOException("Réponse d'association inattendue")
    }

    private fun doPair(host: String) {
        notify("Connexion à $host • association Android TV…")
        val socket = secureSocket(host, 6467, true)
        active = socket
        try {
            val request = TvRemoteWire.join(TvRemoteWire.string(1, "SatNova TV Remote"),
                TvRemoteWire.string(2, "SatNova"))
            exchange(socket, 10, request)
            requirePairReply(socket, 11)

            val encoding = TvRemoteWire.join(TvRemoteWire.number(1, 3), TvRemoteWire.number(2, 6))
            exchange(socket, 20, TvRemoteWire.join(TvRemoteWire.bytes(1, encoding), TvRemoteWire.number(3, 1)))
            requirePairReply(socket, 20)

            exchange(socket, 30, TvRemoteWire.join(TvRemoteWire.bytes(1, encoding), TvRemoteWire.number(2, 1)))
            requirePairReply(socket, 31)

            notify("Saisissez le code affiché sur Lifemaxx.")
            main.post { if (!disposed) enterCode() }
            val code = codeQueue.poll(120, TimeUnit.SECONDS) ?: throw IOException("Délai du code TV dépassé")
            val own = keyMaterial().second.publicKey as RSAPublicKey
            val server = socket.session.peerCertificates[0].publicKey as? RSAPublicKey
                ?: throw IOException("Certificat du téléviseur non RSA")
            val secret = TvRemoteWire.pairingSecret(own, server, code)
            exchange(socket, 40, TvRemoteWire.bytes(1, secret))
            requirePairReply(socket, 41)

            preferences.edit().putString("tv_cert_$host",
                fingerprint(socket.session.peerCertificates[0] as X509Certificate))
                .putString("tv_host", host).apply()
            notify("Lifemaxx associée • ouverture de la télécommande…")
        } finally {
            socket.closeQuietly()
            if (active === socket) active = null
        }
    }

    private fun remoteMessage(socket: SSLSocket, type: Int, payload: ByteArray) {
        synchronized(socket) { TvRemoteWire.writeFrame(socket.outputStream, TvRemoteWire.bytes(type, payload)) }
    }

    private fun doRemote(host: String) {
        notify("Connexion télécommande TV : $host…")
        val socket = secureSocket(host, 6466, false)
        active = socket
        var features = 0L
        try {
            while (!disposed && active === socket) {
                val input = TvRemoteWire.fields(TvRemoteWire.readFrame(socket.inputStream))
                when {
                    input[1]?.firstOrNull()?.bytes != null -> {
                        val offer = input[1]!!.first().bytes!!
                        val offeredFeatures = TvRemoteWire.firstNumber(offer, 1) ?: 0L
                        features = offeredFeatures and (1L or 2L or 32L or 64L or 512L)
                        appLinksEnabled = (features and 512L) != 0L
                        if ((features and 2L) == 0L) throw IOException("La TV n'accepte pas les touches")
                        val info = TvRemoteWire.join(
                            TvRemoteWire.string(1, "SatNova"), TvRemoteWire.string(2, "SatNova"),
                            TvRemoteWire.number(3, 1), TvRemoteWire.string(4, Build.VERSION.RELEASE),
                            TvRemoteWire.string(5, "SatNova"), TvRemoteWire.string(6, "2.0"))
                        remoteMessage(socket, 1, TvRemoteWire.join(TvRemoteWire.number(1, features),
                            TvRemoteWire.bytes(2, info)))
                    }
                    input[2]?.firstOrNull()?.bytes != null -> {
                        remoteMessage(socket, 2, TvRemoteWire.number(1, features))
                        setReady(true)
                        notify("● Lifemaxx connectée • télécommande prête")
                    }
                    input[8]?.firstOrNull()?.bytes != null -> {
                        val ping = input[8]!!.first().bytes!!
                        val sequence = TvRemoteWire.firstNumber(ping, 1) ?: 0L
                        remoteMessage(socket, 9, TvRemoteWire.number(1, sequence))
                    }
                    input[3]?.firstOrNull()?.bytes != null -> {
                        val error = TvRemoteWire.firstNumber(input[3]!!.first().bytes!!, 1)
                        throw IOException("Commande refusée par la TV (code $error)")
                    }
                }
            }
        } finally {
            if (active === socket) active = null
            setReady(false)
            socket.closeQuietly()
        }
    }

    fun press(keyCode: Int) {
        if (!ready) { notify("Connectez d'abord la télévision."); return }
        writer.execute {
            val socket = active
            if (socket != null && ready) try {
                remoteMessage(socket, 10, TvRemoteWire.join(
                    TvRemoteWire.number(1, keyCode.toLong()), TvRemoteWire.number(2, 3)))
            } catch (e: Exception) {
                notify("Commande TV échouée : " + (e.message ?: "connexion interrompue"))
                socket.closeQuietly()
            }
        }
    }

    /** Remote v2 protobuf field 90: app-link request, not a raw Android package launch. */
    fun launchApp(link: String) {
        val value = link.trim()
        if (value.isEmpty() || value.length > 1800 || !value.contains("://")) {
            notify("Raccourci TV : lien d'application invalide.")
            return
        }
        if (!ready) { notify("Connectez d'abord la télévision."); return }
        if (!appLinksEnabled) {
            notify("Lifemaxx ne propose pas le lancement direct d'applications via ce protocole.")
            return
        }
        writer.execute {
            val socket = active
            if (socket != null && ready) try {
                val playStoreLink = value.startsWith("https://play.google.com/apps/launch?id=")
                val request = if (playStoreLink) TvRemoteWire.join(
                    TvRemoteWire.string(1, value),
                    TvRemoteWire.string(2, "com.android.vending"),
                    TvRemoteWire.number(3, 1L))
                else TvRemoteWire.string(1, value)
                remoteMessage(socket, 90, request)
                notify("Commande d'ouverture transmise à Lifemaxx.")
            } catch (e: Exception) {
                notify("Raccourci TV : " + (e.message ?: "connexion interrompue"))
                socket.closeQuietly()
            }
        }
    }

    fun isReady(): Boolean = ready

    fun disconnect() {
        active?.closeQuietly()
        active = null
        setReady(false)
    }

    fun close() {
        disposed = true
        active?.closeQuietly()
        active = null
        codeQueue.offer("")
        reader.shutdownNow()
        writer.shutdownNow()
    }

    private fun Socket.closeQuietly() { try { close() } catch (_: Exception) {} }
}
