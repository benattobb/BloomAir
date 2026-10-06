#!/usr/bin/env python3
import zlib
import struct
import math
import os

def decode_png(path):
    with open(path, 'rb') as f:
        data = f.read()
    w, h = struct.unpack('>II', data[16:24])
    pos = 8
    idat = b''
    while pos < len(data):
        length, chunk_type = struct.unpack('>I4s', data[pos:pos+8])
        pos += 8
        if chunk_type == b'IDAT':
            idat += data[pos:pos+length]
        pos += length + 4
    decompressed = zlib.decompress(idat)
    stride = 1 + w * 4
    pixels = []
    prev_row = [0] * (w * 4)
    for y in range(h):
        filter_type = decompressed[y * stride]
        row_raw = list(decompressed[y * stride + 1 : (y + 1) * stride])
        row = [0] * (w * 4)
        if filter_type == 0:
            row = row_raw
        elif filter_type == 1:
            for x in range(w * 4):
                left = row[x - 4] if x >= 4 else 0
                row[x] = (row_raw[x] + left) & 0xff
        elif filter_type == 2:
            for x in range(w * 4):
                row[x] = (row_raw[x] + prev_row[x]) & 0xff
        elif filter_type == 3:
            for x in range(w * 4):
                left = row[x - 4] if x >= 4 else 0
                up = prev_row[x]
                row[x] = (row_raw[x] + ((left + up) >> 1)) & 0xff
        elif filter_type == 4:
            for x in range(w * 4):
                a = row[x - 4] if x >= 4 else 0
                b = prev_row[x]
                c = prev_row[x - 4] if x >= 4 else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                row[x] = (row_raw[x] + pr) & 0xff
        prev_row = row
        pixels.append(row)
    return w, h, pixels

def encode_png(width, height, rgba_buffer):
    raw_data = bytearray()
    for y in range(height):
        raw_data.append(0)
        row_start = y * width * 4
        raw_data.extend(rgba_buffer[row_start : row_start + width * 4])
    compressed = zlib.compress(raw_data, 9)

    out = bytearray(b'\x89PNG\r\n\x1a\n')
    ihdr = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    out.extend(struct.pack('>I', len(ihdr)))
    out.extend(b'IHDR' + ihdr)
    out.extend(struct.pack('>I', zlib.crc32(b'IHDR' + ihdr)))

    out.extend(struct.pack('>I', len(compressed)))
    out.extend(b'IDAT' + compressed)
    out.extend(struct.pack('>I', zlib.crc32(b'IDAT' + compressed)))

    out.extend(struct.pack('>I', 0))
    out.extend(b'IEND')
    out.extend(struct.pack('>I', zlib.crc32(b'IEND')))
    return bytes(out)

def get_pixel_bilinear(src_w, src_h, src_pixels, u, v):
    if u < 0 or u >= src_w - 1 or v < 0 or v >= src_h - 1:
        x = max(0, min(src_w - 1, int(round(u))))
        y = max(0, min(src_h - 1, int(round(v))))
        row = src_pixels[y]
        idx = x * 4
        return row[idx], row[idx+1], row[idx+2], row[idx+3]

    x0 = int(math.floor(u))
    y0 = int(math.floor(v))
    x1 = x0 + 1
    y1 = y0 + 1
    fx = u - x0
    fy = v - y0

    r00, g00, b00, a00 = src_pixels[y0][x0*4 : x0*4+4]
    r10, g10, b10, a10 = src_pixels[y0][x1*4 : x1*4+4]
    r01, g01, b01, a01 = src_pixels[y1][x0*4 : x0*4+4]
    r11, g11, b11, a11 = src_pixels[y1][x1*4 : x1*4+4]

    def interp(c00, c10, c01, c11):
        top = c00 * (1 - fx) + c10 * fx
        bot = c01 * (1 - fx) + c11 * fx
        return int(round(top * (1 - fy) + bot * fy))

    return interp(r00, r10, r01, r11), interp(g00, g10, g01, g11), interp(b00, b10, b01, b11), interp(a00, a10, a01, a11)

