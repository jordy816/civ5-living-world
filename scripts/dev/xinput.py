#!/usr/bin/env python3
"""Real keyboard and mouse events on an X display, through the XTEST extension, with nothing
but the standard library. For driving the Minecraft client on a virtual screen the way a hand
would: the game cannot tell these events from a person's.

usage: xinput.py [-d :97] <command> [<command> ...]
  focus            give the keyboard to the game's window (there is no window manager to do it)
  move X Y         put the pointer there
  rel DX DY        move the pointer by that much (turns the view while the game holds the mouse)
  click [1|2|3]    press and release a mouse button (1 left, 3 right); default 1
  down N / up N    press or release a mouse button
  key NAME         press and release a key (X keysym name: e, Escape, space, 1, Shift_L ...)
  keydown NAME / keyup NAME
  wait SECONDS
"""
import ctypes
import sys
import time

X = ctypes.CDLL('libX11.so.6')
T = ctypes.CDLL('libXtst.so.6')
P = ctypes.c_void_p
W = ctypes.c_ulong
X.XOpenDisplay.restype = P
X.XOpenDisplay.argtypes = [ctypes.c_char_p]
X.XDefaultRootWindow.restype = W
X.XDefaultRootWindow.argtypes = [P]
X.XQueryTree.argtypes = [P, W, ctypes.POINTER(W), ctypes.POINTER(W), ctypes.POINTER(ctypes.POINTER(W)), ctypes.POINTER(ctypes.c_uint)]
X.XGetGeometry.argtypes = [P, W, ctypes.POINTER(W)] + [ctypes.POINTER(ctypes.c_int)] * 2 + [ctypes.POINTER(ctypes.c_uint)] * 4
X.XSetInputFocus.argtypes = [P, W, ctypes.c_int, ctypes.c_ulong]
X.XRaiseWindow.argtypes = [P, W]
X.XStringToKeysym.restype = ctypes.c_ulong
X.XStringToKeysym.argtypes = [ctypes.c_char_p]
X.XKeysymToKeycode.restype = ctypes.c_ubyte
X.XKeysymToKeycode.argtypes = [P, ctypes.c_ulong]
X.XFlush.argtypes = [P]
X.XSync.argtypes = [P, ctypes.c_int]
T.XTestFakeMotionEvent.argtypes = [P, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_ulong]
T.XTestFakeRelativeMotionEvent.argtypes = [P, ctypes.c_int, ctypes.c_int, ctypes.c_ulong]
T.XTestFakeButtonEvent.argtypes = [P, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
T.XTestFakeKeyEvent.argtypes = [P, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]


def game_window(display):
    """The largest top-level window: on a screen that holds nothing but the game, that is the game."""
    root = X.XDefaultRootWindow(display)
    root_back, parent = W(), W()
    children = ctypes.POINTER(W)()
    count = ctypes.c_uint()
    X.XQueryTree(display, root, ctypes.byref(root_back), ctypes.byref(parent), ctypes.byref(children), ctypes.byref(count))
    best, best_area = None, 0
    for i in range(count.value):
        x, y = ctypes.c_int(), ctypes.c_int()
        w, h, border, depth = ctypes.c_uint(), ctypes.c_uint(), ctypes.c_uint(), ctypes.c_uint()
        if X.XGetGeometry(display, children[i], ctypes.byref(root_back), ctypes.byref(x), ctypes.byref(y), ctypes.byref(w), ctypes.byref(h), ctypes.byref(border), ctypes.byref(depth)):
            if w.value * h.value > best_area:
                best, best_area = children[i], w.value * h.value
    return best


def keycode(display, name):
    sym = X.XStringToKeysym(name.encode())
    if not sym:
        raise SystemExit(f'no such key: {name}')
    return X.XKeysymToKeycode(display, sym)


def main(argv):
    name = b':97'
    if argv and argv[0] == '-d':
        name = argv[1].encode()
        argv = argv[2:]
    display = X.XOpenDisplay(name)
    if not display:
        raise SystemExit(f'cannot open display {name.decode()}')
    i = 0
    while i < len(argv):
        command = argv[i]
        i += 1
        if command == 'focus':
            window = game_window(display)
            if not window:
                raise SystemExit('no window on this display')
            X.XRaiseWindow(display, window)
            X.XSetInputFocus(display, window, 2, 0)
            print(f'focus on window {window:#x}')
        elif command == 'move':
            T.XTestFakeMotionEvent(display, 0, int(argv[i]), int(argv[i + 1]), 0)
            i += 2
        elif command == 'rel':
            T.XTestFakeRelativeMotionEvent(display, int(argv[i]), int(argv[i + 1]), 0)
            i += 2
        elif command in ('click', 'down', 'up'):
            button = 1
            if i < len(argv) and argv[i].isdigit():
                button = int(argv[i])
                i += 1
            if command != 'up':
                T.XTestFakeButtonEvent(display, button, 1, 0)
            if command == 'click':
                X.XSync(display, 0)
                time.sleep(0.25)
            if command != 'down':
                T.XTestFakeButtonEvent(display, button, 0, 0)
        elif command in ('key', 'keydown', 'keyup'):
            code = keycode(display, argv[i])
            i += 1
            if command != 'keyup':
                T.XTestFakeKeyEvent(display, code, 1, 0)
            if command == 'key':
                X.XSync(display, 0)
                time.sleep(0.25)
            if command != 'keydown':
                T.XTestFakeKeyEvent(display, code, 0, 0)
        elif command == 'wait':
            X.XSync(display, 0)
            time.sleep(float(argv[i]))
            i += 1
        else:
            raise SystemExit(f'unknown command: {command}')
        X.XSync(display, 0)
    X.XFlush(display)


if __name__ == '__main__':
    main(sys.argv[1:])
