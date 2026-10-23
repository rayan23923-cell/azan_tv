package com.azantv.app;

import android.os.Handler;
import android.os.Looper;

import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Collections;

/**
 * Tiny web server the phone reaches by scanning the QR code: its page sends the phone's clock
 * back here, which fixes the TV clock when there is no internet.
 */
public final class TimeServer {
    public static final int PORT = 8080;

    public interface Listener {
        void onPhoneTime(long phoneMillis);
        void onSettingsChanged();
        void onTestAzan(int azan);
        void onStopAzan();
    }

    private static final String PAGE = "<!doctype html><html dir='rtl'><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>ضبط وقت التلفزيون</title>"
            + "<style>body{font-family:sans-serif;background:#06261D;color:#fff;text-align:center;padding:48px 16px}"
            + "h1{color:#E8BE5A}#s{font-size:22px;margin-top:24px}button{font-size:20px;padding:12px 28px;margin-top:28px;"
            + "border:0;border-radius:12px;background:#E8BE5A;color:#06261D}</style></head><body>"
            + "<h1>مواقيت الأذان</h1><div id='s'>جاري إرسال الوقت من هذا الهاتف...</div>"
            + "<button onclick='go()'>إرسال مرة أخرى</button><br><a href='/settings' style='color:#E8BE5A;font-size:20px;display:inline-block;margin-top:28px'>إعدادات الأذان</a>"
            + "<script>function go(){fetch('/set?t='+Date.now()).then(function(r){return r.text()})"
            + ".then(function(t){document.getElementById('s').textContent=t})"
            + ".catch(function(){document.getElementById('s').textContent='تعذر الاتصال بالتلفزيون'})}go()</script>"
            + "</body></html>";

