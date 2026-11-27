#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
cd "$repo"
python3 tools/make_chime.py --check
for name in config_flow actions events speakers; do
    echo "MODULE $name"
    sudo docker run --rm --network none --entrypoint python -v "$repo:/fixture:ro" ghcr.io/home-assistant/home-assistant:2026.10.1 "/fixture/tests/ha/$name.py"
done
