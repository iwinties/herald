#!/bin/sh
set -eu
repo=$(cd "$(dirname "$0")/.." && pwd)
case ${1:-} in
  start)
    if ss -ltn | grep -q '127\.0\.0\.1:5038 '; then
      echo "port 5038 in use" >&2
      exit 1
    fi
    mkdir -p -m 700 "$HOME/.config/herald-adb"
    sudo docker run -d --rm --name herald-adb --network host --user "$(id -u):$(id -g)" \
      -e HOME=/adbhome -e ADB_MDNS_AUTO_CONNECT=0 -v "$HOME/.config/herald-adb:/adbhome/.android" \
      -v "$repo/out:/apk:ro" herald-build:36-m3-1.11.1-bt1.18.3 adb -P 5038 nodaemon server
    for second in $(seq 1 20); do
      if sudo docker exec herald-adb adb -P 5038 devices >/dev/null 2>&1; then
        exit 0
      fi
      sleep 1
    done
    echo "adb server did not start" >&2
    exit 1
    ;;
  stop) exec sudo docker rm -f herald-adb ;;
  *) exec sudo docker exec -i herald-adb adb -P 5038 "$@" ;;
esac
