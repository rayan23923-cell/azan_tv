#!/bin/bash
set -e
cd "$(dirname "$0")"
T=/opt/android-sdk/dl
rm -rf build && mkdir -p build/classes build/res
$T/aapt2 compile --dir res -o build/res.zip
$T/aapt2 link -o build/unsigned.apk -I $T/android-framework.jar --manifest AndroidManifest.xml -A assets \
  --java build/gen --min-sdk-version 24 --target-sdk-version 28 build/res.zip
javac -encoding UTF-8 -nowarn --release 8 -cp $T/android-all-12-robolectric-7732740.jar:$T/qrcodegen-1.8.0.jar \
  -d build/classes $(find src build/gen -name '*.java') 2>&1 | grep -v -E "^warning|bootstrap|obsolete|-Xlint|^[0-9]+ warning" || true
test -f build/classes/com/azantv/app/MainActivity.class
(cd build/classes && unzip -q -o $T/qrcodegen-1.8.0.jar 'io/*' -x '*QrSegmentAdvanced*')
java -cp $T/dalvik-dx-16.0.1.jar com.android.dx.command.Main --dex --min-sdk-version=21 --output=build/classes.dex build/classes
cp build/unsigned.apk build/app.apk
(cd build && zip -q -j app.apk classes.dex)
[ -f keystore.jks ] || keytool -genkeypair -keystore keystore.jks -storepass azantv -keypass azantv \
  -alias azan -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Azan TV" >/dev/null 2>&1
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED -cp $T/apksig-2.3.0.jar:tools Sign build/app.apk AzanTV.apk keystore.jks azantv azan
ls -la AzanTV.apk
