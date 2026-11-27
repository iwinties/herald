"""Render Herald's bundled chime from the same samples as Chime.pcm."""

import argparse
import io
import math
import struct
import wave
from pathlib import Path

TARGET = Path(__file__).resolve().parents[1] / "custom_components/herald/chime.wav"


def note(raw, offset, count, frequency, rate):
    for k in range(count):
        t = k / rate
        raw[offset + k] = (
            min(1, t / 0.012)
            * math.exp(-5.5 * t)
            * (
                math.sin(2 * math.pi * frequency * t)
                + 0.18 * math.sin(4 * math.pi * frequency * t)
            )
        )


def pcm(rate):
    first = int(rate * 0.32)
    second = int(rate * 0.75)
    raw = [0.0] * (first + second)
    note(raw, 0, first, 659.25, rate)
    note(raw, first, second, 880.0, rate)
    peak = max(abs(v) for v in raw)
    scale = 0.25 * 32767 / peak if peak else 0
    return struct.pack(f"<{len(raw)}h", *(math.floor(v * scale + 0.5) for v in raw))


def render():
    stream = io.BytesIO()
    with wave.open(stream, "wb") as wav:
        wav.setparams((1, 2, 22050, 0, "NONE", "not compressed"))
        wav.writeframes(pcm(22050))
    return stream.getvalue()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    if parser.parse_args().check:
        raise SystemExit(TARGET.read_bytes() != render())
    TARGET.write_bytes(render())
