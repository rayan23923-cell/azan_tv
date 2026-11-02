// MasjidClock - a stand-alone Wi-Fi time network for mosque TVs/screens.
//
// The ESP32 broadcasts its own Wi-Fi network ("Masjid-Time" by default).
// Screens (Android TVs running AzanTV, etc.) join it and use the ESP32 as
// their NTP server:   adb shell settings put global ntp_server 192.168.4.1
//
// Time sources, best first:
//   1. Internet SNTP, if an uplink router is configured (optional).
//   2. DS3231 RTC with battery (optional, SDA=21 SCL=22 VCC=3V3).
//   3. A phone that joins the network: the captive page sends the phone's
//      clock. When the clock is already good, a phone can only change it with
//      the admin PIN, so visitors' phones can't break it.
//
// Captive behaviour: while the clock is NOT set, phones that join get the
// "sign in to network" page automatically (and it sends their time). Once the
// clock is good, connectivity probes are answered as "online" so screens and
// phones are not nagged.

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <Preferences.h>
#include <Wire.h>
#include <RTClib.h>
#include <esp_sntp.h>
#include <esp_wifi.h>
#include <LittleFS.h>
#include <sys/time.h>

// ---------------------------------------------------------------- defaults
static const char *DEFAULT_AP_SSID = "Masjid-Time";
static const char *DEFAULT_PIN = "1234";
static const char *LOCAL_TZ = "<+03>-3";          // Iraq, UTC+3 (display only)
static const int LED_PIN = 2;                     // DevKit on-board LED
static const time_t MIN_VALID_EPOCH = 1735689600; // 2025-01-01T00:00:00Z
static const uint32_t NTP_UNIX_OFFSET = 2208988800UL;

// ---------------------------------------------------------------- state
WebServer web(80);
DNSServer dns;
WiFiUDP ntpUdp;
Preferences prefs;
RTC_DS3231 rtc;

bool rtcPresent = false;
bool timeValid = false;
String lastSource = "none";
time_t lastSyncEpoch = 0;
uint32_t ntpServed = 0;

String apSsid, apPass, adminPin, upSsid, upPass;

// ---------------------------------------------------------------- clock
static void markSynced(const char *source) {
  struct timeval tv;
  gettimeofday(&tv, nullptr);
  timeValid = tv.tv_sec >= MIN_VALID_EPOCH;
  lastSource = source;
  lastSyncEpoch = tv.tv_sec;
  Serial.printf("[clock] set from %s: %ld\n", source, (long)tv.tv_sec);
}

static void writeRtcFromSystem() {
  if (!rtcPresent) return;
  struct timeval tv;
  gettimeofday(&tv, nullptr);
  time_t s = tv.tv_sec + (tv.tv_usec >= 500000 ? 1 : 0);
  rtc.adjust(DateTime((uint32_t)s));
}

static bool setSystemClockMs(uint64_t epochMs) {
  if (epochMs / 1000ULL < (uint64_t)MIN_VALID_EPOCH) return false;
  struct timeval tv;
  tv.tv_sec = (time_t)(epochMs / 1000ULL);
  tv.tv_usec = (suseconds_t)((epochMs % 1000ULL) * 1000ULL);
  settimeofday(&tv, nullptr);
  return true;
}

static void loadFromRtc() {
  if (!rtcPresent || rtc.lostPower()) return;
  uint32_t t = rtc.now().unixtime();
  if (t < (uint32_t)MIN_VALID_EPOCH) return;
  setSystemClockMs((uint64_t)t * 1000ULL);
  markSynced("rtc");
}

static void onSntpSync(struct timeval *) {
  markSynced("internet");
  writeRtcFromSystem();
}

static String localTimeString() {
  time_t now = time(nullptr);
  struct tm lt;
  localtime_r(&now, &lt);
  char buf[32];
  strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", &lt);
  return String(buf);
}

// ---------------------------------------------------------------- NTP server
static void writeNtpTimestamp(uint8_t *p, const struct timeval &tv) {
  uint32_t sec = (uint32_t)tv.tv_sec + NTP_UNIX_OFFSET;
  uint32_t frac = (uint32_t)(((uint64_t)tv.tv_usec << 32) / 1000000ULL);
  p[0] = sec >> 24; p[1] = sec >> 16; p[2] = sec >> 8; p[3] = sec;
  p[4] = frac >> 24; p[5] = frac >> 16; p[6] = frac >> 8; p[7] = frac;
}

