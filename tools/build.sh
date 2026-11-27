#!/bin/sh
# Build the Herald app (co.tbrkr.herald) in a container: run the JVM tests of the pure
# parts, then build the signed APK, upload-signed AAB and release-signed universal APK.
# usage: tools/build.sh      (from any directory)
# env:   DOCKER (default "sudo docker"), HERALD_KEYS (default $HOME/.config/herald)
# Keep the release and upload keystores and their password files created on the first run.
# Prints the PASS lines of the tests, then `built <path> version <name>`.
# PLAN-20261125-herald-v1.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/.." && pwd)
DOCKER=${DOCKER:-sudo docker}
image=herald-build:36-m3-1.11.1-bt1.18.3
keys=${HERALD_KEYS:-$HOME/.config/herald}
out=$repo/out
version=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$repo/app/AndroidManifest.xml")
[ -n "$version" ] || { echo "no versionName in $repo/app/AndroidManifest.xml" >&2; exit 1; }

if ! $DOCKER image inspect "$image" >/dev/null 2>&1; then
  $DOCKER build -t "$image" "$here" >&2
fi

mkdir -p "$keys" "$out"
chmod 700 "$keys"
rm -f "$out/herald.apk" "$out/herald.aab" "$out/herald-universal.apk"

# Signing commands read passwords from files. The build runs offline.
$DOCKER run -i --rm --network none \
  -v "$repo:/src:ro" -v "$out:/out" -v "$keys:/keys" \
  --user "$(id -u):$(id -g)" -e HOME=/tmp \
  "$image" sh -s <<'BUILD'
set -eu
SDK=/opt/android-sdk
BT=$SDK/build-tools/36.1.0
AJ=$SDK/platforms/android-36/android.jar
w=/tmp/build
p=/src/app/src/co/tbrkr/herald
rm -rf "$w"
mkdir -p "$w/test" "$w/classes" "$w/dex"

javac -d "$w/test" "$p/TlsIdentity.java" "$p/Http.java" "$p/Json.java" "$p/Api.java" "$p/Auth.java" "$p/EventLog.java" "$p/Backend.java" \
  "$p/Compat.java" "$p/MqttTopics.java" "$p/Ntfy.java" "$p/SoundDecision.java" "$p/Settings.java" "$p/Style.java" "$p/Setup.java" "$p/SettingsModel.java" "$p/PairingText.java" "$p/SpokenCards.java" "$p/ChipLayout.java" "$p/ChipPulse.java" "$p/KeyPolicy.java" "$p/CardFrame.java" "$p/Look.java" "$p/Category.java" "$p/Cidr.java" \
  "$p/AnnounceSpec.java" "$p/RequestIds.java" "$p/CardSpec.java" "$p/CardBoard.java" "$p/MediaState.java" "$p/ChipSpec.java" "$p/ChipBoard.java" "$p/ImageStore.java" \
  "$p/ClipStore.java" "$p/Chime.java" "$p/AudioTiming.java" /src/app/test/co/tbrkr/herald/PureTest.java >&2
keytool -genkeypair -keystore "$w/tls-test.p12" -storetype PKCS12 -storepass herald-test \
  -keypass herald-test -alias test -keyalg EC -groupname secp256r1 -dname CN=herald-test -validity 2
java -ea -Dherald.tls.fixture="$w/tls-test.p12" -cp "$w/test" co.tbrkr.herald.PureTest

ensure_key() (
  name=$1
  alias=$2
  subject=$3
  key=/keys/$name.jks
  pass=/keys/$name.pass
  if [ -f "$key" ]; then
    [ -s "$pass" ] || { echo "$name.jks has no $name.pass next to it" >&2; exit 1; }
    exit 0
  fi
  umask 077
  rm -f "$pass"
  tr -dc "A-Za-z0-9" </dev/urandom | head -c 32 >"$pass"
  [ "$(wc -c <"$pass")" -eq 32 ] || { echo "could not make the key password" >&2; exit 1; }
  keytool -genkeypair -keystore "$key" -storetype PKCS12 -alias "$alias" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "$subject" \
    -storepass:file "$pass" -keypass:file "$pass" >&2
  echo "created a new $name key in the key directory" >&2
)
ensure_key release herald CN=herald
ensure_key upload upload CN=herald-upload

module=$w/module
mkdir -p "$w/libs" "$w/gen" "$w/paho" "$module/root" "$module/manifest"
classpath=$AJ
set --
while read -r file digest role url; do
  [ "$role" != tool ] || continue
  case "$file" in
    *.aar)
      jar="$w/libs/${file%.aar}.jar"
      unzip -p "/opt/deps/$file" classes.jar > "$jar"
      ;;
    *.jar) jar="/opt/deps/$file" ;;
    *) echo "unsupported library $file" >&2; exit 1 ;;
  esac
  classpath="$classpath:$jar"
  case "$role" in
    runtime)
      set -- "$@" "$jar"
      unzip -Z1 "$jar" > "$w/jar-entries"
      awk '!/\/$|\.class$/ && !/^META-INF\/(MANIFEST\.MF|[^/]*\.(SF|RSA|EC|DSA))$/' \
        "$w/jar-entries" > "$w/resources"
      while IFS= read -r resource; do
        unzip -qo "$jar" "$resource" -d "$module/root"
      done < "$w/resources"
      ;;
    compile) set -- "$@" --classpath "$jar" ;;
    *) echo "unknown library role $role" >&2; exit 1 ;;
  esac
done < /opt/deps/deps.lock

