#!/usr/bin/env bash
# shellcheck disable=SC2016 # Guest shell snippets must expand inside the container.
# start stays attached. In a second shell use "adb <device command>" or "stop".
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
image=herald-emu:36
name=herald-emu
label=io.github.iwinties.herald.emulator
docker=(sudo -n docker)
d() { "${docker[@]}" "$@"; }
usage() { echo "usage: $0 start | stop | adb <args> | checks <out/apk> <evidence.md> | ocr-pair --layout <sw> <sh> | ocr-pair <before.png> <code.png> [--mask N]" >&2; exit 2; }
owned() { d container inspect -f "{{index .Config.Labels \"$label\"}}" "$name"; }
serial=emulator-5554
cmd=${1:-}
# The pairing reader shared by ocr-pair and the live runner (plan.md "Unattended emulator runner").
# It never logs or stores a code: callers print it once to stdout or compare it in memory.
read -r -d '' reader_py <<'PYTHON' || true
import io
import subprocess
from PIL import Image, ImageChops

def px(size, thousandths):
    return (size * thousandths + 500) // 1000

def layout(sw, sh):
    width, height = px(sw, 340), px(sh, 352)
    left, top = (sw - width + 1) // 2, (sh - height + 1) // 2
    box_width, box_height = px(sh, 45), px(sh, 60)
    gap, group_gap = px(sh, 8), px(sh, 20)
    row_width = 6 * box_width + 4 * gap + group_gap
    x = left + (width - row_width + 1) // 2
    y = top + sum(px(sh, slot) for slot in (30, 40, 16, 45, 8, 30, 24))
    boxes = []
    for i in range(6):
        boxes.append((x, y, box_width, box_height))
        x += box_width + (group_gap if i == 2 else gap)
    return (left, top, width, height), boxes

def pairing_shown(before, frame, boxes):
    # Only the six digit boxes count: shadow and the rest of the screen are ignored.
    if before.size != frame.size:
        return False
    for x, y, w, h in boxes:
        area = (x, y, x + w, y + h)
        bands = [band.point(lambda v: 255 if v > 24 else 0)
                 for band in ImageChops.difference(before.crop(area), frame.crop(area)).split()]
        changed = ImageChops.lighter(ImageChops.lighter(bands[0], bands[1]), bands[2]).histogram()[255]
        if changed * 20 < w * h:
            return False
    return True

def read_digit(frame, box):
    x, y, w, h = box
    ix, iy = (w * 15 + 50) // 100, (h * 15 + 50) // 100
    gray = frame.crop((x + ix, y + iy, x + w - ix, y + h - iy)).convert("L")
    low, high = gray.getextrema()
    if high - low < 60:
        return None
    gray = gray.resize((gray.width * 4, gray.height * 4), Image.LANCZOS)
    cut = (low + high) // 2
    ink = gray.point(lambda v: 0 if v > cut else 255)
    page = Image.new("L", (ink.width + 40, ink.height + 40), 255)
    page.paste(ink, (20, 20))
    stream = io.BytesIO()
    page.save(stream, "PNG")
    # Single-character mode first; some glyphs need the single-word mode (psm 8).
    for mode in ("10", "8"):
        result = subprocess.run(["tesseract", "stdin", "stdout", "--psm", mode,
                                 "-c", "tessedit_char_whitelist=0123456789"],
                                input=stream.getvalue(), capture_output=True, timeout=30)
        text = result.stdout.decode().strip() if result.returncode == 0 else ""
        if len(text) == 1 and text in "0123456789":
            return text
    return None

def read_code(before, frame, mask=None):
    _, boxes = layout(*frame.size)
    if not pairing_shown(before, frame, boxes):
        return None
    if mask is not None:
        x, y, w, h = boxes[mask - 1]
        frame = frame.copy()
        frame.paste((0, 0, 0), (x, y, x + w, y + h))
    digits = [read_digit(frame, box) for box in boxes]
    return None if None in digits else "".join(digits)