static void handleNtp() {
  int len = ntpUdp.parsePacket();
  if (len <= 0) return;
  struct timeval rx;
  gettimeofday(&rx, nullptr);

  uint8_t req[48];
  if (len < 48) { ntpUdp.flush(); return; }
  ntpUdp.read(req, 48);
  ntpUdp.flush();
  if (!timeValid) return;           // never hand out a wrong time
  if ((req[0] & 0x07) != 3) return; // client requests only
  uint8_t vn = (req[0] >> 3) & 0x07;
  if (vn < 1 || vn > 4) vn = 4;

  uint8_t res[48] = {0};
  res[0] = (vn << 3) | 4;           // LI=0, VN, mode=server
  res[1] = 2;                       // stratum
  res[2] = req[2];
  res[3] = 0xEC;                    // precision 2^-20 s
  res[10] = 0x04;                   // root dispersion ~15 ms
  memcpy(&res[12], "LOCL", 4);
  struct timeval ref = { lastSyncEpoch, 0 };
  writeNtpTimestamp(&res[16], ref);
  memcpy(&res[24], &req[40], 8);    // originate = client's transmit
  writeNtpTimestamp(&res[32], rx);
  struct timeval tx;
  gettimeofday(&tx, nullptr);
  writeNtpTimestamp(&res[40], tx);

  ntpUdp.beginPacket(ntpUdp.remoteIP(), ntpUdp.remotePort());
  ntpUdp.write(res, 48);
  ntpUdp.endPacket();
  ntpServed++;
}

