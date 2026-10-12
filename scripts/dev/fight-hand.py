#!/usr/bin/env python3
"""Development aid: a hand on the mouse, fast enough to fight. For the client on the virtual screen
(prod-client.sh), with the player on foot before an enemy unit (debug driver: `tofoe`) and a sword
in hand: reads the screen's own file, turns towards the red name of a soldier with relative mouse
motion and strikes when the sword has swung back. Every event is a real mouse event (XTEST).
It says how many soldiers were beaten and whether the player fell.

usage: fight-hand.py [seconds]      (needs python with nothing but the standard library)"""
import ctypes, mmap, re, struct, sys, time
import os
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FB = os.environ.get('LW_FB', ROOT + '/fabric/run/fb') + '/Xvfb_screen0'
LOG = os.environ.get('LW_RUN', ROOT + '/fabric/run-prod') + '/logs/latest.log'
X = ctypes.CDLL('libX11.so.6'); T = ctypes.CDLL('libXtst.so.6'); P = ctypes.c_void_p
X.XOpenDisplay.restype = P; X.XOpenDisplay.argtypes = [ctypes.c_char_p]; X.XFlush.argtypes = [P]
T.XTestFakeRelativeMotionEvent.argtypes = [P, ctypes.c_int, ctypes.c_int, ctypes.c_ulong]
T.XTestFakeButtonEvent.argtypes = [P, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
d = X.XOpenDisplay(b':97')
f = open(FB, 'rb'); mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
h = struct.unpack('>25I', mm[:100]); hs, width, height, bpl, ncol = h[0], h[4], h[5], h[12], h[19]
base = hs + ncol * 12

def red():
    xs = ys = n = 0
    for y in range(36, 330, 3):
        row = mm[base + y * bpl + 60 * 4: base + y * bpl + 794 * 4]
        for i in range(0, len(row), 12):
            b, g, r = row[i], row[i + 1], row[i + 2]
            if r > 200 and g < 110 and b < 110:
                n += 1; xs += 60 + i // 4; ys += y
    return (n, xs / n, ys / n) if n else (0, 0, 0)

def log_count(pattern, since):
    with open(LOG, errors='ignore') as fh:
        fh.seek(since); return len(re.findall(pattern, fh.read()))

since = len(open(LOG, 'rb').read())
last_click = 0.0; lost = 0; clicks = 0; start = time.time(); shown = 0
while time.time() - start < float(sys.argv[1] if len(sys.argv) > 1 else 90):
    n, cx, cy = red()
    if n >= 2:
        lost = 0
        dx = cx - 427; dy = (cy + 40) - 240
        if abs(dx) > 6 or abs(dy) > 10:
            T.XTestFakeRelativeMotionEvent(d, int(max(-70, min(70, dx * 0.45))), int(max(-40, min(40, dy * 0.35))), 0)
    else:
        lost += 1
        if lost > 6:
            T.XTestFakeRelativeMotionEvent(d, 60, 0, 0)      # look around for them
    now = time.time()
    if now - last_click > 0.62:                             # the sword swung back
        T.XTestFakeButtonEvent(d, 1, 1, 0); X.XFlush(d); time.sleep(0.03); T.XTestFakeButtonEvent(d, 1, 0, 0)
        last_click = now; clicks += 1
    X.XFlush(d)
    if now - shown > 4:
        shown = now
        beaten = log_count(r'Your fight: ', since); dead = log_count(r'Player was slain|Player died|Player drowned', since)
        print(f'{now - start:5.1f}s red {n} beaten {beaten} dead {dead} clicks {clicks}', flush=True)
        if beaten >= 3 or dead:
            break
    time.sleep(0.06)
print('RESULT beaten', log_count(r'Your fight: ', since), 'dead', log_count(r'Player was slain|Player died|Player drowned', since), 'clicks', clicks, 'seconds', round(time.time() - start, 1))
