# Zen4 TV Companion — التطبيق المكمّل الخفيف بجانب SecretoTVLauncher

تطبيق Android TV خفيف يعمل بجانب **SecretoTVLauncher** دون أي تعارض (معرّف حزمة مستقل `com.satnova.zencompanion` وبدون `CATEGORY_HOME`، وبدون أذان أو طقس أو قرآن).

## خطوات البناء والتشغيل في 3 دقائق
1. فكّ الضغط وضع مجلد `zen4-companion/` وملف `.github/workflows/zen4-companion-build.yml` داخل جذر مستودع **SatNova** (على فرع `feature/altaplay-v012-multilingual-safe-ui-20261122`).
2. ارفع التغييرات (`git push`) ثم افتح تبويب **Actions** في GitHub وشغّل **Build Zen4 TV Companion APK** لتحميل ملف `app-release.apk` الجاهز مع ملف فهرس الشعارات `badr-eshail-logos.tsv`.
3. انشر `badr-eshail-logos.tsv` في مسار `zen4-companion/badr-eshail-logos.tsv` بالمستودع ليحمّله التطبيق تلقائياً في أول تشغيل ويخزّن شعارات قنواتك محلياً على التلفاز.
4. ثبّت الـ APK على التلفاز بجانب **SecretoTVLauncher**، وأضف أيقونة **Zen4 TV** لشريط سوكريتو، ثم افتح التطبيق واضغط **إعداد HDMI والريموت** و**تفعيل الريموت** مرة واحدة فقط.