// ---------------------------------------------------------------- web
static const char PAGE[] PROGMEM = R"HTML(<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ساعة المسجد</title>
<style>
body{font-family:sans-serif;background:#0b3d2e;color:#fff;margin:0;padding:16px;text-align:center}
.card{background:#14523f;border-radius:12px;padding:16px;margin:12px auto;max-width:420px}
h1{color:#e8c05a;margin:8px 0}.big{font-size:2.2em;font-weight:bold}
button,input{font-size:1em;padding:10px;border-radius:8px;border:0;margin:4px;width:90%;box-sizing:border-box}
button{background:#e8c05a;color:#0b3d2e;font-weight:bold}
small{color:#bcd}details{text-align:right}
</style></head><body>
<h1>ساعة المسجد</h1>
<div class="card"><div>الوقت الآن</div><div class="big">%TIME%</div>
<small>المصدر: %SOURCE% • %VALID%</small></div>
<div class="card"><div id="msg">%MSG%</div>
<input id="pin" type="password" inputmode="numeric" placeholder="الرمز (مطلوب فقط إذا كان الوقت مضبوطاً)">
<button onclick="sync()">ضبط الوقت من هذا الهاتف</button></div>
<div class="card"><a href="/settings"><button>إعدادات شاشات الأذان (AzanTV)</button></a>
<a href="/update"><button>تحديث تطبيق الأذان</button></a>
<a href="/apk"><button>تنزيل تطبيق الأذان (APK)</button></a>
<small>رابط التطبيق المباشر: http://%IP%/apk</small></div>
<div class="card"><details><summary>إعدادات الساعة (تحتاج الرمز)</summary>
<form method="post" action="/config">
<input name="pin" type="password" inputmode="numeric" placeholder="الرمز الحالي" required>
<b>شبكة الساعة</b>
<input name="apssid" placeholder="اسم الشبكة" value="%APSSID%">
<input name="appass" type="password" placeholder="كلمة مرور الشبكة (فارغ = مفتوحة، أو 8 أحرف فأكثر)">
<b>راوتر فيه إنترنت (اختياري)</b>
<input name="upssid" placeholder="اسم الراوتر" value="%UPSSID%">
<input name="uppass" type="password" placeholder="كلمة مرور الراوتر">
<b>تغيير الرمز</b>
<input name="newpin" type="password" inputmode="numeric" placeholder="رمز جديد (اختياري)">
<button type="submit">حفظ وإعادة تشغيل</button></form>
<small>%UPLINK%</small></details></div>
<small>طلبات NTP: %SERVED% • RTC: %RTC% • عنوان الساعة: 192.168.4.1</small>
<script>
function sync(){
  var m=document.getElementById('msg');
  var p=encodeURIComponent(document.getElementById('pin').value);
  fetch('/settime?ms='+Date.now()+'&pin='+p).then(r=>r.text()).then(t=>{
    m.textContent=t; setTimeout(()=>location.reload(),2000);
  }).catch(()=>{m.textContent='فشل الإرسال';});
}
if(%AUTO%) sync();
</script></body></html>)HTML";

static void handleRoot() {
  String html = FPSTR(PAGE);
  html.replace("%TIME%", timeValid ? localTimeString() : String("غير مضبوط"));
  html.replace("%SOURCE%", lastSource);
  html.replace("%VALID%", timeValid ? "مضبوط ✓" : "يحتاج ضبط");
  html.replace("%MSG%", timeValid ? "الوقت مضبوط. لتغييره أدخل الرمز."
                                  : "جارٍ إرسال وقت هاتفك…");
  html.replace("%AUTO%", timeValid ? "false" : "true");
  html.replace("%APSSID%", apSsid);
  html.replace("%UPSSID%", upSsid);
  html.replace("%UPLINK%", WiFi.status() == WL_CONNECTED
      ? "متصل بالراوتر " + WiFi.SSID() + " • IP: " + WiFi.localIP().toString()
      : String("لا يوجد راوتر"));
  html.replace("%SERVED%", String(ntpServed));
  html.replace("%IP%", web.client().localIP().toString());
  html.replace("%RTC%", rtcPresent ? "موجود" : "غير موجود");
  web.sendHeader("Cache-Control", "no-store");
  web.send(200, "text/html; charset=utf-8", html);
}

static void handleSetTime() {
  if (!web.hasArg("ms")) { web.send(400, "text/plain", "missing ms"); return; }
  if (timeValid && web.arg("pin") != adminPin) {
    web.send(403, "text/plain; charset=utf-8", "الوقت مضبوط مسبقاً، والرمز غير صحيح");
    return;
  }
  uint64_t ms = strtoull(web.arg("ms").c_str(), nullptr, 10);
  if (!setSystemClockMs(ms)) {
    web.send(400, "text/plain; charset=utf-8", "وقت الهاتف غير صالح");
    return;
  }
  markSynced("phone");
  writeRtcFromSystem();
  web.send(200, "text/plain; charset=utf-8", "تم ضبط الوقت ✓ " + localTimeString());
}

static void handleConfig() {
  if (web.arg("pin") != adminPin) {
    web.send(403, "text/plain; charset=utf-8", "الرمز غير صحيح");
    return;
  }
  String ssid = web.arg("apssid");
  String pass = web.arg("appass");
  if (pass.length() > 0 && pass.length() < 8) {
    web.send(400, "text/plain; charset=utf-8", "كلمة مرور الشبكة يجب أن تكون 8 أحرف فأكثر");
    return;
  }
  if (ssid.length() > 0) prefs.putString("apssid", ssid);
  prefs.putString("appass", pass);
  prefs.putString("upssid", web.arg("upssid"));
  if (web.arg("uppass").length() > 0) prefs.putString("uppass", web.arg("uppass"));
  if (web.arg("newpin").length() > 0) prefs.putString("pin", web.arg("newpin"));
  web.send(200, "text/plain; charset=utf-8", "تم الحفظ، إعادة التشغيل…");
  delay(500);
  ESP.restart();
}

static void redirectToPortal() {
  Serial.printf("[status] unknown %s from %s\n", web.uri().c_str(),
                web.client().remoteIP().toString().c_str());
  web.sendHeader("Location", "http://192.168.4.1/");
  web.send(302, "text/plain", "");
}

// Connectivity probes: "online" when the clock is good, captive page otherwise.
static void probeAndroid() {
  if (timeValid) web.send(204); else redirectToPortal();
}
static void probeApple() {
  if (timeValid) web.send(200, "text/html",
      "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>");
  else redirectToPortal();
}
static void probeWindowsConnect() {
  if (timeValid) web.send(200, "text/plain", "Microsoft Connect Test"); else redirectToPortal();
}
static void probeWindowsNcsi() {
  if (timeValid) web.send(200, "text/plain", "Microsoft NCSI"); else redirectToPortal();
}

// ---------------------------------------------------------------- setup / loop
static void startWifi() {
  apSsid = prefs.getString("apssid", DEFAULT_AP_SSID);
  apPass = prefs.getString("appass", "");
  adminPin = prefs.getString("pin", DEFAULT_PIN);
  upSsid = prefs.getString("upssid", "");
  upPass = prefs.getString("uppass", "");

  WiFi.mode(upSsid.length() ? WIFI_AP_STA : WIFI_AP);
  WiFi.setSleep(false);
  WiFi.softAPConfig(IPAddress(192, 168, 4, 1), IPAddress(192, 168, 4, 1),
                    IPAddress(255, 255, 255, 0));
  WiFi.softAP(apSsid.c_str(), apPass.length() ? apPass.c_str() : nullptr,
              1, 0, 8);  // channel 1 (follows the router if one is set), up to 8 screens

  if (upSsid.length()) {
    int n = WiFi.scanNetworks();
    for (int i = 0; i < n; i++) {
      Serial.printf("[status] scan '%s' ch%d rssi%d auth%d\n", WiFi.SSID(i).c_str(),
                    WiFi.channel(i), WiFi.RSSI(i), (int)WiFi.encryptionType(i));
    }
    // Optional fixed home-LAN address so the TV's ntp_server never goes stale.
    IPAddress ip;
    if (ip.fromString(prefs.getString("upip", ""))) {
      IPAddress gw(ip[0], ip[1], ip[2], 1);
      WiFi.config(ip, gw, IPAddress(255, 255, 255, 0), gw);
    }
    WiFi.setAutoReconnect(true);
    WiFi.setMinSecurity(WIFI_AUTH_WPA_PSK);  // some home routers still use WPA1
    WiFi.begin(upSsid.c_str(), upPass.c_str());
    // Same as D:\cooler\ebike_controller: also force the WPA1 threshold at the
    // ESP-IDF level, otherwise reconnects can fail with NO_SSID_AVAIL.
    wifi_config_t conf;
    if (esp_wifi_get_config(WIFI_IF_STA, &conf) == ESP_OK) {
      conf.sta.threshold.authmode = WIFI_AUTH_WPA_PSK;
      esp_wifi_set_config(WIFI_IF_STA, &conf);
      esp_wifi_connect();
    }
  }
}

// ---------------------------------------------------------------- AzanTV settings
// The ESP32 is the single place where the mosque's AzanTV settings live.
// Screens pull GET /api/settings and apply them when "version" changes.
// Contract (JSON object, all keys optional; the app keeps its default for missing ones):
//   city (Arabic name as in the app list), method: jafari|mwl|makkah|egypt|karachi,
//   asr: standard|hanafi, adj_<p> (min, p = fajr|sunrise|dhuhr|asr|maghrib|isha),
//   azan_<p> (bool, p without sunrise), iqama_<p> (min), prayer_min, adhkar_on (bool),
//   adhkar_min, fajr_first_min, jumua_first_min, khutbah_min, hijri_adj (days),
//   azan_volume (0-100)

static void sendJson(int code, const String &body) {
  web.sendHeader("Access-Control-Allow-Origin", "*");
  web.sendHeader("Cache-Control", "no-store");
  web.send(code, "application/json; charset=utf-8", body);
}

static void handleApiSettingsGet() {
  Serial.printf("[status] GET /api/settings from %s\n", web.client().remoteIP().toString().c_str());
  String s = prefs.getString("appcfg", "{}");
  uint32_t v = prefs.getUInt("appver", 0);
  sendJson(200, String("{\"version\":") + v + ",\"settings\":" + s + "}");
}

// POST /api/settings  body = settings JSON object.
// Needs ?pin=, except the very first seed (version 0), so a screen can upload
// its current settings once when it first finds the clock.
static void handleApiSettingsPost() {
  uint32_t v = prefs.getUInt("appver", 0);
  String body = web.arg("plain");
  body.trim();
  Serial.printf("[status] POST /api/settings from %s len=%u args=%d\n",
                web.client().remoteIP().toString().c_str(), body.length(), web.args());
  if (v != 0 && web.arg("pin") != adminPin) { sendJson(403, "{\"error\":\"pin\"}"); return; }
  if (!body.startsWith("{") || !body.endsWith("}") || body.length() > 3500) {
    sendJson(400, "{\"error\":\"json\"}");
    return;
  }
  prefs.putString("appcfg", body);
  prefs.putUInt("appver", v + 1);
  sendJson(200, String("{\"version\":") + (v + 1) + "}");
}

static void handleApiInfo() {
  Serial.printf("[status] GET /api/info from %s\n", web.client().remoteIP().toString().c_str());
  sendJson(200, String("{\"name\":\"MasjidClock\",\"timeValid\":") + (timeValid ? "true" : "false") +
      ",\"source\":\"" + lastSource + "\",\"epochMs\":" +
      String((uint64_t)time(nullptr) * 1000ULL) +
      ",\"ip\":\"" + WiFi.localIP().toString() + "\",\"apIp\":\"192.168.4.1\"}");
}

static const char SETTINGS_PAGE[] PROGMEM = R"HTML(<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>إعدادات شاشات المسجد</title>
<style>
body{font-family:sans-serif;background:#0b3d2e;color:#fff;margin:0;padding:12px}
h1{color:#e8c05a;text-align:center;font-size:1.4em}
.card{background:#14523f;border-radius:12px;padding:12px;margin:10px auto;max-width:480px}
.card h2{color:#e8c05a;font-size:1.05em;margin:4px 0 8px}
label{display:flex;justify-content:space-between;align-items:center;margin:6px 0;gap:8px}
input,select,button{font-size:1em;padding:8px;border-radius:8px;border:0}
input[type=number]{width:80px}button{background:#e8c05a;color:#0b3d2e;font-weight:bold;width:100%;margin-top:8px}
#msg{text-align:center;margin:8px}small{color:#bcd}
</style></head><body>
<h1>إعدادات شاشات المسجد</h1>
<div id="form">جارٍ التحميل…</div>
<div class="card"><label>الرمز <input id="pin" type="password" inputmode="numeric"></label>
<button onclick="save()">حفظ للشاشات</button><div id="msg"></div>
<small>تأخذ الشاشات الإعدادات خلال دقيقة.</small></div>
<script>
var P=[['fajr','الفجر'],['sunrise','الشروق'],['dhuhr','الظهر'],['asr','العصر'],['maghrib','المغرب'],['isha','العشاء']];
var CITIES=['بغداد','البصرة','الموصل','أربيل','النجف','كربلاء','السليمانية','كركوك','الناصرية','الحلة','الديوانية','العمارة','الكوت','السماوة','الرمادي','الفلوجة','بعقوبة','تكريت','سامراء','دهوك'];
var METHODS=[['jafari','الجعفري (معهد قم)'],['mwl','رابطة العالم الإسلامي'],['makkah','أم القرى (مكة)'],['egypt','الهيئة المصرية'],['karachi','جامعة كراتشي']];
var S={};
function sel(k,opts){return '<select id="'+k+'">'+opts.map(function(o){var v=o[0]||o,t=o[1]||o;return '<option value="'+v+'"'+(S[k]==v?' selected':'')+'>'+t+'</option>'}).join('')+'</select>'}
function num(k,d){return '<input type="number" id="'+k+'" value="'+(S[k]!=null?S[k]:d)+'">'}
function chk(k,d){var v=S[k]!=null?S[k]:d;return '<input type="checkbox" id="'+k+'"'+(v?' checked':'')+'>'}
function row(t,h){return '<label><span>'+t+'</span>'+h+'</label>'}
function render(){
 var h='<div class="card"><h2>عام</h2>'+row('المدينة',sel('city',CITIES))+row('طريقة الحساب',sel('method',METHODS))+
  row('وقت العصر',sel('asr',[['standard','الجمهور'],['hanafi','الحنفي']]))+row('تعديل التاريخ الهجري (يوم)',num('hijri_adj',0))+
  row('صوت الأذان %',num('azan_volume',100))+'</div><div class="card"><h2>الأذان والإقامة</h2>';
 P.forEach(function(p){h+=row(p[1]+': تعديل (د)',num('adj_'+p[0],0));if(p[0]!='sunrise'){h+=row('أذان '+p[1],chk('azan_'+p[0],true))+row('الإقامة بعد (د)',num('iqama_'+p[0],{fajr:20,dhuhr:15,asr:15,maghrib:5,isha:10}[p[0]]))}});
 h+='</div><div class="card"><h2>الأذكار والجمعة</h2>'+row('مدة الصلاة قبل الأذكار (د)',num('prayer_min',10))+row('عرض الأذكار',chk('adhkar_on',true))+
  row('مدة الأذكار (د)',num('adhkar_min',10))+row('الأذان الأول للفجر قبل (د)',num('fajr_first_min',30))+
  row('الأذان الأول للجمعة قبل (د)',num('jumua_first_min',30))+row('شاشة الخطبة (د)',num('khutbah_min',25))+'</div>';
 document.getElementById('form').innerHTML=h;
}
function collect(){var o={};document.querySelectorAll('#form input,#form select').forEach(function(e){
 o[e.id]=e.type=='checkbox'?e.checked:(e.type=='number'?Number(e.value):e.value)});return o}
function save(){var m=document.getElementById('msg');
 fetch('/api/settings?pin='+encodeURIComponent(document.getElementById('pin').value),{method:'POST',body:JSON.stringify(collect())})
 .then(function(r){return r.json().then(function(j){m.textContent=r.ok?'تم الحفظ ✓ (الإصدار '+j.version+')':'الرمز غير صحيح'})})
 .catch(function(){m.textContent='فشل الحفظ'})}
fetch('/api/settings').then(function(r){return r.json()}).then(function(j){S=j.settings||{};render()});
</script></body></html>)HTML";

// AzanTV registers itself: GET /api/tv?ip=<tv ip>. The TV serves the real
// settings UI on :8080, so /settings forwards the phone there.
static void handleApiTv() {
  String ip = web.arg("ip");
  IPAddress parsed;
  if (parsed.fromString(ip)) {
    if (prefs.getString("tvip", "") != ip) prefs.putString("tvip", ip);
    Serial.printf("[status] TV registered at %s\n", ip.c_str());
    sendJson(200, "{\"ok\":true}");
  } else {
    sendJson(400, "{\"error\":\"ip\"}");
  }
}

static void handleSettingsPage() {
  String tv = prefs.getString("tvip", "");
  if (tv.length()) {
    web.sendHeader("Location", "http://" + tv + ":8080/settings");
    web.send(302, "text/plain", "");
    return;
  }
  web.sendHeader("Cache-Control", "no-store");
  web.send_P(200, "text/html; charset=utf-8", SETTINGS_PAGE);
}

// ---------------------------------------------------------------- AzanTV APK store
// The phone uploads a new AzanTV APK here once; every screen in the mosque sees
// GET /api/apk change "id", downloads /app.apk, compares its versionCode with
// the installed one and opens Android's installer.
static bool fsReady = false;
static File apkUpload;
static bool apkUploadOk = false;

static void handleApkInfo() {
  Serial.printf("[status] GET /api/apk from %s\n", web.client().remoteIP().toString().c_str());
  if (!fsReady || !LittleFS.exists("/app.apk")) { sendJson(200, "{\"id\":0}"); return; }
  File f = LittleFS.open("/app.apk", "r");
  size_t sz = f.size();
  f.close();
  sendJson(200, String("{\"id\":") + prefs.getUInt("apkid", 0) + ",\"size\":" + sz +
      ",\"name\":\"" + prefs.getString("apkname", "AzanTV.apk") + "\",\"url\":\"/app.apk\"}");
}

static void handleApkDownload() {
  Serial.printf("[status] GET %s (download) from %s\n", web.uri().c_str(),
                web.client().remoteIP().toString().c_str());
  if (!fsReady || !LittleFS.exists("/app.apk")) { web.send(404, "text/plain", "no apk"); return; }
  File f = LittleFS.open("/app.apk", "r");
  web.sendHeader("Content-Disposition", "attachment; filename=\"AzanTV.apk\"");
  web.streamFile(f, "application/vnd.android.package-archive");
  f.close();
}

// Multipart upload: POST /api/apk?pin=XXXX  (form field "apk")
static void handleApkUploadChunk() {
  HTTPUpload &up = web.upload();
  if (up.status == UPLOAD_FILE_START) {
    apkUploadOk = fsReady && web.arg("pin") == adminPin;
    if (apkUploadOk) {
      apkUpload = LittleFS.open("/app.tmp", "w");
      apkUploadOk = (bool)apkUpload;
    }
    Serial.printf("[status] apk upload start %s ok=%d\n", up.filename.c_str(), apkUploadOk);
  } else if (up.status == UPLOAD_FILE_WRITE) {
    if (apkUploadOk && apkUpload.write(up.buf, up.currentSize) != up.currentSize) apkUploadOk = false;
  } else if (up.status == UPLOAD_FILE_END) {
    if (apkUpload) apkUpload.close();
    if (apkUploadOk && up.totalSize > 1000) {
      LittleFS.remove("/app.apk");
      LittleFS.rename("/app.tmp", "/app.apk");
      prefs.putUInt("apkid", prefs.getUInt("apkid", 0) + 1);
      prefs.putString("apkname", up.filename);
    } else {
      LittleFS.remove("/app.tmp");
      apkUploadOk = false;
    }
    Serial.printf("[status] apk upload end size=%u ok=%d\n", (unsigned)up.totalSize, apkUploadOk);
  } else if (up.status == UPLOAD_FILE_ABORTED) {
    if (apkUpload) apkUpload.close();
    LittleFS.remove("/app.tmp");
    apkUploadOk = false;
  }
}

static void handleApkUploadDone() {
  if (apkUploadOk) handleApkInfo();
  else sendJson(web.arg("pin") == adminPin ? 400 : 403, "{\"error\":\"upload\"}");
}

static const char UPDATE_PAGE[] PROGMEM = R"HTML(<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>تحديث تطبيق الأذان</title>
<style>
body{font-family:sans-serif;background:#0b3d2e;color:#fff;margin:0;padding:16px;text-align:center}
.card{background:#14523f;border-radius:12px;padding:16px;margin:12px auto;max-width:420px}
h1{color:#e8c05a}input,button{font-size:1em;padding:10px;border-radius:8px;border:0;margin:6px 0;width:100%;box-sizing:border-box}
button{background:#e8c05a;color:#0b3d2e;font-weight:bold}#m{margin-top:8px;color:#e8c05a}small{color:#bcd}
</style></head><body><h1>تحديث تطبيق الأذان</h1>
<div class="card"><div id="cur">…</div></div>
<div class="card"><input id="f" type="file" accept=".apk,application/vnd.android.package-archive">
<input id="pin" type="password" inputmode="numeric" placeholder="الرمز">
<button onclick="up()">رفع النسخة للشاشات</button><div id="m"></div>
<a href="/apk"><button>تنزيل النسخة المخزنة</button></a>
<small>بعد الرفع تعرض كل شاشة رسالة التحديث، اضغط "تثبيت" بالريموت.</small></div>
<script>
function info(){fetch('/api/apk').then(function(r){return r.json()}).then(function(j){
 document.getElementById('cur').textContent=j.id?('النسخة المخزنة: '+j.name+' — '+(j.size/1048576).toFixed(2)+' ميغا (رقم '+j.id+')'):'لا توجد نسخة مخزنة'})}
function up(){var f=document.getElementById('f').files[0],m=document.getElementById('m');if(!f){m.textContent='اختر ملف APK';return}
 var fd=new FormData();fd.append('apk',f,f.name);var x=new XMLHttpRequest();
 x.open('POST','/api/apk?pin='+encodeURIComponent(document.getElementById('pin').value));
 x.upload.onprogress=function(e){if(e.lengthComputable)m.textContent='جارٍ الرفع '+Math.round(e.loaded*100/e.total)+'%'};
 x.onload=function(){m.textContent=x.status==200?'تم الرفع ✓':(x.status==403?'الرمز غير صحيح':'فشل الرفع ('+x.status+')');info()};
 x.onerror=function(){m.textContent='فشل الاتصال'};x.send(fd)}
info();
</script></body></html>)HTML";

static void handleUpdatePage() {
  web.sendHeader("Cache-Control", "no-store");
  web.send_P(200, "text/html; charset=utf-8", UPDATE_PAGE);
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  setenv("TZ", LOCAL_TZ, 1);
  tzset();
  prefs.begin("masjidclock", false);

  // The default partition table names the data partition "spiffs".
  fsReady = LittleFS.begin(true, "/littlefs", 10, "spiffs");
  Serial.printf("[status] fs %s, total=%u used=%u\n", fsReady ? "ok" : "FAILED",
                fsReady ? (unsigned)LittleFS.totalBytes() : 0, fsReady ? (unsigned)LittleFS.usedBytes() : 0);

  Wire.begin(21, 22);
  rtcPresent = rtc.begin();
  Serial.printf("[rtc] %s\n", rtcPresent ? "found" : "not found");
  loadFromRtc();

  startWifi();

  sntp_set_time_sync_notification_cb(onSntpSync);
  configTime(0, 0, "pool.ntp.org", "time.google.com", "time.cloudflare.com");
  setenv("TZ", LOCAL_TZ, 1);  // configTime() resets TZ to UTC
  tzset();

  dns.start(53, "*", WiFi.softAPIP());
  web.on("/", handleRoot);
  web.on("/settime", handleSetTime);
  web.on("/config", HTTP_POST, handleConfig);
  web.on("/settings", handleSettingsPage);
  web.on("/api/settings", HTTP_GET, handleApiSettingsGet);
  web.on("/api/settings", HTTP_POST, handleApiSettingsPost);
  web.on("/api/info", handleApiInfo);
  web.on("/api/tv", handleApiTv);
  web.on("/update", handleUpdatePage);
  web.on("/app.apk", handleApkDownload);
  web.on("/apk", handleApkDownload);  // short link to type in a TV/phone browser
  web.on("/api/apk", HTTP_GET, handleApkInfo);
  web.on("/api/apk", HTTP_POST, handleApkUploadDone, handleApkUploadChunk);
  web.on("/generate_204", probeAndroid);
  web.on("/gen_204", probeAndroid);
  web.on("/hotspot-detect.html", probeApple);
  web.on("/library/test/success.html", probeApple);
  web.on("/connecttest.txt", probeWindowsConnect);
  web.on("/ncsi.txt", probeWindowsNcsi);
  web.onNotFound(redirectToPortal);
  web.begin();

  ntpUdp.begin(123);
}

void loop() {
  dns.processNextRequest();
  web.handleClient();
  handleNtp();

  // Uplink watchdog (pattern from D:\cooler): retry every 5 s while disconnected.
  static uint32_t lastWifiCheck = 0;
  if (upSsid.length() && millis() - lastWifiCheck > 5000UL) {
    lastWifiCheck = millis();
    if (WiFi.status() != WL_CONNECTED) WiFi.reconnect();
  }

  // Serial provisioning: "uplink <ssid> <password>" stores the router and reboots.
  if (Serial.available()) {
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.startsWith("tvip ")) {  // where AzanTV serves its settings UI (:8080)
      prefs.putString("tvip", line.substring(5));
      Serial.printf("[config] tv ip set to %s\n", line.substring(5).c_str());
    }
    if (line == "resetcfg") {  // forget the screens' settings (next screen re-seeds)
      prefs.remove("appcfg");
      prefs.putUInt("appver", 0);
      Serial.println("[config] app settings cleared");
    }
    if (line.startsWith("upip ")) {
      prefs.putString("upip", line.substring(5));
      Serial.printf("[config] uplink ip set to %s, restarting\n", line.substring(5).c_str());
      delay(300);
      ESP.restart();
    }
    if (line.startsWith("uplink ")) {
      String rest = line.substring(7);
      int sp = rest.indexOf(' ');
      String ssid = sp < 0 ? rest : rest.substring(0, sp);
      String pass = sp < 0 ? String("") : rest.substring(sp + 1);
      prefs.putString("upssid", ssid);
      prefs.putString("uppass", pass);
      Serial.printf("[config] uplink set to %s, restarting\n", ssid.c_str());
      delay(300);
      ESP.restart();
    }
  }

  // Without internet, re-read the DS3231 hourly (it drifts far less than the
  // ESP32's crystal).
  static uint32_t lastRtcReload = 0;
  if (rtcPresent && lastSource != "internet" && millis() - lastRtcReload > 3600000UL) {
    lastRtcReload = millis();
    loadFromRtc();
  }

  // Status line on the serial port every 10 s (uplink IP is what the TV's
  // ntp_server must point at when it stays on the home Wi-Fi).
  static uint32_t lastStatus = 0;
  if (millis() - lastStatus > 10000UL) {
    lastStatus = millis();
    Serial.printf("[status] valid=%d source=%s time=%s uplink=%s(%d) ip=%s ntp=%u\n",
                  timeValid, lastSource.c_str(), localTimeString().c_str(),
                  upSsid.c_str(), (int)WiFi.status(),
                  WiFi.localIP().toString().c_str(), ntpServed);
  }

  // LED: solid = clock good, blinking = waiting for a phone to set it.
  static uint32_t lastBlink = 0;
  if (timeValid) {
    digitalWrite(LED_PIN, HIGH);
  } else if (millis() - lastBlink > 500) {
    lastBlink = millis();
    digitalWrite(LED_PIN, !digitalRead(LED_PIN));
  }
}
