package com.satnova.alpha

import android.content.SharedPreferences

/** User-selected TV UI language independent of Android system language. */
internal class SatNovaLanguage(private val prefs: SharedPreferences) {
    fun code(): String = prefs.getString("ui_language", "fr")?.takeIf { it in listOf("ar","fr","en") } ?: "fr"
    fun name(): String = when(code()) { "ar" -> "العربية"; "en" -> "English"; else -> "Français" }
    fun t(key: String): String {
        val words = mapOf(
            "home" to arrayOf("الرئيسية", "Accueil", "Home"),
            "search" to arrayOf("بحث", "Recherche", "Search"),
            "settings" to arrayOf("الإعدادات", "Paramètres", "Settings"),
            "wifi" to arrayOf("الواي فاي", "Wi-Fi", "Wi-Fi"),
            "language" to arrayOf("اللغة", "Langue", "Language"),
            "location" to arrayOf("المدينة", "Ville", "City"),
            "time" to arrayOf("الوقت والتاريخ", "Date et heure", "Date & time"),
            "themes" to arrayOf("الخلفية والثيم", "Thèmes et fond", "Themes & wallpaper"),
            "islamic" to arrayOf("الثيم الإسلامي الأصلي", "Thème islamique original", "Original Islamic theme"),
            "sport" to arrayOf("الثيم الرياضي · ملعب مولاي عبد الله", "Stade Moulay Abdellah · Sport", "Moulay Abdellah Stadium · Sports"),
            "custom" to arrayOf("صورة خاصة من التلفاز أو USB", "Photo depuis la TV ou USB", "Choose photo from TV or USB"),
            "quran" to arrayOf("القرآن الكريم", "Saint Coran", "Holy Quran"),
            "adhan" to arrayOf("صوت الأذان", "Audio de l'adhan", "Adhan audio"),
            "cityWeather" to arrayOf("مدينة الطقس والصلاة", "Ville météo et prières", "Weather & prayer city"),
            "apps" to arrayOf("التطبيقات المفضلة", "Applications favorites", "Favorite apps"),
            "radio" to arrayOf("الإذاعات", "Radios", "Radio"),
            "tv" to arrayOf("القنوات", "Chaînes TV", "TV channels"),
            "watched" to arrayOf("الأكثر مشاهدة", "Les plus regardées", "Most watched"),
            "homeSelect" to arrayOf("اختيار SatNova كواجهة رئيسية", "Choisir SatNova comme accueil", "Set SatNova as Home"),
            "homeRestore" to arrayOf("العودة للواجهة الأصلية", "Retour à l'accueil original", "Restore original Home"),
            "hdmiRemote" to arrayOf("تيليكوموند ZEN 4 عبر HDMI", "Télécommande ZEN 4 via HDMI", "ZEN 4 remote via HDMI"),
            "systemSettings" to arrayOf("إعدادات النظام", "Paramètres système", "System settings"),
            "hdmi" to arrayOf("اختبار HDMI", "Tester HDMI", "Test HDMI"),
            "connection" to arrayOf("اتصال ZEN 4", "Connexion ZEN 4", "ZEN 4 connection"),
            "logos" to arrayOf("تحديث شعارات القنوات", "Actualiser les logos", "Refresh channel logos"),
            "groups" to arrayOf("إعادة ضبط مجموعات القنوات", "Réinitialiser les groupes TV", "Reset channel groups"),
            "diagnostics" to arrayOf("تشخيص HOME", "Diagnostic HOME", "HOME diagnostics"),
            "history" to arrayOf("إظهار أو إخفاء الأكثر مشاهدة", "Afficher / masquer les plus regardées", "Show / hide most watched"),
            "prayerToday" to arrayOf("مواقيت الوزارة لليوم", "Horaires du ministère : aujourd'hui", "Ministry prayer times: today"),
            "prayerTomorrow" to arrayOf("مواقيت الوزارة للغد", "Horaires du ministère : demain", "Ministry prayer times: tomorrow"),
            "habous" to arrayOf("موقع الأوقاف المغربية", "Site des Habous", "Moroccan Ministry of Habous"),
            "accessibility" to arrayOf("تفعيل إمكانية الوصول", "Activer Accessibilité", "Enable Accessibility"),
            "close" to arrayOf("إغلاق", "Fermer", "Close")
        )
        val i = when(code()) { "ar" -> 0; "en" -> 2; else -> 1 }
        return words[key]?.get(i) ?: key
    }
}