PYTHON
case "$cmd" in
    ocr-pair)
        if [[ $# == 4 && $2 == --layout ]]; then
            [[ $3 =~ ^[1-9][0-9]*$ && $4 =~ ^[1-9][0-9]*$ ]] || usage
            d run --rm --pull=never -i --network none "$image" python3 -c "$reader_py
import sys
body, boxes = layout(int(sys.argv[1]), int(sys.argv[2]))
print('body', *body)
for number, box in enumerate(boxes, start=1):
    print('box', number, *box)" "$3" "$4"
            exit
        fi
        [[ $# == 3 || ($# == 5 && $4 == --mask && $5 =~ ^[1-6]$) ]] || usage
        before=$(realpath -e -- "$2") && frame=$(realpath -e -- "$3") || usage
        [[ -f $before && -f $frame ]] || usage
        # One offline extraction: no AVD, bridge or KVM; only the two frames are mounted.
        # --log-driver=none: the code goes to the caller's stdout only, never to a daemon log copy.
        d run --rm --pull=never --network none --log-driver=none -v "$before:/frames/before.png:ro" -v "$frame:/frames/code.png:ro" \
            "$image" python3 -c "$reader_py
import sys
from PIL import Image
mask = int(sys.argv[1]) if sys.argv[1] else None
code = read_code(Image.open('/frames/before.png').convert('RGB'), Image.open('/frames/code.png').convert('RGB'), mask)
if code is None:
    sys.exit('pairing panel not shown or a box is unreadable')
print(code)" "${5:-}"
        exit ;;
    stop)
        [[ $# == 1 && $(owned) == "$root" ]] || exit 1
        d stop "$name" >/dev/null
        exit ;;
    adb)
        shift
        [[ $# -gt 0 && $(owned) == "$root" ]] || exit 1
        case "$1" in
            shell|exec-out|install|uninstall|logcat|push|pull|get-state|forward|reverse|bugreport|devices) ;;
            *) echo "expected a local emulator device command" >&2; exit 2 ;;
        esac
        exec "${docker[@]}" exec -i "$name" adb -s "$serial" "$@" ;;
    checks)
        [[ $# == 3 ]] || usage
        apk=$(realpath -e -- "$2")
        [[ -f $apk && $apk == "$root/out/"*.apk ]] || usage
        evidence=$(realpath -m -- "$3")
        [[ $evidence == *.md ]] || usage ;;
    start) [[ $# == 1 ]] || usage ;;
    *) usage ;;
esac

quiet() {
    local loads available containers processes
    for ((attempt=0; attempt<=12; attempt++)); do
        uptime >&2
        free -g >&2
        loads=$(cut -d' ' -f1-3 /proc/loadavg)
        available=$(free -g | awk '/^Mem:/ {print $7}')
        containers=$(d ps --no-trunc --format '{{.Names}} {{.Image}} {{.Command}}')
        # tools/adb.sh runs a persistent ADB daemon from the build image. Verify
        # its process list before omitting that idle daemon from the workload scan.
        if [[ $containers == *herald-adb* ]] &&
           [[ $(d top herald-adb -eo pid,comm 2>/dev/null | awk 'NR>1 {print $2}') == adb ]]; then
            containers=$(sed '/^herald-adb herald-build:[^ ]* .*adb -P 5038 nodaemon server"$/d' <<<"$containers")
        fi
        processes=$(ps -eo comm,args)
        if awk -v ram="$available" '{exit !(NF==3 && $1<8 && $2<8 && $3<8 && ram>=8)}' <<<"$loads" &&
           ! grep -Eiq '(^|[ /:_-])(build|test|tests|pytest|bats|herald-emu)([ /:_-]|$)' <<<"$containers" &&
           ! grep -Eq '^(docker|docker-buildx).* (build|bake)( |$)|^(sh|bash|python3?|bats|pytest) .*(tools/build\.sh|tests/ha/run\.sh| -m pytest|/bats |/pytest )' <<<"$processes"; then
            return
        fi
        ((attempt < 12)) || return 1
        sleep 300
    done
}

umask 077
tmp=$(mktemp -d "${TMPDIR:-/tmp}/herald-emu.XXXXXX")
network=
cid=
cleanup() {
    local rc=$?
    trap - EXIT HUP INT TERM
    if [[ -n $cid ]] && d container inspect "$cid" >/dev/null 2>&1; then
        d rm -f "$cid" >/dev/null || rc=1
    fi
    if [[ -n $network ]]; then d network rm "$network" >/dev/null || rc=1; fi
    rm -rf -- "$tmp"
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ $cmd == checks ]]; then
    # Replace an older passing result even when setup fails before the first reading.
    printf '# Herald Android TV emulator check\n\nAPK SHA-256: %s\n\n## Readings\n\n' \
        "$(sha256sum "$apk" | cut -d' ' -f1)" > "$evidence"
fi
quiet
if ! d image inspect "$image" >/dev/null 2>&1; then
    d build --force-rm -t "$image" "$root/tools/emulator"
    quiet
fi
network=$(d network create --driver bridge --internal --label "$label=$root" "herald-emu-${tmp##*.}")
cid=$(d create --init --name "$name" --label "$label=$root" --device /dev/kvm \
    --cpus 4 --memory 6g --network "$network" -v "$root/out:/apk:ro" "$image" sleep infinity)
d start "$cid" >/dev/null
inside() { d exec "$cid" "$@"; }
inside emulator -accel-check | tee "$tmp/accel"
grep -Ei 'KVM.*(usable|can be used)' "$tmp/accel" >/dev/null
inside adb start-server
emu_revision=$(inside awk -F= '/^Pkg.Revision/{print $2}' /opt/android-sdk/emulator/source.properties)
tv_revision=$(inside awk -F= '/^Pkg.Revision/{print $2}' /opt/android-sdk/system-images/android-36/android-tv/x86_64/source.properties)
[[ -n $emu_revision && -n $tv_revision ]]
printf 'Installed emulator %s, TV image %s\n' "$emu_revision" "$tv_revision" >&2
booted=false
for renderer in swiftshader_indirect guest; do
    inside sh -c 'emulator -avd herald36 -port 5554 -accel on -no-window -no-audio -no-snapshot -wipe-data -memory 2048 -cores 2 -gpu "$1" >/tmp/emulator.log 2>&1 & echo $! >/tmp/emu.pid' sh "$renderer"
    if inside timeout 600 sh -c 'while kill -0 "$(cat /tmp/emu.pid)" 2>/dev/null; do
        if [ "$(timeout 5 adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" = 1 ]; then exit 0; fi
        sleep 2
    done; exit 1'; then
        booted=true
        break
    fi
    echo "$renderer failed to reach sys.boot_completed=1" >&2
    inside sh -c 'grep -E "ERROR|FATAL|PANIC" /tmp/emulator.log | tail -5' >&2
    # Restart just this container to clear the failed renderer and its child processes.
    d restart -t 1 "$cid" >/dev/null
    inside adb start-server
done
$booted || { echo 'Neither renderer booted within its deadline' >&2; exit 1; }
inside adb -s "$serial" forward tcp:7979 tcp:7979
printf 'Emulator %s, TV image %s, renderer %s\n' "$emu_revision" "$tv_revision" "$renderer" >&2
if [[ $cmd == start ]]; then
    "${docker[@]}" wait "$cid" >/dev/null &
    wait "$!"
    exit
fi
printf 'Emulator revision %s, android-36 android-tv x86_64 revision %s, renderer %s.\n\n' \
    "$emu_revision" "$tv_revision" "$renderer" >> "$evidence"
{ printf '%s\n' "$reader_py"; cat <<'PY'; } | d exec -i "$cid" python3 - "/apk/${apk#"$root/out/"}" >>"$evidence"
import base64
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import re
import shlex
import signal
import socket
import threading
import ssl
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops, ImageFilter

pkg = "io.github.iwinties.herald"
activity = pkg + "/.SettingsActivity"
adb = ["adb", "-s", "emulator-5554"]
os.umask(0o077)
private = tempfile.TemporaryDirectory()
auth = Path(private.name, "header")

class ReadingError(Exception):
    pass

def require(condition, message):
    if not condition:
        raise ReadingError(message)

def command(args, data=None, timeout=30):
    result = subprocess.run(args, input=data, capture_output=True, timeout=timeout)
    require(result.returncode == 0, f"{Path(args[0]).name} exited {result.returncode}")
    return result.stdout

def shell(*args):
    return command(adb + ["shell", shlex.join(args)]).decode().strip()

cert_sha256 = None

def certificate_digests(peer):
    der = command(["openssl", "x509", "-outform", "DER"], peer)
    pub = command(["openssl", "x509", "-pubkey", "-noout"], peer)
    spki = command(["openssl", "pkey", "-pubin", "-outform", "DER"], pub)
    fingerprint = hashlib.sha256(der).hexdigest()
    pin = base64.b64encode(hashlib.sha256(spki).digest()).decode()
    return fingerprint, pin

def bootstrap():
    global cert_sha256
    require(not auth.exists() and cert_sha256 is None, "bootstrap requires a fresh client")
    raw = command(["curl", "-q", "--noproxy", "*", "--insecure", "--silent", "--fail",
                   "--max-time", "8", "https://127.0.0.1:7979/v1/info"])
    info = json.loads(raw)
    peer = command(["openssl", "s_client", "-connect", "127.0.0.1:7979"], b"", timeout=8)
    fingerprint, pin = certificate_digests(peer)
    require(fingerprint == info.get("cert_sha256") and pin == info.get("pin_sha256"),
            "certificate mismatch")
    cert_sha256 = fingerprint
    return info

def request(path, method="GET", body=None):
    require(cert_sha256 is not None, "certificate mismatch")
    conn = http.client.HTTPSConnection("127.0.0.1", 7979, timeout=8,
                                       context=ssl._create_unverified_context())
    try:
        conn.connect()
        require(hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest() == cert_sha256,
                "certificate mismatch")
        headers = {}
        if auth.exists():
            headers["Authorization"] = auth.read_text().strip().split(": ", 1)[1]
        if body is not None:
            headers["Content-Type"] = "application/json"
        conn.request(method, "/v1/" + path, json.dumps(body) if body is not None else None, headers)
        reply = conn.getresponse()
        require(reply.status == 200, f"{path} HTTP {reply.status}")
        result = json.loads(reply.read())
        require(result.get("ok") is True, f"{path} response missing ok")
        return result
    finally:
        conn.close()

def key(name):
    shell("input", "keyevent", "KEYCODE_" + name)
    time.sleep(0.45)

def ui():
    # uiautomator writes XML separately from its informational stdout.
    shell("uiautomator", "dump", "/data/local/tmp/herald-ui.xml")
    return ET.fromstring(shell("cat", "/data/local/tmp/herald-ui.xml"))

def resumed():
    records = shell("dumpsys", "activity", "activities").splitlines()
    top = [r for r in records if re.search(r"\b(?:topResumedActivity|mResumedActivity)\s*[:=]", r)]
    require(top, "dumpsys returned no resumed activity record")
    return any(activity in row for row in top)

def screenshot():
    png = command(adb + ["exec-out", "screencap", "-p"])
    return png, Image.open(io.BytesIO(png)).convert("RGB")

def ocr(image, mode):
    stream = io.BytesIO()
    image.save(stream, "PNG")
    return command(["tesseract", "stdin", "stdout", "--psm", str(mode)],
                   stream.getvalue()).decode()

def install():
    require(shell("getprop", "ro.build.version.sdk") == "36", "guest SDK differs from 36")
    require("android.software.leanback" in shell("pm", "list", "features"), "missing Leanback")
    require(b"Success" in command(adb + ["install", "-g", sys.argv[1]], timeout=120), "install failed")
    shell("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
    require("allow" in shell("appops", "get", pkg, "SYSTEM_ALERT_WINDOW"), "overlay grant missing")
    key("WAKEUP")
    info = enable_receiving()
    return f"API 36 TV, APK installed, overlay allowed, headless enable, /v1/info herald {info['version']} API 2"

def enable_receiving():
    start_settings()
    result = shell("am", "broadcast", "-a", pkg + ".action.ENABLE_RECEIVING", "-n", pkg + "/.ShellReceiver")
    require("result=-1" in result, "start refused")

    return wait_info()

def wait_info():
    def expired(signum, frame):
        raise ReadingError("Herald did not start; the saved choice is On (switch it off in Setup to stop retrying)")

    previous = signal.signal(signal.SIGALRM, expired)
    signal.alarm(20)
    try:
        while True:
            try:
                if cert_sha256 is None:
                    bootstrap()
                info = request("info")
                break
            except ReadingError as error:
                if not str(error).startswith(("curl exited", "openssl exited")):
                    raise
            except (OSError, http.client.HTTPException, subprocess.TimeoutExpired):
                pass
            time.sleep(0.25)
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, previous)
    require(info.get("app") == "herald" and info.get("api") == 2, "unexpected info identity")
    require(info.get("cert_sha256") == cert_sha256, "reported certificate changed")
    return info

def pair():
    # Open a static screen, keep a before frame, then read two fresh code frames >= 1 s apart.
    start_settings()
    time.sleep(1)
    _, before = screenshot()
    request("pair/start", "POST", {"client": "Emulator"})
    codes = []
    for reading in range(2):
        time.sleep(1.2)
        code = None
        for attempt in range(10):
            _, frame = screenshot()
            code = read_code(before, frame)
            if code is not None:
                break
            time.sleep(0.5)
        require(code is not None, "pairing panel not shown or a digit box unreadable after 10 frames")
        codes.append(code)
    require(codes[0] == codes[1], "two fresh pairing reads disagree")
    reply = request("pair/finish", "POST", {"client": "Emulator", "code": codes[0]})
    require(isinstance(reply.get("token"), str) and reply["token"], "finish missing token")
    auth.write_text("Authorization: Bearer " + reply["token"] + "\n")
    time.sleep(5.2)  # paired confirmation overlay
    require(request("status").get("api") == 2, "pinned status missing API 2")
    return "two matching OCR reads, paired client authorized at pinned /v1/status"

def bounds(node):
    values = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    require(values is not None, "missing UI node bounds")
    return tuple(map(int, values.groups()))

def card_back(expand):
    ident = "expand-test" if expand else "back-test"
    title = "Expand notice" if expand else "Close notice"
    status = request("status")
    request("cards/clear", "POST", {"all": True})
    request("cards", "POST", {"id": ident, "title": title, "style": {"duration": 0, "focus": "auto"},
            "buttons": [{"id":"action", "label":"Select", "action":"expand" if expand else "event"}]})
    time.sleep(1)
    def card_window():
        tree = ui()
        roots = [n for n in tree.findall("node") if n.get("package") == pkg and
                 any(child.get("text") == title for child in n.iter("node"))]
        require(len(roots) == 1, "buttoned card absent from active window")
        return bounds(roots[0])
    original = card_window()
    extra = ""
    if expand:
        key("DPAD_CENTER")
        enlarged = card_window()
        require(enlarged[2]-enlarged[0] > original[2]-original[0], "card width did not expand")
        extra = f"bounds {original} to {enlarged}, "
    key("BACK")
    time.sleep(1)
    events = request(f"events?after={status['event_seq']}&wait=0")
    require(not events["gap"] and not events["reset"] and events["stream"] == status["stream"],
            "event stream changed or lost records")
    relevant = [e for e in events["events"] if e.get("id") == ident]
    ended = [e["reason"] for e in relevant if e["type"] == "card.ended"]
    require(any(e["type"] == "card.shown" for e in relevant) and ended == ["dismissed"],
            f"after one BACK, card.ended reasons {ended}")
    return extra + "one BACK, one card.ended dismissed"

def start_settings():
    shell("am", "start", "-W", "-n", activity)
    require(resumed(), "SettingsActivity did not resume")

def focused():
    matches = [n.get("text") for n in ui().iter("node") if n.get("package") == pkg and
               n.get("focused") == "true"]
    require(len(matches) == 1, "missing unique focused Herald control")
    return matches[0]

def move_down_to(label):
    for step in range(13):
        if focused() == label:
            return
        if step < 12:
            key("DPAD_DOWN")
    raise ReadingError(f"did not reach {label} within 12 presses")

def navigation():
    start_settings()
    require(focused() == "Setup", "Setup section did not receive focus")
    move_down_to("Categories")
    key("DPAD_RIGHT")
    first = focused()
    require(re.search(r" \([a-z0-9_]+\)$", first), "category list focus missing")
    key("DPAD_CENTER")
    require(focused().startswith("Look "), "category editor rows missing")
    key("BACK")
    require(resumed() and focused() == first, "first BACK did not restore category list")
    key("BACK")
    require(resumed() and focused() == "Categories", "second BACK did not restore sections")
    key("BACK")
    require(not resumed(), "third BACK left SettingsActivity resumed")
    return f"rows → list ({first}) → sections (Categories) → closed, three BACK presses"

def insets():
    shell("logcat", "-c")
    start_settings()
    tree = ui()
    text = shell("logcat", "-d", "-s", "Herald:I", "*:S")
    rows = re.findall(r"settings window ((?:-?\d+ ){3}-?\d+) insets (\d+,\d+,\d+,\d+)", text)
    require(rows, "no current SettingsActivity metrics/insets")
    x,y,r,b = map(int, rows[-1][0].split())
    left,top,right,bottom = map(int, rows[-1][1].split(","))
    limit = (x+left,y+top,r-right,b-bottom)
    columns = []
    for name in ("herald_rail", "herald_content"):
        matches = [bounds(n) for n in tree.iter("node") if n.get("resource-id") == pkg + ":id/" + name]
        require(len(matches) == 1, f"expected one {name}, saw {matches}")
        columns.append(matches[0])
    rail, content = columns
    require((rail[0], rail[1], content[2], rail[3]) == limit
            and rail[2] == content[0] and content[1::2] == rail[1::2],
            f"rail/content {columns} do not fill inset bounds {limit}")
    require(abs(rail[2] - rail[0] - .24 * (limit[2] - limit[0])) <= 1,
            f"rail width differs from 24 percent: {rail}")
    suffix = "" if any((left,top,right,bottom)) else ", non-zero insets not device-verified"
    return f"window {rows[-1][0]}, insets {rows[-1][1]}, content {columns}{suffix}"

def render():
    key("HOME")
    request("cards/clear", "POST", {"all": True})
    # Classic is the opaque look; translucent looks over the launcher defeat whole-frame OCR.
    request("cards", "POST", {"id":"render-test", "title":"Copper Lantern", "look":"classic", "style":{"duration":0}})
    request("chips/render-test", "PUT", {"text":"Silver Bell", "look":"classic"})
    for attempt in range(8):
        time.sleep(1)
        png, frame = screenshot()
        words = " ".join(ocr(frame, 11).casefold().split())
        if "copper lantern" in words and "silver bell" in words:
            break
    require("copper lantern" in words and "silver bell" in words, "card and chip text not both visible in screencap")
    return f"same {frame.width}×{frame.height} screencap contains Copper Lantern and Silver Bell, SHA-256 {hashlib.sha256(png).hexdigest()}"

def tls():
    info = request("info")
    for flag in ("-tls1_2", "-tls1_3"):  # Both supported by the API 36 image.
        peer = command(["openssl", "s_client", flag, "-connect", "127.0.0.1:7979"], b"", timeout=8)
        require(certificate_digests(peer) == (cert_sha256, info["pin_sha256"]),
                "forced handshake digest mismatch")
    with socket.create_connection(("127.0.0.1", 7979), timeout=8) as plain:
        plain.sendall(b"GET /v1/info HTTP/1.0\r\n\r\n")
        try:
            first = plain.recv(1)
        except ConnectionResetError:
            first = b""
        require(first in (b"", b"\x15"), "plain request received an unexpected reply")
    shell("am", "force-stop", pkg)
    enable_receiving()  # Keeps the existing DER pin.
    return "TLS 1.2 and 1.3, DER and SPKI match, no plain HTTP reply, force-stop and enable retained fingerprint"

def listener_closed():
    start = time.monotonic()
    for index in range(16):
        time.sleep(max(0, start + index * 2 - time.monotonic()))
        for table in ("tcp", "tcp6"):
            rows = shell("cat", "/proc/net/" + table).splitlines()
            require(rows and rows[0].split()[:4] in (["sl", "local_address", "rem_address", "st"],
                                                      ["sl", "local_address", "remote_address", "st"]),
                    f"missing {table} header at sample {index + 1}")
            for row in rows[1:]:
                fields = row.split()
                require(len(fields) >= 4, f"malformed {table} row")
                require(not (fields[1].upper().endswith(":1F2B") and fields[3] == "0A"),
                        f"{table} shows LISTEN on 7979 at sample {index + 1}")

def fresh_state():
    global cert_sha256
    original = cert_sha256
    require(shell("pm", "clear", pkg) == "Success", "pm clear failed")
    auth.unlink()
    cert_sha256 = None
    shell("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
    start_settings()
    require(focused() == "Setup", "fresh install did not open Setup")
    listener_closed()
    enable_receiving()
    require(cert_sha256 != original, "pm clear retained the old identity")
    pair()
    return "pm clear, client credentials dropped, Setup, tcp/tcp6 closed for 30 s, enabled, new identity pinned and paired"

def reboot():
    boot_id = shell("cat", "/proc/sys/kernel/random/boot_id")
    require(bool(boot_id), "missing original boot ID")
    command(adb + ["reboot"])
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        try:
            if (shell("getprop", "sys.boot_completed") == "1"
                    and shell("cat", "/proc/sys/kernel/random/boot_id") not in ("", boot_id)):
                break
        except (ReadingError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    else:
        raise ReadingError("new boot did not complete")
    command(adb + ["forward", "tcp:7979", "tcp:7979"])
    mappings = command(adb + ["forward", "--list"]).decode().splitlines()
    require(any(line.split() == [adb[2], "tcp:7979", "tcp:7979"] for line in mappings),
            "7979 forward missing after reboot")

def stop_receiving():
    start_settings()
    require(focused() == "Setup", "Stop requires Setup")
    key("DPAD_RIGHT")
    move_down_to("Stop receiving")
    key("DPAD_CENTER")
    require(any(n.get("text", "").split() == ["Receiving", "Off"] for n in ui().iter("node")),
            "Setup did not show Receiving Off")

def boot_checks():
    reboot()
    wait_info()
    stop_receiving()
    reboot()
    listener_closed()
    enable_receiving()
    return "On reboot retained pin, both new boots completed with verified forwards, Off tcp/tcp6 closed for 30 s, enabled again"

def ntfy():
    delivered = threading.Event()
    stop = threading.Event()
    class Stream(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            if self.path.split("?", 1)[0] != "/emulator/json":
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.end_headers()
            if not delivered.is_set():
                event = {"event": "message", "id": "test1", "topic": "emulator",
                         "time": int(time.time()), "message": "Parcel received"}
                self.wfile.write(json.dumps(event).encode() + b"\n")
                delivered.set()
            while not stop.wait(1):
                self.wfile.write(b'{"event":"keepalive"}\n')

    key("HOME")
    address = socket.gethostbyname(socket.gethostname())
    with ThreadingHTTPServer((address, 0), Stream) as server:
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            request("settings", "PUT", {"ntfy": {"enabled": True, "server": f"http://{address}:{server.server_port}",
                    "topics": [{"topic": "emulator", "label": "Amber Parcel", "look": "classic"}]}})
            require(delivered.wait(20), "no ntfy subscription reached the fake server")
            for _ in range(8):
                _, frame = screenshot()
                if "amber parcel" in " ".join(ocr(frame, 11).lower().split()):
                    return "one untitled ntfy message, mapped topic label Amber Parcel visible in screencap"
                time.sleep(0.5)
            raise ReadingError("mapped ntfy title absent from screencap")
        finally:
            stop.set()
            server.shutdown()
            thread.join()
            request("settings", "PUT", {"ntfy": {"enabled": False}})

def update():
    def replace():
        require(b"Success" in command(adb + ["install", "-r", "/apk/herald-universal.apk"], timeout=120),
                "universal APK update failed")
    replace()
    wait_info()  # No launch or enable after replacement. The post-clear pin remains active.
    stop_receiving()
    replace()
    listener_closed()
    return "universal APK installed twice, On answered within 20 s with post-clear pin, Off tcp/tcp6 closed for 30 s"

failures = []
def check(k, fn):
    try:
        reading = fn()
    except Exception as exc:
        # Only our controlled readings may reach evidence. Library exceptions can contain inputs.
        message = str(exc) if isinstance(exc, ReadingError) else type(exc).__name__
        print(f"- {k} — {message} (exit 1) — fail", flush=True)
        failures.append(k)
        return False
    print(f"- {k} — {reading} (exit 0) — pass", flush=True)
    return True

def when(ready, k, fn):
    if ready:
        return check(k, fn)
    print(f"- {k} — prerequisite failed (no exit code) — n/a", flush=True)
    failures.append(k)
    return False

installed = check("E01", install)
secure = when(installed, "E07", tls)
paired = when(secure, "E08", pair)
when(paired, "E02", lambda: card_back(False))
when(paired, "E03", lambda: card_back(True))
when(installed, "E04", navigation)
when(installed, "E05", insets)
when(paired, "E06", render)
fresh = when(paired, "E09", fresh_state)
enabled = when(fresh, "E10", boot_checks)
when(enabled, "E11", ntfy)
when(enabled, "E12", update)
private.cleanup()
sys.exit(int(bool(failures)))
PY