def render_sage_icon(size, src_w, src_h, src_pixels, is_round=False):
    buf = bytearray(size * size * 4)
    radius = size * 0.5 if is_round else size * 0.25
    icon_pad = size * 0.22

    # Calming Sage background: #C8D5CD
    bg_top = (212, 222, 215)
    bg_bot = (195, 208, 199)
    rim_color = (175, 190, 180)

    # Dark Iron: #181A1C (matte cast iron)
    iron_r, iron_g, iron_b = 24, 26, 28

    center = size / 2.0

    for y in range(size):
        for x in range(size):
            idx = (y * size + x) * 4

            if is_round:
                dx = x - center
                dy = y - center
                dist = math.sqrt(dx * dx + dy * dy)
            else:
                dx = max(0, abs(x - center) - (center - radius))
                dy = max(0, abs(y - center) - (center - radius))
                dist = math.sqrt(dx * dx + dy * dy)

            if dist > radius:
                buf[idx : idx + 4] = [0, 0, 0, 0]
                continue

            t = y / float(size)
            cr = int(bg_top[0] * (1 - t) + bg_bot[0] * t)
            cg = int(bg_top[1] * (1 - t) + bg_bot[1] * t)
            cb = int(bg_top[2] * (1 - t) + bg_bot[2] * t)

            # Refined inner rim
            if dist >= radius - max(1.5, size * 0.025):
                cr = int(cr * 0.3 + rim_color[0] * 0.7)
                cg = int(cg * 0.3 + rim_color[1] * 0.7)
                cb = int(cb * 0.3 + rim_color[2] * 0.7)

            # Dark Iron Blossom Icon in center
            if icon_pad <= x < size - icon_pad and icon_pad <= y < size - icon_pad:
                src_u = (x - icon_pad) / (size - 2 * icon_pad) * (src_w - 1)
                src_v = (y - icon_pad) / (size - 2 * icon_pad) * (src_h - 1)
                ir, ig, ib, ia = get_pixel_bilinear(src_w, src_h, src_pixels, src_u, src_v)
                if ia > 10:
                    alpha = ia / 255.0
                    cr = int(cr * (1 - alpha) + iron_r * alpha)
                    cg = int(cg * (1 - alpha) + iron_g * alpha)
                    cb = int(cb * (1 - alpha) + iron_b * alpha)

            edge_alpha = 255
            if radius - dist < 1.0:
                edge_alpha = int(255 * max(0, radius - dist))

            buf[idx : idx + 4] = [cr, cg, cb, edge_alpha]

    return buf

def render_adaptive_foreground(size, src_w, src_h, src_pixels):
    buf = bytearray(size * size * 4)
    icon_pad = int(size * 0.23)
    iron_r, iron_g, iron_b = 24, 26, 28

    for y in range(size):
        for x in range(size):
            idx = (y * size + x) * 4
            if icon_pad <= x < size - icon_pad and icon_pad <= y < size - icon_pad:
                src_u = (x - icon_pad) / float(size - 2 * icon_pad) * (src_w - 1)
                src_v = (y - icon_pad) / float(size - 2 * icon_pad) * (src_h - 1)
                ir, ig, ib, ia = get_pixel_bilinear(src_w, src_h, src_pixels, src_u, src_v)
                if ia > 10:
                    buf[idx : idx + 4] = [iron_r, iron_g, iron_b, ia]
                else:
                    buf[idx : idx + 4] = [0, 0, 0, 0]
            else:
                buf[idx : idx + 4] = [0, 0, 0, 0]
    return buf

