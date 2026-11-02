# Azan TV

تطبيق أذان لشاشات Android TV مع ساعة وقت ESP32.

- `app-source/` — تطبيق Android (com.azantv.app، الإصدار 1.56). البناء: `app-source/build.sh`
- `ntp-clock/` — برنامج ESP32 (PlatformIO) يبث شبكة Masjid-Time ويوفر الوقت عبر NTP للشاشات.

ملف التوقيع `keystore.jks` غير موجود في المستودع عمداً. احتفظ بنسختك منه، فبدونه لا يمكن تحديث التطبيق المثبت على الشاشة.
