#!/usr/bin/env python3
"""Limits the sea depth in app/src/main/assets/maps/bijnor.bin to 9 m (v1.0.32). Safe to run again.
Problem: inside the 801 x 801 grid the sea got up to 76 m deep (the map maker kept going 3 cm per metre after 45 m), but outside the grid the game
uses OPEN_SEA_DEPTH = 7.7 m. So a swimmer crossing the grid edge saw the depth jump from 25-76 m to 7.7 m, and a diver could go 76 m down.
The sea floor is never drawn (quads below -0.6 m are culled), so nothing else changes. Land (>= 0.5 m) is untouched.
Run:  python3 tools/fix_bijnor_sea_depth.py   then   python3 tools/sync_manifest.py"""
import struct, pathlib
import numpy as np
BIN = pathlib.Path(__file__).resolve().parent.parent / 'app/src/main/assets/maps/bijnor.bin'
b = bytearray(BIN.read_bytes()); n, = struct.unpack_from('<i', b, 4)
off = len(b) - n * n * 2
gh = np.frombuffer(bytes(b[off:]), '<i2').copy()
print('sea cells deeper than 9 m:', int((gh < -900).sum()), ' deepest', gh.min() / 100.0)
gh = np.maximum(gh, -900).astype('<i2')
b[off:] = gh.tobytes(); BIN.write_bytes(bytes(b)); print('deepest now', gh.min() / 100.0)