def render_tv_banner(width, height, icon_buf, icon_size):
    buf = bytearray(width * height * 4)
    for y in range(height):
        for x in range(width):
            idx = (y * width + x) * 4
            buf[idx : idx + 4] = [21, 22, 24, 255]

    start_x = int(height * 0.18)
    start_y = int((height - icon_size) / 2.0)
    for iy in range(icon_size):
        for ix in range(icon_size):
            s_idx = (iy * icon_size + ix) * 4
            d_x = start_x + ix
            d_y = start_y + iy
            if 0 <= d_x < width and 0 <= d_y < height:
                d_idx = (d_y * width + d_x) * 4
                sr, sg, sb, sa = icon_buf[s_idx : s_idx + 4]
                if sa > 0:
                    alpha = sa / 255.0
                    dr, dg, db = buf[d_idx : d_idx + 3]
                    buf[d_idx] = int(dr * (1 - alpha) + sr * alpha)
                    buf[d_idx+1] = int(dg * (1 - alpha) + sg * alpha)
                    buf[d_idx+2] = int(db * (1 - alpha) + sb * alpha)
    return buf

if __name__ == "__main__":
    import sys

    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {sys.argv[0]} <source-icon.png>")
    input_icon = sys.argv[1]
    res_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "res")

    print("[*] Decoding uploaded icon...")
    src_w, src_h, src_pixels = decode_png(input_icon)
    print(f"[+] Loaded {src_w}x{src_h} image.")

    sizes = [
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
        ("drawable", 512),
    ]

    for dir_name, size in sizes:
        target_dir = os.path.join(res_dir, dir_name)
        os.makedirs(target_dir, exist_ok=True)
        print(f"[*] Rendering {size}x{size} sage icons for {dir_name}...")

        # Squircle
        icon_buf = render_sage_icon(size, src_w, src_h, src_pixels, is_round=False)
        png_data = encode_png(size, size, icon_buf)
        fname = "ic_bloomair_512.png" if dir_name == "drawable" else "ic_bloomair_launcher.png"
        with open(os.path.join(target_dir, fname), "wb") as f:
            f.write(png_data)
        # Also maintain legacy name to be safe
        fname_legacy = "ic_launcher_512.png" if dir_name == "drawable" else "ic_launcher.png"
        with open(os.path.join(target_dir, fname_legacy), "wb") as f:
            f.write(png_data)

        # Full Circle
        if dir_name != "drawable":
            round_buf = render_sage_icon(size, src_w, src_h, src_pixels, is_round=True)
            round_png = encode_png(size, size, round_buf)
            with open(os.path.join(target_dir, "ic_bloomair_launcher_round.png"), "wb") as f:
                f.write(round_png)
            with open(os.path.join(target_dir, "ic_launcher_round.png"), "wb") as f:
                f.write(round_png)

    # Adaptive Foreground
    print("[*] Generating Adaptive Icon foreground...")
    fg_buf = render_adaptive_foreground(432, src_w, src_h, src_pixels)
    fg_png = encode_png(432, 432, fg_buf)
    with open(os.path.join(res_dir, "drawable", "ic_bloomair_foreground.png"), "wb") as f:
        f.write(fg_png)
    with open(os.path.join(res_dir, "drawable", "ic_launcher_foreground.png"), "wb") as f:
        f.write(fg_png)

    # TV Banner
    print("[*] Rendering TV Banner (320x180 & 640x360)...")
    banner_icon_size = 110
    banner_icon = render_sage_icon(banner_icon_size, src_w, src_h, src_pixels, is_round=True)
    banner_320 = render_tv_banner(320, 180, banner_icon, banner_icon_size)
    with open(os.path.join(res_dir, "drawable", "banner_bloomair.png"), "wb") as f:
        f.write(encode_png(320, 180, banner_320))
    with open(os.path.join(res_dir, "drawable", "banner.png"), "wb") as f:
        f.write(encode_png(320, 180, banner_320))

    banner_640_icon = render_sage_icon(220, src_w, src_h, src_pixels, is_round=True)
    banner_640 = render_tv_banner(640, 360, banner_640_icon, 220)
    os.makedirs(os.path.join(res_dir, "drawable-xhdpi"), exist_ok=True)
    with open(os.path.join(res_dir, "drawable-xhdpi", "banner_bloomair.png"), "wb") as f:
        f.write(encode_png(640, 360, banner_640))
    with open(os.path.join(res_dir, "drawable-xhdpi", "banner.png"), "wb") as f:
        f.write(encode_png(640, 360, banner_640))

    print("[+] All icons generated successfully!")
