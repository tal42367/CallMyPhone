import math
import random
import struct
import wave
from pathlib import Path

SR = 22050
BPM = 89.1029
BEAT = 60.0 / BPM
BARS = 16
DURATION = BARS * 4 * BEAT
N = int(SR * DURATION)

OUT = Path("app/src/main/assets/yishai_music_loop.wav")
OUT.parent.mkdir(parents=True, exist_ok=True)

CHORDS = [
    (220.00, 261.63, 329.63),  # Am
    (174.61, 220.00, 261.63),  # F
    (130.81, 164.81, 196.00),  # C
    (196.00, 246.94, 293.66),  # G
]

random.seed(7)

def env_pluck(t, length):
    if t < 0 or t > length:
        return 0.0
    attack = min(1.0, t / 0.008)
    return attack * math.exp(-t / 0.55)

def env_pad(t, length):
    if t < 0 or t > length:
        return 0.0
    x = t / length
    return math.sin(math.pi * x) ** 1.35

def kick(t):
    if t < 0 or t > 0.30:
        return 0.0
    f = 72.0 - 38.0 * (t / 0.30)
    return math.sin(2 * math.pi * f * t) * math.exp(-15 * t)

def shaker(t, seed):
    if t < 0 or t > 0.07:
        return 0.0
    # Deterministic pseudo-noise for each hit.
    x = math.sin((seed * 12.9898 + t * 9157.3)) * 43758.5453
    noise = (x - math.floor(x)) * 2 - 1
    return noise * math.exp(-55 * t)

samples = [0.0] * N

for bar in range(BARS):
    bar_start = bar * 4 * BEAT
    chord = CHORDS[(bar // 2) % len(CHORDS)]

    # Warm pad.
    for f in chord:
        length = 4 * BEAT
        i0 = int(bar_start * SR)
        i1 = min(N, int((bar_start + length) * SR))
        for i in range(i0, i1):
            tt = i / SR - bar_start
            e = env_pad(tt, length)
            samples[i] += e * 0.018 * (
                math.sin(2 * math.pi * (f / 2) * tt)
                + 0.30 * math.sin(2 * math.pi * f * tt)
            )

    # Four gentle plucks per bar.
    order = (0, 2, 1, 2)
    for k, beat_pos in enumerate((0.0, 1.0, 2.0, 3.0)):
        start = bar_start + beat_pos * BEAT
        f = chord[order[k]]
        length = 1.15 * BEAT
        i0 = int(start * SR)
        i1 = min(N, int((start + length) * SR))
        for i in range(i0, i1):
            tt = i / SR - start
            e = env_pluck(tt, length)
            samples[i] += e * 0.085 * (
                math.sin(2 * math.pi * f * tt)
                + 0.20 * math.sin(2 * math.pi * 2 * f * tt)
                + 0.06 * math.sin(2 * math.pi * 3 * f * tt)
            )

    # Soft kick on 1 and 3.
    for beat_pos in (0.0, 2.0):
        start = bar_start + beat_pos * BEAT
        i0 = int(start * SR)
        i1 = min(N, i0 + int(0.30 * SR))
        for i in range(i0, i1):
            tt = i / SR - start
            samples[i] += 0.045 * kick(tt)

    # Very light shaker on offbeats.
    for hit, beat_pos in enumerate((0.5, 1.5, 2.5, 3.5)):
        start = bar_start + beat_pos * BEAT
        i0 = int(start * SR)
        i1 = min(N, i0 + int(0.07 * SR))
        seed = bar * 8 + hit
        for i in range(i0, i1):
            tt = i / SR - start
            samples[i] += 0.008 * shaker(tt, seed)

# Small melodic motif every four bars.
motif = (440.0, 523.25, 659.25, 523.25, 392.0, 493.88, 587.33, 493.88)
for block in range(0, BARS, 4):
    base = block * 4 * BEAT
    for j, f in enumerate(motif):
        start = base + j * 0.5 * BEAT
        length = 0.60 * BEAT
        i0 = int(start * SR)
        i1 = min(N, int((start + length) * SR))
        for i in range(i0, i1):
            tt = i / SR - start
            samples[i] += env_pluck(tt, length) * 0.026 * math.sin(2 * math.pi * f * tt)

# Gentle saturation and seamless edge fade.
fade = int(0.12 * SR)
for i in range(N):
    x = math.tanh(samples[i] * 1.8) * 0.66
    if i < fade:
        x *= i / fade
    if i >= N - fade:
        x *= (N - i - 1) / fade
    samples[i] = max(-0.95, min(0.95, x))

with wave.open(str(OUT), "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    chunk = bytearray()
    for x in samples:
        chunk += struct.pack("<h", int(x * 32767))
        if len(chunk) >= 131072:
            w.writeframesraw(chunk)
            chunk.clear()
    if chunk:
        w.writeframesraw(chunk)

print(f"Generated {OUT} ({DURATION:.1f}s, {OUT.stat().st_size} bytes)")
