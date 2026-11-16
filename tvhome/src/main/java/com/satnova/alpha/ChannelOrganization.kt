package com.satnova.alpha

import java.text.Normalizer
import java.util.Locale

/**
 * Presentation-only classifications. No receiver list writes, no reordering.
 * Countries are conservative suggestions, and can be overridden in the UI.
 * A Badr/other satellite footprint is NOT equivalent to a channel's country.
 */
internal object ChannelOrganization {
    const val ALL = "Toutes"
    const val OTHER = "Non classées"

    val countries = listOf(
        "Maroc", "Qatar", "Arabie saoudite", "Algérie", "Jordanie",
        "Libye", "Oman", "Koweït", "Mauritanie", "Allemagne", "France", OTHER
    )

    fun plain(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim().replace(Regex("\\s+"), " ")

    private fun cleaned(name: String): String = plain(name)
        .replace(Regex("\\b(hd|sd|fhd|uhd|4k|hevc|h265|backup|bkp)\\b"), " ")
        .trim().replace(Regex("\\s+"), " ")

    fun country(channel: ZenChannel): String {
        val n = cleaned(channel.name)
        return when {
            n.matches(Regex("(al aoula|al oula|aloula|alawla)( .*|$)")) ||
                n.matches(Regex("2m( maroc| monde)?")) ||
                n.startsWith("arryadia") || n.startsWith("arriadia") ||
                n.startsWith("aryadia") || n.startsWith("athaqafia") ||
                n.startsWith("atthaqafia") || n.startsWith("assadissa") ||
                n.startsWith("aflam") || n.startsWith("tamazight") ||
                n.startsWith("medi1") || n.startsWith("medi 1") ||
                n.startsWith("al maghribia") -> "Maroc"
            n.startsWith("bein sports") || n.startsWith("bein movies") ||
                n.startsWith("al jazeera") || n.startsWith("aljazeera") ||
                n.startsWith("al kass") || n.startsWith("alkass") ||
                n.startsWith("qatar tv") || n.startsWith("qatar 2") ||
                n.startsWith("qatar today") || n.startsWith("al rayyan") ||
                n.startsWith("qbc ") || n == "qbc" -> "Qatar"
            n.startsWith("mbc") || n.startsWith("saudi ") ||
                n.startsWith("ksa ") -> "Arabie saoudite"
            n.startsWith("canal algerie") || n.startsWith("algerie ") ||
                n.startsWith("ennahar") -> "Algérie"
            n.startsWith("roya ") || n == "roya tv" ||
                n.startsWith("almamlaka") -> "Jordanie"
            n.startsWith("libya ") || n.startsWith("libye ") -> "Libye"
            n.startsWith("oman tv") -> "Oman"
            n.startsWith("kuwait ") || n.startsWith("koweit ") -> "Koweït"
            n.startsWith("el mouritania") || n.startsWith("mauritania") -> "Mauritanie"
            n.startsWith("das erste") || n.startsWith("zdf") -> "Allemagne"
            n.matches(Regex("france [235]?( tv)?")) ||
                n.startsWith("tf1") || n.matches(Regex("m6( .*|$)")) -> "France"
            else -> OTHER
        }
    }

    private val familyRules = listOf(
        "Al Aoula" to Regex("^(al aoula|al oula|aloula|alawla)(\\b|$)"),
        "2M" to Regex("^2m(\\b|$)"),
        "Arryadia" to Regex("^(arryadia|arriadia|aryadia)(\\b|$)"),
        "National Geographic" to Regex("^(national geographic|nat geo)(\\b|$)"),
        "Al Jazeera" to Regex("^(al jazeera|aljazeera)(\\b|$)"),
        "beIN SPORTS" to Regex("^bein sports(\\b|$)"),
        "beIN MOVIES" to Regex("^bein movies(\\b|$)"),
        "MBC" to Regex("^mbc(\\b|$)"),
        "Al Kass" to Regex("^(al kass|alkass)(\\b|$)")
    )

    fun family(channel: ZenChannel): String? {
        val n = cleaned(channel.name)
        return familyRules.firstOrNull { it.second.containsMatchIn(n) }?.first
    }

    fun knownFamilies(): List<String> = familyRules.map { it.first }

    fun tnt(channel: ZenChannel, country: String): Boolean =
        !channel.radio && country == "Maroc" && channel.frequencyMhz == 11180

    fun summary(channel: ZenChannel, country: String? = null): String {
        val parts = mutableListOf("N° \${channel.position}")
        if (channel.radio) parts.add("Radio")
        else channel.definition?.let(parts::add)
        when (channel.encrypted) {
            true -> parts.add("🔒 Cryptée")
            false -> parts.add("Libre")
            null -> Unit
        }
        if (tnt(channel, country ?: country(channel))) parts.add("TNT")
        return parts.joinToString(" · ")
    }
}
