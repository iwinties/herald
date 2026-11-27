#!/bin/sh
# Usage: install-adb.sh <serial | host[:port]> [--apk file]
set -eu
fail() { echo "$*" >&2; exit 1; }
case $# in
    1) apk=$(dirname "$0")/../out/herald.apk ;;
    3) [ "$2" = --apk ] || fail "expected --apk file"; apk=$3 ;;
    *) fail "usage: $0 <serial | host[:port]> [--apk file]" ;;
esac
[ -r "$apk" ] || fail "APK is unreadable: $apk"
case $1 in ''|-*) fail "invalid device target" ;; esac
serial=$1
device() { adb -s "$serial" "$@"; }
forward=
cleanup() { if [ -n "$forward" ]; then device forward --remove "tcp:$forward"; fi; }
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

# A listed serial can be a USB id, emulator name, mDNS service, or host:port.
listing=$(device devices)
listed=$(printf '%s\n' "$listing" | awk -v target="$serial" '$1 == target {print $2}')
case "$serial" in
    \[*\]:*) host=${serial%:*}; port=${serial##*:} ;;
    \[*\]) host=$serial; port=5555 ;;
    *:*:*) host="[$serial]"; port=5555 ;;
    *:*) host=${serial%:*}; port=${serial##*:} ;;
    *) host=$serial; port=5555 ;;
esac
if [ -z "$listed" ]; then
    case "$port" in ''|*[!0-9]*) fail "invalid ADB port" ;; esac
    [ "$port" -ge 1 ] && [ "$port" -le 65535 ] || fail "invalid ADB port"
    serial=$host:$port
    device connect "$serial"
elif [ "$listed" != device ]; then
    fail "ADB target $serial is $listed"
fi
[ "$(device get-state)" = device ] || fail "ADB device unavailable"
pkg=io.github.iwinties.herald
device install -r "$apk"
sdk=$(device shell getprop ro.build.version.sdk | tr -d '\r')
case $sdk in ''|*[!0-9]*) fail "invalid Android SDK version" ;; esac
if [ "$sdk" -ge 33 ]; then device shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS; fi
device shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow
device shell dumpsys deviceidle whitelist "+$pkg"
device shell am start-foreground-service -n "$pkg/.HeraldService"
version=$(device shell dumpsys package "$pkg" | sed -n 's/^[[:space:]]*versionName=//p' | tr -d '\r' | head -1)
[ -n "$version" ] || fail "installed version unavailable"
case "$serial" in
    *:[0-9]*) endpoint="http://$host:7979" ;;
    *) forward=$(device forward tcp:0 tcp:7979)
       endpoint="http://127.0.0.1:$forward" ;;
esac
info=$(curl -gfsS --noproxy '*' --retry 8 --retry-connrefused --retry-delay 1 --max-time 4 "$endpoint/v1/info")
printf '%s' "$info" | python3 -c '
import json, sys
info = json.load(sys.stdin)
if info.get("app") != "herald" or info.get("version") != sys.argv[1]:
    sys.exit("running Herald version differs from the installed package")
print("Herald", sys.argv[1], "is responding")
' "$version"
