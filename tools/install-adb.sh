#!/usr/bin/env bash
# Usage: install-adb.sh <serial | host[:port]> [--apk file] [--start-receiving] [--port n]
set -euo pipefail
fail() { echo "$*" >&2; exit 1; }
usage() { fail "usage: $0 <serial | host[:port]> [--apk file] [--start-receiving] [--port 1024-65535]"; }
[[ $# -gt 0 && $1 != -* && -n $1 ]] || usage
serial=$1
shift
apk=$(dirname "$0")/../out/herald.apk
native_port=7979
start=false
while (($#)); do
    case $1 in
        --apk) [[ $# -ge 2 ]] || usage; apk=$2; shift 2 ;;
        --start-receiving) start=true; shift ;;
        --port)
            [[ $# -ge 2 && $2 =~ ^[0-9]{1,5}$ ]] || usage
            native_port=$((10#$2))
            ((native_port >= 1024 && native_port <= 65535)) || usage
            shift 2 ;;
        *) usage ;;
    esac
done
[ -r "$apk" ] || fail "APK is unreadable: $apk"
# The SDK tools can read versionName directly. With only platform-tools, verify the
# installed base APK byte-for-byte before trusting its package-manager versionName.
apk_version=
if command -v aapt2 >/dev/null; then
    badging=$(aapt2 dump badging "$apk")
    apk_version=$(sed -n "s/^package: name='io\.github\.iwinties\.herald'.* versionName='\([^']*\)'.*/\1/p" <<<"$badging")
    [[ -n $apk_version ]] || fail "APK is missing Herald's package name or versionName"
fi
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
installed=$(device shell pm path "$pkg")
[[ $installed == package:* ]] || fail "package not installed"
version=$(device shell dumpsys package "$pkg" | sed -n 's/^[[:space:]]*versionName=//p' | tr -d '\r')
[[ -n $version ]] || fail "installed version unavailable"
if [[ -n $apk_version ]]; then
    [[ $version == "$apk_version" ]] || fail "installed version differs from the APK"
else
    path=${installed#package:}
    path=${path%%$'\n'*}
    path=${path%$'\r'}
    [[ $path =~ ^/[a-zA-Z0-9_./~+=-]+$ ]] || fail "invalid installed APK path"
    source_digest=$(sha256sum <"$apk")
    device_digest=$(device shell sha256sum "$path")
    [[ ${source_digest%% *} == "${device_digest%% *}" ]] || fail "installed APK differs from the source"
fi
echo "Installed Herald $version"
if [[ $start == false ]]; then exit 0; fi

device shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow
device shell dumpsys deviceidle whitelist "+$pkg"
device shell am start -W -n "$pkg/.SettingsActivity"
result=$(device shell am broadcast -a "$pkg.action.ENABLE_RECEIVING" -n "$pkg/.ShellReceiver")
[[ $result =~ result=-1([^0-9]|$) ]] || fail "start refused"
case "$serial" in
    *:[0-9]*) endpoint="https://$host:$native_port" ;;
    *) forward=$(device forward tcp:0 "tcp:$native_port")
       endpoint="https://127.0.0.1:$forward" ;;
esac
# Only the first info request trusts an unverified peer. Share one deadline across all attempts.
deadline=$((SECONDS + 20))
remaining() {
    local left=$((deadline - SECONDS))
    ((left > 0)) || return 1
    echo "$left"
}
probe() {
    local left
    left=$(remaining) || return 1
    curl -q --insecure -gfs --noproxy '*' --max-time "$left" "$@" "$endpoint/v1/info"
}
unavailable="Herald did not start; the saved choice is On (switch it off in Setup to stop retrying)"
until info=$(probe); do
    remaining >/dev/null || fail "$unavailable"
    sleep 0.25
done
# Read the certificate on a separate connection, keeping transport errors distinct from digest differences.
left=$(remaining) || fail "$unavailable"
peer=$(timeout "$left" openssl s_client -connect "${endpoint#https://}" </dev/null 2>/dev/null) ||
    fail "TLS connection failed"
cert=$(printf '%s\n' "$peer" | openssl x509 -outform DER | sha256sum)
pin=$(printf '%s\n' "$peer" | openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER |
    openssl dgst -sha256 -binary | base64)
printf '%s' "$info" | python3 -c '
import json, sys
info = json.load(sys.stdin)
if info.get("cert_sha256") != sys.argv[1] or info.get("pin_sha256") != sys.argv[2]:
    sys.exit("certificate mismatch")
' "${cert%% *}" "$pin"
until info=$(probe --pinnedpubkey "sha256//$pin"); do
    rc=$?
    [[ $rc != 90 ]] || fail "certificate mismatch"
    remaining >/dev/null || fail "$unavailable"
    sleep 0.25
done
printf '%s' "$info" | python3 -c '
import json, sys
info = json.load(sys.stdin)
if info.get("app") != "herald" or info.get("version") != sys.argv[1]:
    sys.exit("running Herald version differs from the installed package")
print("Herald", sys.argv[1], "is responding")
' "$version"