unzip -q /opt/deps/media3-exoplayer-1.11.1.aar 'res/*' -d "$w"
mv "$w/res" "$w/exo-res"
aapt2 compile --dir "$w/exo-res" -o "$w/exo-res.zip"
aapt2 compile --dir /src/app/res -o "$w/app-res.zip"
link_resources() {
  aapt2 link --manifest /src/app/AndroidManifest.xml -I "$AJ" \
    -R "$w/exo-res.zip" -R "$w/app-res.zip" --auto-add-overlay \
    --extra-packages androidx.media3.exoplayer --min-sdk-version 26 --target-sdk-version 36 "$@"
}
link_resources -o "$w/base.apk" --java "$w/gen"
link_resources -o "$w/base-proto.apk" --proto-format
find /src/app/src "$w/gen" -name '*.java' > "$w/sources"
javac -source 11 -target 11 -Xlint:-options -cp "$classpath" -d "$w/classes" @"$w/sources"
jar cf "$w/app.jar" -C "$w/classes" .
d8 --lib "$AJ" --min-api 26 --output "$w/dex" "$w/app.jar" "$@"
(cd "$w/dex" && zip -q ../base.apk classes*.dex)

descriptor=META-INF/services/org.eclipse.paho.client.mqttv3.spi.NetworkModuleFactory
unzip -q /opt/deps/org.eclipse.paho.client.mqttv3-1.2.5.jar "$descriptor" '*.properties' -d "$w/paho"
(cd "$w/paho" && zip -qr "$w/base.apk" .)
dexdump "$w"/dex/classes*.dex > "$w/dexdump"
grep -Fq "Class descriptor  : 'Landroidx/media3/exoplayer/R\$string;'" "$w/dexdump"
echo "CHECK exoplayer R resolves"
unzip -Z1 "$w/base.apk" > "$w/entries"
grep -Fxq "$descriptor" "$w/entries"
echo "CHECK paho service descriptor"
for factory in hls/HlsMediaSource dash/DashMediaSource; do
  grep -Fq "Class descriptor  : 'Landroidx/media3/exoplayer/${factory}\$Factory;'" "$w/dexdump"
done
echo "CHECK hls and dash modules"
aapt2 dump badging "$w/base.apk" > "$w/badging"
grep -Fxq "minSdkVersion:'26'" "$w/badging"
grep -Fxq "targetSdkVersion:'36'" "$w/badging"
echo "CHECK min 26 target 36"
"$BT/zipalign" -f 4 "$w/base.apk" "$w/aligned.apk"
"$BT/apksigner" sign --ks /keys/release.jks --ks-key-alias herald \
  --ks-pass file:/keys/release.pass --out /out/herald.apk "$w/aligned.apk" >&2
rm -f /out/herald.apk.idsig
"$BT/apksigner" verify /out/herald.apk >&2
unzip -q "$w/base-proto.apk" -d "$module"
mv "$module/AndroidManifest.xml" "$module/manifest/"
cp -r "$w/dex" "$module/"
(cd "$module" && zip -qr "$w/base.zip" .)
bundletool() {
  java -jar /opt/deps/bundletool-all-1.18.3.jar "$@"
}
bundletool build-bundle --modules="$w/base.zip" --output="$w/unsigned.aab"
jarsigner -keystore /keys/upload.jks -storepass:file /keys/upload.pass -keypass:file /keys/upload.pass \
  -signedjar "$w/herald.aab" "$w/unsigned.aab" upload

# A self-signed certificate is expected. Verify the JAR signature and bind it to the upload alias.
jarsigner -verify "$w/herald.aab" > "$w/verification"
grep -Fxq 'jar verified.' "$w/verification"
keytool -printcert -jarfile "$w/herald.aab" > "$w/signer"
signer=$(sed -n 's/^[[:space:]]*SHA256: //p' "$w/signer" | tr -d ':' | tr 'A-F' 'a-f')
key_digest() {
  keytool -exportcert -keystore "/keys/$1.jks" -storepass:file "/keys/$1.pass" -alias "$2" \
    > "$w/cert.der" || return 1
  sha256sum "$w/cert.der" | cut -d' ' -f1
}
upload_digest=$(key_digest upload upload)
release_digest=$(key_digest release herald)
[ "$signer" = "$upload_digest" ] && [ "$signer" != "$release_digest" ] || exit 1

bundletool build-apks --bundle="$w/herald.aab" --output="$w/herald.apks" --mode=universal \
  --ks=/keys/release.jks --ks-key-alias=herald --ks-pass=file:/keys/release.pass \
  --key-pass=file:/keys/release.pass
unzip -q "$w/herald.apks" universal.apk -d "$w"
universal=$w/universal.apk
"$BT/apksigner" verify --print-certs "$universal" > "$w/universal-signer"
grep -Fxq "Signer #1 certificate SHA-256 digest: $release_digest" "$w/universal-signer"
unzip -q "$universal" 'classes*.dex' -d "$w/universal-dex"
diff -qr "$w/dex" "$w/universal-dex"
unzip -Z1 "$universal" > "$w/universal-entries"
grep -Fxq "$descriptor" "$w/universal-entries"
grep -Fxq 'org/eclipse/paho/client/mqttv3/internal/nls/messages.properties' "$w/universal-entries"
aapt2 dump badging "$universal" > "$w/universal-badging"
grep -Fxq "minSdkVersion:'26'" "$w/universal-badging"
grep -Fxq "targetSdkVersion:'36'" "$w/universal-badging"
grep -Eq "^[[:space:]]*uses-feature: name='android[.]software[.]leanback'$" "$w/universal-badging"
echo "CHECK aab upload signature and universal apk"
cp "$universal" /out/herald-universal.apk
cp "$w/herald.aab" /out/herald.aab
BUILD

[ -s "$out/herald.apk" ] || { echo "no APK was built" >&2; exit 1; }
echo "apk size $(wc -c < "$out/herald.apk")"
echo "built $out/herald.apk version $version"
echo "built /out/herald.aab"