    /** Settings page: built in the browser from /api/settings, each change saved at once. */
    private static final String SETTINGS_PAGE = "<!doctype html><html dir='rtl'><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>إعدادات الأذان</title>"
            + "<style>body{font-family:sans-serif;background:#06261D;color:#fff;margin:0;padding:16px}"
            + "h1{color:#E8BE5A;text-align:center;font-size:24px}h2{color:#E8BE5A;font-size:19px;margin:22px 0 8px}"
            + ".r{display:flex;align-items:center;justify-content:space-between;gap:10px;background:#0B3A2C;"
            + "border-radius:12px;padding:10px 12px;margin:6px 0}.r label{flex:1;font-size:16px}"
            + "input[type=number]{width:70px;font-size:17px;padding:6px;border-radius:8px;border:0;text-align:center}"
            + "select{font-size:16px;padding:6px;border-radius:8px;border:0;max-width:55%}"
            + "input[type=checkbox]{width:26px;height:26px}.ok{color:#7CE38B;width:18px}"
            + "#m{text-align:center;color:#E8BE5A}a{color:#E8BE5A}</style></head><body>"
            + "<h1>إعدادات الأذان - التلفزيون</h1><div id='m'>جاري التحميل...</div>"
            + "<h2>الموقع الدقيق للمسجد</h2><div class='r' style='display:block'>"
            + "<div id='lc' style='margin-bottom:8px'>المواقيت محسوبة على المدينة المختارة</div>"
            + "<button onclick='gps()' style='width:100%;font-size:17px;padding:10px;border:0;border-radius:10px;background:#E8BE5A;color:#06261D'>استخدم موقع الموبايل الحالي</button>"
            + "<p style='font-size:14px;opacity:.8'>أو الصق رابط كوكل ماب أو الإحداثيات، مثل 35.4681, 44.3922 - من كوكل ماب: اضغط مطولاً على المكان وانسخ الأرقام اللي تطلع</p>"
            + "<input id='li' placeholder='الإحداثيات أو الرابط' style='width:100%;box-sizing:border-box;font-size:16px;padding:8px;border-radius:8px;border:0;direction:ltr'>"
            + "<input id='ln' placeholder='اسم المسجد أو المنطقة - اختياري' style='width:100%;box-sizing:border-box;font-size:16px;padding:8px;border-radius:8px;border:0;margin-top:8px'>"
            + "<div style='display:flex;gap:8px;margin-top:8px'><button onclick='paste()' style='flex:1;font-size:16px;padding:8px;border:0;border-radius:10px'>حفظ الموقع</button>"
            + "<button onclick='clr()' style='flex:1;font-size:16px;padding:8px;border:0;border-radius:10px'>رجوع للمدينة</button></div>"
            + "<div id='ls' style='margin-top:8px;color:#E8BE5A'></div></div>"
            + "<h2>صوت الأذان</h2><div id='au'></div>"
            + "<div id='f'></div>"
            + "<p style='text-align:center'><a href='/'>ضبط وقت التلفزيون من الموبايل</a></p>"
            + "<p style='text-align:center'><a href='/app.apk' download='AzanTV.apk'>⬇ تنزيل تطبيق الأذان - لتنصيبه على تلفزيون ثاني</a></p>"
            + "<script>var B=location.search.indexOf('tv=')>0?'http://'+location.search.split('tv=')[1].split('&')[0]:'';"
            + "function ls(t){document.getElementById('ls').textContent=t}"
            + "function sendLoc(a,b){var n=document.getElementById('ln').value;ls('جاري الحفظ...');"
            + "fetch(B+'/api/location?lat='+a+'&lng='+b+'&name='+encodeURIComponent(n)).then(function(r){"
            + "if(!r.ok)throw 0;ls('✓ انحفظ الموقع '+(+a).toFixed(5)+', '+(+b).toFixed(5)+' والمواقيت تنحسب عليه');showLoc(a,b,n)})"
            + ".catch(function(){ls('✗ ما انحفظ، تأكد من الأرقام')})}"
            + "function showLoc(a,b,n){document.getElementById('lc').textContent=a==null?'المواقيت محسوبة على المدينة المختارة':"
            + "'الموقع الحالي: '+(n?n+' - ':'')+(+a).toFixed(5)+', '+(+b).toFixed(5)}"
            + "function gps(){if(!navigator.geolocation||!window.isSecureContext){ls('المتصفح يمنع الموقع على هذا العنوان - الصق الإحداثيات من كوكل ماب تحت');return}"
            + "ls('جاري تحديد الموقع...');navigator.geolocation.getCurrentPosition(function(p){sendLoc(p.coords.latitude,p.coords.longitude)},"
            + "function(){ls('ما سمح المتصفح بالموقع - الصق الإحداثيات من كوكل ماب تحت')},{enableHighAccuracy:true,timeout:20000})}"
            + "function paste(){var t=decodeURIComponent(document.getElementById('li').value),m=(t.match(/!3d-?\\d+\\.\\d+!4d-?\\d+\\.\\d+/g)||[]).pop();"
            + "m=(m&&m.match(/!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)/))"
            + "||t.match(/@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)/)||t.match(/(-?\\d{1,2}\\.\\d+)\\s*[,،\\s]\\s*(-?\\d{1,3}\\.\\d+)/);"
            + "if(!m){ls(t.indexOf('goo.gl')>=0?'الرابط المختصر ما يشتغل - افتحه وانسخ الأرقام':'ما لكيت إحداثيات بالنص');return}sendLoc(m[1],m[2])}"
            + "var SL=[['azan','الأذان العادي'],['fajr','أذان الفجر - اختياري'],['first','الأذان الأول للفجر والجمعة - اختياري'],['iqama','صوت الإقامة - اختياري']];"
            + "function au(d){var w=document.getElementById('au');w.innerHTML='';SL.forEach(function(s){var x=d[s[0]],r=document.createElement('div');"
            + "r.className='r';r.style.display='block';var h='<b>'+s[1]+'</b><div style=\"font-size:14px;opacity:.85;margin:4px 0\">'+"
            + "(x?'ملف مرفوع: '+Math.floor(x.seconds/60)+':'+('0'+x.seconds%60).slice(-2)+' دقيقة - '+(x.size/1048576).toFixed(1)+' ميغا':"
            + "(s[0]=='azan'?'الأذان الحالي من ذاكرة التلفزيون':s[0]=='iqama'?'نغمة قصيرة':'يستخدم الأذان العادي'))+'</div>';r.innerHTML=h;"
            + "var i=document.createElement('input');i.type='file';i.accept='audio/*,.mp3,.m4a,.aac,.ogg,.opus,.wav,.flac,.amr,.3gp';i.style.width='100%';i.onchange=function(){up(s[0],i.files[0],st)};"
            + "var st=document.createElement('div');st.style.color='#E8BE5A';r.appendChild(i);"
            + "var p=document.createElement('button');p.textContent='▶ تجربة على التلفزيون';p.onclick=function(){fetch(B+'/api/play?slot='+s[0]);st.textContent='يشتغل على التلفزيون - اضغط إيقاف أو أي زر بالريموت'};"
            + "var q=document.createElement('button');q.textContent='■ إيقاف';q.onclick=function(){fetch(B+'/api/play?slot=stop')};"
            + "var bx=document.createElement('div');bx.style.cssText='display:flex;gap:8px;margin-top:8px';[p,q].forEach(function(b){b.style.cssText='flex:1;font-size:15px;padding:8px;border:0;border-radius:10px';bx.appendChild(b)});"
            + "if(x){var dl=document.createElement('button');dl.textContent='حذف';dl.style.cssText='flex:1;font-size:15px;padding:8px;border:0;border-radius:10px';"
            + "dl.onclick=function(){fetch(B+'/api/audio?slot='+s[0]+'&delete=1').then(function(r){return r.json()}).then(au)};bx.appendChild(dl)}"
            + "r.appendChild(bx);r.appendChild(st);w.appendChild(r)})}"
            + "function up(slot,f,st){if(!f)return;if(f.size>20971520){st.textContent='الملف أكبر من 20 ميغا';return}"
            + "var ext=(f.name.split('.').pop()||'mp3'),x=new XMLHttpRequest();x.open('POST',B+'/api/audio?slot='+slot+'&ext='+encodeURIComponent(ext));"
            + "x.upload.onprogress=function(e){if(e.lengthComputable)st.textContent='جاري الرفع '+Math.round(e.loaded*100/e.total)+'%'};"
            + "x.onload=function(){if(x.status==200){st.textContent='✓ انرفع';au(JSON.parse(x.responseText))}else{var m='';try{m=JSON.parse(x.responseText).error}catch(e){}"
            + "st.textContent='✗ ما انرفع: '+(m||('خطأ '+x.status))}};"
            + "x.onerror=function(){st.textContent='✗ انقطع الاتصال بالتلفزيون - تأكد الموبايل على نفس الواي فاي'};"
            + "st.textContent='جاري الرفع...';x.send(f)}"
            + "fetch(B+'/api/audio').then(function(r){return r.json()}).then(au);"
            + "function clr(){fetch(B+'/api/location?clear=1').then(function(){ls('✓ رجعت المواقيت على المدينة');showLoc(null)})}"
            + "function save(k,v,o){o.textContent='…';fetch(B+'/api/set?'+k+'='+encodeURIComponent(v))"
            + ".then(function(r){o.textContent=r.ok?'✓':'✗'}).catch(function(){o.textContent='✗'})}"
            + "fetch(B+'/api/settings').then(function(r){return r.json()}).then(function(d){var f=document.getElementById('f'),sec='';"
            + "if(d.location){showLoc(d.location.lat,d.location.lng,d.location.name);document.getElementById('ln').value=d.location.name||''}"
            + "d.fields.forEach(function(x){if(x.section!=sec){sec=x.section;var h=document.createElement('h2');h.textContent=sec;f.appendChild(h)}"
            + "var r=document.createElement('div');r.className='r';var l=document.createElement('label');l.textContent=x.label;r.appendChild(l);"
            + "var e,ok=document.createElement('span');ok.className='ok';"
            + "if(x.type=='b'){e=document.createElement('input');e.type='checkbox';e.checked=x.value;e.onchange=function(){save(x.key,e.checked?1:0,ok)}}"
            + "else if(x.type=='s'){e=document.createElement('select');x.options.forEach(function(t,i){var o=document.createElement('option');"
            + "o.value=i+x.first;o.textContent=t;e.appendChild(o)});e.value=x.value;e.onchange=function(){save(x.key,e.value,ok)}}"
            + "else{e=document.createElement('input');e.type='number';e.min=x.min;e.max=x.max;e.value=x.value;"
            + "e.onchange=function(){save(x.key,e.value,ok)}}"
            + "r.appendChild(e);r.appendChild(ok);f.appendChild(r)});document.getElementById('m').textContent='كل تغيير ينحفظ مباشرة'})"
            + ".catch(function(){document.getElementById('m').textContent='تعذر الاتصال بالتلفزيون'})</script></body></html>";

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket server;

