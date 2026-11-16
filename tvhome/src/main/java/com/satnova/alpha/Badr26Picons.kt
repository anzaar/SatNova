package com.satnova.alpha

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/**
 * Picons names confirmed in the Badr 26°E service index.
 * Source: https://github.com/picons/picons
 * Matches channel names only; never changes the receiver's order or metadata.
 */
internal class Badr26Picons(context: Context) {
    private val aliases: Map<String, String> = try {
        context.assets.open("badr26-picons.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val pair = line.split('\t', limit = 2)
                    if (pair.size != 2 || !pair[0].matches(Regex("[a-z0-9_-]+")) ||
                        !pair[1].matches(Regex("[a-z0-9_-]+\\.default\\.(png|svg)"))) null
                    else pair[0] to pair[1]
                }.toMap()
        }
    } catch (_: Exception) { emptyMap() }

    private fun key(name: String) = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "")

    fun url(channel: ZenChannel): String? {
        val key = key(channel.name)
        if (key.isBlank()) return null
        // Exact published aliases before a conservative quality-suffix fallback.
        val file = aliases[key]
            ?: key.removeSuffix("hd").takeIf { it != key }?.let(aliases::get)
            ?: key.removeSuffix("sd").takeIf { it != key }?.let(aliases::get)
            ?: key.removeSuffix("4k").takeIf { it != key }?.let(aliases::get)
            ?: return null
        return "https://raw.githubusercontent.com/picons/picons/master/build-source/logos/$file"
    }
}
