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
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
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

def request(path, method="GET", body=None):
    args = ["curl", "--noproxy", "*", "--silent", "--show-error", "--max-time", "8",
            "--fail", "-X", method, "http://127.0.0.1:7979/v1/" + path]
    if auth.exists():
        args += ["-H", "@" + str(auth)]
    if body is not None:
        args += ["--json", "@-"]
    result = json.loads(command(args, json.dumps(body).encode() if body is not None else None))
    require(result.get("ok") is True, f"{path} response missing ok")
    return result

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
    shell("am", "start-foreground-service", "-n", pkg + "/.HeraldService")
    for attempt in range(15):
        try:
            info = request("info")
            break
        except ReadingError:
            if attempt == 14:
                raise
            time.sleep(1)
    require(info.get("app") == "herald" and info.get("api") == 1, "unexpected info identity")
    return f"API 36 TV, APK installed, overlay allowed, /v1/info herald {info['version']} API 1"

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
    return request("status")

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

def navigation():
    start_settings()
    require(focused() == "Looks", "Looks section did not receive focus")
    key("DPAD_DOWN")
    require(focused() == "Categories", "Categories section did not receive focus")
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

installed = check("E01", install)
paired = False
if installed:
    try:
        pair()
        paired = True
    except Exception as exc:
        print("Pairing prerequisite failed: " + (str(exc) if isinstance(exc, ReadingError) else type(exc).__name__), flush=True)
for k, fn, ready in [("E02", lambda: card_back(False), paired),
                     ("E03", lambda: card_back(True), paired),
                     ("E04", navigation, installed), ("E05", insets, installed), ("E06", render, paired)]:
    if ready:
        check(k, fn)
    else:
        print(f"- {k} — prerequisite failed (no exit code) — n/a", flush=True)
        failures.append(k)
private.cleanup()
sys.exit(int(bool(failures)))
PY
