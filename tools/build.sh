#!/bin/sh
# Build the Herald app (io.github.iwinties.herald) in a container: run the JVM tests of the pure
# parts, then compile, dex, align and sign <repo>/out/herald.apk.
# usage: tools/build.sh      (from any directory)
# env:   DOCKER (default "sudo docker"), HERALD_KEYS (default $HOME/.config/herald)
# The first run creates the release signing key there (release.jks, release.pass). Keep it:
# an APK signed with another key cannot update the installed app.
# Prints the PASS lines of the tests, then `built <path> version <name>`.
# PLAN-20261125-herald-v1.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/.." && pwd)
DOCKER=${DOCKER:-sudo docker}
image=herald-build:36-m3-1.11.1
keys=${HERALD_KEYS:-$HOME/.config/herald}
out=$repo/out
version=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$repo/app/AndroidManifest.xml")
[ -n "$version" ] || { echo "no versionName in $repo/app/AndroidManifest.xml" >&2; exit 1; }

if ! $DOCKER image inspect "$image" >/dev/null 2>&1; then
  $DOCKER build -t "$image" "$here" >&2
fi

mkdir -p "$keys" "$out"
chmod 700 "$keys"
rm -f "$out/herald.apk"

# The password lives only in /keys/release.pass and reaches keytool and apksigner as
# file: arguments; it is never on a command line or in the output. The script below is
# expanded inside the container.
$DOCKER run -i --rm --network none \
  -v "$repo:/src:ro" -v "$out:/out" -v "$keys:/keys" \
  --user "$(id -u):$(id -g)" -e HOME=/tmp \
  "$image" sh -s <<'BUILD'
set -eu
SDK=/opt/android-sdk
BT=$SDK/build-tools/36.1.0
AJ=$SDK/platforms/android-36/android.jar
w=/tmp/build
p=/src/app/src/io/github/iwinties/herald
rm -rf "$w"
mkdir -p "$w/test" "$w/classes" "$w/dex"

javac -d "$w/test" "$p/Http.java" "$p/Json.java" "$p/Api.java" "$p/Auth.java" "$p/EventLog.java" "$p/Backend.java" \
  "$p/Compat.java" "$p/MqttTopics.java" "$p/SoundDecision.java" "$p/Settings.java" "$p/Style.java" "$p/SettingsModel.java" "$p/PairingText.java" "$p/SpokenCards.java" "$p/ChipLayout.java" "$p/ChipPulse.java" "$p/KeyPolicy.java" "$p/CardFrame.java" "$p/Look.java" "$p/Category.java" "$p/Cidr.java" \
  "$p/AnnounceSpec.java" "$p/RequestIds.java" "$p/CardSpec.java" "$p/CardBoard.java" "$p/MediaState.java" "$p/ChipSpec.java" "$p/ChipBoard.java" "$p/ImageStore.java" \
  "$p/ClipStore.java" "$p/Chime.java" "$p/AudioTiming.java" /src/app/test/io/github/iwinties/herald/PureTest.java >&2
java -ea -cp "$w/test" io.github.iwinties.herald.PureTest

if [ ! -f /keys/release.jks ]; then
  rm -f /keys/release.pass
  (umask 077 && tr -dc "A-Za-z0-9" </dev/urandom | head -c 32 >/keys/release.pass)
  chmod 600 /keys/release.pass
  [ "$(wc -c </keys/release.pass)" -eq 32 ] || { echo "could not make the key password" >&2; exit 1; }
  keytool -genkeypair -keystore /keys/release.jks -storetype PKCS12 -alias herald \
    -keyalg RSA -keysize 2048 -validity 10000 -dname CN=herald \
    -storepass:file /keys/release.pass -keypass:file /keys/release.pass >&2
  chmod 600 /keys/release.jks
  echo "created a new release key in the key directory" >&2
fi
[ -s /keys/release.pass ] || { echo "release.jks has no release.pass next to it" >&2; exit 1; }

mkdir -p "$w/libs" "$w/gen" "$w/paho"
classpath=$AJ
set --
while read -r file digest role url; do
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
    runtime) set -- "$@" "$jar" ;;
    compile) set -- "$@" --classpath "$jar" ;;
    *) echo "unknown library role $role" >&2; exit 1 ;;
  esac
done < /opt/deps/deps.lock

unzip -q /opt/deps/media3-exoplayer-1.11.1.aar 'res/*' -d "$w"
mv "$w/res" "$w/exo-res"
aapt2 compile --dir "$w/exo-res" -o "$w/exo-res.zip"
aapt2 compile --dir /src/app/res -o "$w/app-res.zip"
aapt2 link -o "$w/base.apk" --manifest /src/app/AndroidManifest.xml -I "$AJ" \
  -R "$w/exo-res.zip" -R "$w/app-res.zip" --auto-add-overlay --java "$w/gen" \
  --extra-packages androidx.media3.exoplayer --min-sdk-version 26 --target-sdk-version 36
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
BUILD

[ -s "$out/herald.apk" ] || { echo "no APK was built" >&2; exit 1; }
echo "apk size $(wc -c < "$out/herald.apk")"
echo "built $out/herald.apk version $version"
