// Azan TV time server: ESP32 + DS3231.
// - Joins the home Wi-Fi and serves NTP (UDP 123) on a fixed IP, so the TV can keep the
//   right time when the internet is down (adb: settings put global ntp_server 192.168.1.50).
// - Also opens its own open Wi-Fi "AzanClock" with a catch-all DNS, so any device that joins
//   it and asks for time.android.com / pool.ntp.org gets the time from here.
// - Whenever the internet is reachable it corrects the DS3231 from pool.ntp.org.
// - No internet? Join "AzanClock" from a phone: the sign-in page pops up by itself and sends
//   the phone's clock to the ESP32, which stores it in the DS3231.
#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <DNSServer.h>
#include <WebServer.h>
#include <Wire.h>
#include <RTClib.h>
#include <sys/time.h>
#include <time.h>
#include <esp_sntp.h>

// ---- settings: edit these ----
const char *HOME_SSID = "اسم_شبكة_البيت";
const char *HOME_PASS = "رمز_الشبكة";
IPAddress HOME_IP(192, 168, 1, 50);      // fixed address of this clock on the home network
IPAddress HOME_GW(192, 168, 1, 1);
IPAddress HOME_MASK(255, 255, 255, 0);
const char *AP_SSID = "AzanClock";       // open network for the "join to get time" mode
// -------------------------------

RTC_DS3231 rtc;
bool rtcOk = false;
WiFiUDP ntp;
DNSServer dns;
WebServer web(80);
unsigned long lastInternetSync = 0;
bool timeKnown = false;

static const uint32_t NTP_EPOCH_OFFSET = 2208988800UL;  // 1900 -> 1970

void setSystemTime(time_t t) {
  struct timeval tv = {t, 0};
  settimeofday(&tv, nullptr);
}

void loadTimeFromRtc() {
  if (!rtcOk) return;
  DateTime now = rtc.now();
  if (now.year() >= 2026 && !rtc.lostPower()) {
    setSystemTime(now.unixtime());
    timeKnown = true;
    Serial.printf("time from DS3231: %04d-%02d-%02d %02d:%02d:%02d UTC\n", now.year(), now.month(),
                  now.day(), now.hour(), now.minute(), now.second());
  } else {
    Serial.println("DS3231 has no valid time yet; waiting for internet");
  }
}

// Ask pool.ntp.org through the home network; on success also write the DS3231.
void syncFromInternet() {
  if (WiFi.status() != WL_CONNECTED) return;
  sntp_set_sync_status(SNTP_SYNC_STATUS_RESET);
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  unsigned long start = millis();
  while (sntp_get_sync_status() != SNTP_SYNC_STATUS_COMPLETED && millis() - start < 8000) delay(100);
  if (sntp_get_sync_status() != SNTP_SYNC_STATUS_COMPLETED) {
    Serial.println("internet time not reachable");
    return;
  }
  time_t t = time(nullptr);
  if (rtcOk) rtc.adjust(DateTime((uint32_t)t));
  timeKnown = true;
  lastInternetSync = millis();
  Serial.printf("synced from internet: %ld\n", (long)t);
}