    private final android.content.Context context;

    public TimeServer(android.content.Context c, Listener l) {
        context = c.getApplicationContext();
        listener = l;
    }

    public synchronized boolean running() { return server != null; }

    public synchronized void start() {
        if (server != null) return;
        try {
            server = new ServerSocket(PORT);
        } catch (Exception e) {
            server = null;
            return;
        }
        final ServerSocket s = server;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(s); }
        }, "azan-time-server");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        if (server == null) return;
        try { server.close(); } catch (Exception ignored) { }
        server = null;
    }

    private void loop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket c = s.accept();
                c.setSoTimeout(30000); // phones pause mid-upload on weak Wi-Fi
                handle(c);
            } catch (Exception ignored) {
            }
        }
    }

    private void handle(Socket c) {
        try {
            java.io.InputStream in = new java.io.BufferedInputStream(c.getInputStream());
            String line = readLine(in);
            String path = line != null && line.split(" ").length > 1 ? line.split(" ")[1] : "/";
            long length = 0;
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
                if (h.toLowerCase(java.util.Locale.US).startsWith("content-length:")) {
                    try { length = Long.parseLong(h.substring(15).trim()); } catch (NumberFormatException ignored) { }
                }
            }
            String body;
            String type = "text/html; charset=utf-8";
            String status = "200 OK";
            if (path.startsWith("/set?t=")) {
                type = "text/plain; charset=utf-8";
                final long t = (long) Double.parseDouble(path.substring(7).split("&")[0]);
                if (t < 1767225600000L) {
                    body = "وقت الهاتف غير صحيح، اضبطه أولاً";
                } else {
                    body = "✓ تم ضبط وقت التلفزيون";
                    main.post(new Runnable() {
                        @Override public void run() { listener.onPhoneTime(t); }
                    });
                }
            } else if (path.startsWith("/api/settings")) {
                type = "application/json; charset=utf-8";
                body = WebSettings.json(context);
            } else if (path.startsWith("/api/location?")) {
                type = "text/plain; charset=utf-8";
                java.util.Map<String, String> q = new java.util.HashMap<String, String>();
                for (String kv : path.substring(14).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) q.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"), URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                }
                Config cfg = new Config(context);
                body = "ok";
                if (q.containsKey("clear")) {
                    cfg.clearCustomLocation();
                } else {
                    try {
                        double lat = Double.parseDouble(q.get("lat")), lng = Double.parseDouble(q.get("lng"));
                        if (!Config.validLocation(lat, lng)) throw new IllegalArgumentException();
                        cfg.setCustomLocation(lat, lng, q.get("name"));
                    } catch (Exception e) {
                        status = "400 Bad Request";
                        body = "bad";
                    }
                }
                main.post(new Runnable() {
                    @Override public void run() { listener.onSettingsChanged(); }
                });
            } else if (path.startsWith("/api/audio")) {
                type = "application/json; charset=utf-8";
                java.util.Map<String, String> q = query(path);
                String slot = q.get("slot");
                if (slot != null && !java.util.Arrays.asList(AzanFiles.SLOTS).contains(slot)) slot = null;
                if (line.startsWith("POST") && slot != null) {
                    String err = saveAudio(in, length, slot, q.get("ext"));
                    if (err != null) status = "400 Bad Request";
                    body = err != null ? new org.json.JSONObject().put("error", err).toString() : audioJson();
                } else {
                    if (slot != null && q.containsKey("delete")) {
                        java.io.File f = AzanFiles.uploaded(context, slot);
                        if (f != null) f.delete();
                    }
                    body = audioJson();
                }
            } else if (path.startsWith("/api/play")) {
                type = "text/plain; charset=utf-8";
                String slot = query(path).get("slot");
                final int azan = "iqama".equals(slot) ? -1 : "fajr".equals(slot) ? PrayTimes.FAJR : "first".equals(slot) ? Config.FAJR_FIRST : PrayTimes.DHUHR;
                final boolean stop = "stop".equals(slot);
                body = "ok";
                main.post(new Runnable() {
                    @Override public void run() {
                        if (stop) listener.onStopAzan();
                        else listener.onTestAzan(azan);
                    }
                });
            } else if (path.startsWith("/app.apk")) { // this app, to install on another TV from its browser
                java.io.File apk = new java.io.File(context.getApplicationInfo().sourceDir);
                OutputStream out = c.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: " + apk.length()
                        + "\r\nContent-Disposition: attachment; filename=\"AzanTV.apk\"\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                java.io.InputStream fin = new java.io.FileInputStream(apk);
                byte[] buf = new byte[16384];
                int n;
                while ((n = fin.read(buf)) > 0) out.write(buf, 0, n);
                fin.close();
                out.flush();
                return;
            } else if (path.startsWith("/api/status")) {
                type = "application/json; charset=utf-8";
                body = status(context);
            } else if (path.startsWith("/api/info")) {
                type = "application/json; charset=utf-8";
                body = "{\"app\":\"AzanTV\",\"port\":" + PORT + "}";
            } else if (path.startsWith("/api/set?")) {
                type = "text/plain; charset=utf-8";
                boolean all = true;
                for (String kv : path.substring(9).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) { all = false; continue; }
                    String k = URLDecoder.decode(kv.substring(0, eq), "UTF-8");
                    String v = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                    all &= WebSettings.set(context, k, v);
                }
                if (!all) status = "400 Bad Request";
                body = all ? "ok" : "bad";
                main.post(new Runnable() {
                    @Override public void run() { listener.onSettingsChanged(); }
                });
            } else if (path.startsWith("/settings")) {
                body = SETTINGS_PAGE;
            } else {
                body = PAGE;
            }
            byte[] b = body.getBytes(Charset.forName("UTF-8"));
            OutputStream out = c.getOutputStream();
            out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                    + "\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            out.write(b);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { c.close(); } catch (Exception ignored) { }
        }
    }

    private static String readLine(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) >= 0 && ch != '\n') {
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) break;
        }
        return ch < 0 && b.size() == 0 ? null : b.toString("UTF-8");
    }

    private static java.util.Map<String, String> query(String path) throws java.io.IOException {
        java.util.Map<String, String> q = new java.util.HashMap<String, String>();
        int i = path.indexOf('?');
        if (i < 0) return q;
        for (String kv : path.substring(i + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) q.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"), URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
            else if (!kv.isEmpty()) q.put(kv, "");
        }
        return q;
    }

    private static final long MAX_AUDIO = 20L * 1024 * 1024;

    /** Streams an uploaded recording to disk and keeps it only if Android can play it. */
    private String saveAudio(java.io.InputStream in, long length, String slot, String ext) {
        if (length <= 0) return "الملف فارغ";
        if (length > MAX_AUDIO) return "الملف أكبر من 20 ميغا";
        String e = ext == null ? "" : "." + ext.toLowerCase(java.util.Locale.US).replace(".", "");
        if (!java.util.Arrays.asList(AzanFiles.EXTS).contains(e)) e = ".mp3";
        java.io.File dir = context.getExternalFilesDir(null);
        if (dir == null) return "ذاكرة التلفزيون غير متاحة";
        java.io.File tmp = new java.io.File(dir, slot + ".upload");
        try {
            java.io.OutputStream out = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            long left = length;
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                out.write(buf, 0, n);
                left -= n;
            }
            out.close();
            if (left > 0) {
                tmp.delete();
                return "انقطع الرفع قبل ما يكمل";
            }
            if (playableMillis(tmp) < 1000) {
                tmp.delete();
                return "التلفزيون ما يكدر يشغّل هذا الملف - حوّله لـ mp3";
            }
            java.io.File old = AzanFiles.uploaded(context, slot);
            if (old != null) old.delete();
            if (!tmp.renameTo(new java.io.File(dir, slot + e))) throw new java.io.IOException("rename");
            return null;
        } catch (Exception x) {
            tmp.delete();
            return "تعذر حفظ الملف";
        }
    }

    /** Length of a recording, from its tags or else by opening it in the player; 0 when unplayable. */
    private static long playableMillis(java.io.File f) {
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(f.getPath());
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            r.release();
            if (d != null && Long.parseLong(d) > 0) return Long.parseLong(d);
        } catch (Exception ignored) {
        }
        android.media.MediaPlayer p = new android.media.MediaPlayer();
        try {
            p.setDataSource(f.getPath());
            p.prepare();
            return p.getDuration();
        } catch (Exception e) {
            return 0;
        } finally {
            p.release();
        }
    }

    private String audioJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (String slot : AzanFiles.SLOTS) {
                java.io.File f = AzanFiles.uploaded(context, slot);
                if (f == null) continue;
                long sec = playableMillis(f) / 1000;
                o.put(slot, new org.json.JSONObject().put("size", f.length()).put("seconds", sec).put("file", f.getName()));
            }
            java.io.File def = AzanFiles.find(context, false);
            o.put("default", def == null ? "" : def.getPath());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String status(android.content.Context c) {
        try {
            android.content.SharedPreferences sp = c.getSharedPreferences("azan", android.content.Context.MODE_PRIVATE);
            Config cfg = new Config(c);
            long now = cfg.now();
            long[] next = cfg.nextAzan(now + 1000);
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("now", now).put("clockValid", cfg.clockValid(now)).put("azanAll", cfg.azanOn());
            org.json.JSONArray on = new org.json.JSONArray();
            for (int i = 0; i < 6; i++) on.put(cfg.azanEnabledSetting(i));
            o.put("azanPerPrayer", on);
            o.put("nextAzan", next == null ? 0 : next[1]).put("nextPrayer", next == null ? -1 : next[0]);
            o.put("alarmAt", sp.getLong("alarmAt", -1)).put("alarmPrayer", sp.getInt("alarmPrayer", -1))
                    .put("alarmSetAt", sp.getLong("alarmSetAt", -1));
            o.put("sleep", cfg.sleepMode()).put("espVersion", sp.getInt("espVersion", -1));
            o.put("appVersion", Updater.myVersion(c)).put("clockApkId", sp.getLong("apkId", -1))
                    .put("clockApkVersion", sp.getInt("apkVersion", -1)).put("updateAskedAt", sp.getLong("apkAskedAt", 0));
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e + "\"}";
        }
    }

    /** The TV's address on the local network, or null when it has none. */
    public static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
