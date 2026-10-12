#!/usr/bin/env python3
"""Turns an X window dump (xwd -root) into a PNG, with nothing but the standard library.

xwd2png.py dump out.png [x y width height [times]]   optionally only a part of it, enlarged
"""
import struct
import sys
import zlib


def main(src, dst, crop=None, times=1):
    data = open(src, 'rb').read()
    header = struct.unpack('>25I', data[:100])
    header_size, _, _, _, width, height, _, byte_order, _, _, _, bits_per_pixel, bytes_per_line = header[:13]
    ncolors = header[19]
    pixels = data[header_size + ncolors * 12:]
    if bits_per_pixel != 32:
        raise SystemExit(f'only 32 bits per pixel dumps are understood, this one has {bits_per_pixel}')
    left, top, part_width, part_height = crop or (0, 0, width, height)
    part_width = min(part_width, width - left)
    part_height = min(part_height, height - top)
    rows = []
    for y in range(top, top + part_height):
        line = pixels[y * bytes_per_line + left * 4:y * bytes_per_line + (left + part_width) * 4]
        row = bytearray(part_width * 3)
        if byte_order == 0:  # least significant byte first: B G R x
            row[0::3] = line[2::4]
            row[1::3] = line[1::4]
            row[2::3] = line[0::4]
        else:
            row[0::3] = line[1::4]
            row[1::3] = line[2::4]
            row[2::3] = line[3::4]
        if times > 1:
            wide = bytearray(part_width * 3 * times)
            for i in range(times):
                wide[0 + 3 * i::3 * times] = row[0::3]
                wide[1 + 3 * i::3 * times] = row[1::3]
                wide[2 + 3 * i::3 * times] = row[2::3]
            row = wide
        rows.extend([b'\x00' + bytes(row)] * times)
    width, height = part_width * times, part_height * times

    def chunk(kind, body):
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body) & 0xFFFFFFFF)

    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(b''.join(rows), 6)) + chunk(b'IEND', b'')
    open(dst, 'wb').write(png)


if __name__ == '__main__':
    numbers = [int(a) for a in sys.argv[3:]]
    main(sys.argv[1], sys.argv[2], tuple(numbers[:4]) if len(numbers) >= 4 else None, numbers[4] if len(numbers) >= 5 else 1)