// Captive-portal page: its script posts the phone's clock (UTC ms) to /set as soon as it opens.
const char PAGE[] PROGMEM = R"HTML(<!doctype html><html dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>ساعة الأذان</title>
<style>body{font-family:sans-serif;background:#06261D;color:#fff;text-align:center;padding:40px 16px}
h1{color:#E8BE5A}#s{font-size:22px;margin-top:24px}button{font-size:20px;padding:12px 28px;margin-top:24px;
border:0;border-radius:12px;background:#E8BE5A;color:#06261D}</style></head><body>
<h1>ساعة الأذان</h1><div id="s">جاري ضبط الوقت من هذا الهاتف...</div>
<button onclick="go()">ضبط الوقت مرة أخرى</button>
<script>function go(){fetch('/set?t='+Date.now()).then(r=>r.text()).then(t=>{document.getElementById('s').innerHTML=t})
.catch(()=>{document.getElementById('s').textContent='تعذر الاتصال بالساعة'})}go()</script></body></html>)HTML";

void handleSet() {
  if (!web.hasArg("t")) { web.send(400, "text/plain", "missing t"); return; }
  double ms = web.arg("t").toDouble();
  time_t t = (time_t)(ms / 1000.0);
  if (t < 1767225600) {  // before 2026: the phone's clock is wrong too
    web.send(200, "text/html; charset=utf-8", "وقت الهاتف غير صحيح، اضبطه أولاً");
    return;
  }
  setSystemTime(t);
  if (rtcOk) rtc.adjust(DateTime((uint32_t)t));
  timeKnown = true;
  Serial.printf("time from phone: %ld\n", (long)t);
  web.send(200, "text/html; charset=utf-8", "✓ تم ضبط الوقت. يمكنك قطع الاتصال الآن");
}

void handlePortal() {
  web.send_P(200, "text/html; charset=utf-8", PAGE);
}

void handleNtp() {
  int len = ntp.parsePacket();
  if (len < 48) return;
  uint8_t req[48], res[48] = {0};
  ntp.read(req, 48);
  if (!timeKnown) return;  // never hand out a wrong time

  struct timeval tv;
  gettimeofday(&tv, nullptr);
  uint32_t sec = (uint32_t)tv.tv_sec + NTP_EPOCH_OFFSET;
  uint32_t frac = (uint32_t)((double)tv.tv_usec * 4294.967296);

  res[0] = 0x24;   // LI=0, version 4, mode 4 (server)
  res[1] = 1;      // stratum 1 (local reference clock)
  res[2] = req[2]; // poll
  res[3] = 0xEC;   // precision ~ 2^-20
  memcpy(&res[12], "RTC\0", 4);  // reference id
  for (int i = 0; i < 8; i++) res[24 + i] = req[40 + i];  // originate = client's transmit
  auto put = [&](int off) {
    res[off] = sec >> 24; res[off + 1] = sec >> 16; res[off + 2] = sec >> 8; res[off + 3] = sec;
    res[off + 4] = frac >> 24; res[off + 5] = frac >> 16; res[off + 6] = frac >> 8; res[off + 7] = frac;
  };
  put(16);  // reference
  put(32);  // receive
  put(40);  // transmit
  ntp.beginPacket(ntp.remoteIP(), ntp.remotePort());
  ntp.write(res, 48);
  ntp.endPacket();
}

void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);
  rtcOk = rtc.begin();
  Serial.println(rtcOk ? "DS3231 found" : "DS3231 NOT found: check wiring");
  loadTimeFromRtc();

  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(AP_SSID);  // open network
  dns.start(53, "*", WiFi.softAPIP());  // every name -> this clock, so time.android.com resolves here
  WiFi.config(HOME_IP, HOME_GW, HOME_MASK, HOME_GW);
  WiFi.begin(HOME_SSID, HOME_PASS);
  ntp.begin(123);
  web.on("/set", handleSet);
  web.onNotFound(handlePortal);  // every captive-portal probe gets the page, so it pops up by itself
  web.begin();
  Serial.printf("open network %s at %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());
}

void loop() {
  dns.processNextRequest();
  web.handleClient();
  handleNtp();

  static unsigned long lastTry = 0;
  bool due = lastInternetSync == 0 ? millis() - lastTry > 30000UL : millis() - lastInternetSync > 3600000UL;
  if (WiFi.status() == WL_CONNECTED && (lastTry == 0 || due)) {
    lastTry = millis();
    syncFromInternet();
  }
  // Keep the ESP32 clock tied to the DS3231 (it drifts less) when there is no internet.
  static unsigned long lastRtcRead = 0;
  if (rtcOk && timeKnown && millis() - lastInternetSync > 3600000UL && millis() - lastRtcRead > 600000UL) {
    lastRtcRead = millis();
    setSystemTime(rtc.now().unixtime());
  }
  delay(2);
}
