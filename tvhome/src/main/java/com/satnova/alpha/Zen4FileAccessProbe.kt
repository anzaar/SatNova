package com.satnova.alpha

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Read-only network discovery of possible file-sharing services on the single,
 * already-authenticated Zen 4 LAN IP. This does not access/export receiver
 * storage and does not send G-MScreen/CHMAX commands.
 */
internal object Zen4FileAccessProbe {
    private data class Service(val port: Int, val name: String, val purpose: String)
    private val services = listOf(
        Service(21, "FTP", "Transfert de fichiers"),
        Service(22, "SFTP / SSH", "Accès fichiers sécurisé éventuel"),
        Service(80, "HTTP", "Interface web éventuelle"),
        Service(443, "HTTPS", "Interface web sécurisée éventuelle"),
        Service(445, "SMB", "Partage de fichiers réseau"),
        Service(8080, "HTTP alternatif", "Interface web éventuelle"),
        Service(8200, "DLNA", "Bibliothèque multimédia éventuelle"),
        Service(8899, "Service web alternatif", "Service réseau à identifier")
    )

    fun inspect(receiverIp: String): String {
        require(isPrivateIpv4(receiverIp)) { "Adresse du récepteur non autorisée" }

        val open = mutableListOf<Service>()
        for (service in services) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(receiverIp, service.port), 550)
                    open.add(service)
                }
            } catch (_: Exception) {
                // Closed, filtered, timed out, or not accessible from this phone.
            }
        }

        return buildString {
            append("Diagnostic réseau Zen 4\n")
            append("Port de contrôle G-MScreen : 20000 (déjà utilisé)\n\n")
            if (open.isEmpty()) {
                append("Aucun service de transfert de fichiers détecté sur les ports courants.\n")
                append("Cela ne prouve pas qu'il n'existe aucune autre méthode de transfert.\n")
                append("Le protocole G-MScreen testé ne fournit pas de fichier CHMAX.")
            } else {
                append("Ports accessibles depuis le téléphone :\n")
                for (service in open) append("- ${service.port} · ${service.name} · ${service.purpose}\n")
                append("\nUn port accessible n'est PAS la preuve qu'un partage de fichiers est disponible.\n")
                append("Aucune authentification, navigation ni téléchargement n'a été effectué.")
            }
            append("\n\nLecture seule : aucune modification du récepteur.")
        }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val nums = host.split('.').map { it.toIntOrNull() }
        if (nums.size != 4 || nums.any { it == null || it !in 0..255 }) return false
        val a = nums[0]!!; val b = nums[1]!!
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
