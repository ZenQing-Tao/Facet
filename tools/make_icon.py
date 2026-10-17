"""生成自适应图标素材（背景 / 前景 / 单色）和 1024 预览图。需要 Pillow。

    python tools/make_icon.py
"""
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "res", "drawable-nodpi")
SS = 4                      # 超采样倍数
N = 432                     # 108dp @ xxxhdpi
S = N * SS

# 宝石的顶点（432 画布坐标）
L, R = (96, 178), (336, 178)
TL, TR = (160, 118), (272, 118)
G1, G2, G3 = (156, 178), (216, 178), (276, 178)
P = (216, 322)

FACETS = [
    ([L, TL, G1], (255, 255, 255, 240)),
    ([TL, G1, G2], (206, 232, 255, 240)),
    ([TL, TR, G2], (255, 255, 255, 252)),
    ([TR, G2, G3], (184, 216, 250, 240)),
    ([TR, G3, R], (228, 242, 255, 240)),
    ([L, G1, P], (140, 196, 246, 240)),
    ([G1, G2, P], (232, 246, 255, 245)),
    ([G2, G3, P], (112, 170, 236, 240)),
    ([G3, R, P], (166, 204, 246, 240)),
]


SHRINK = 0.84               # 整体向中心收一点，留出安全区


def sc(pts):
    c = N / 2
    return [((c + (x - c) * SHRINK) * SS, (c + (y - c) * SHRINK + 4) * SS) for x, y in pts]


def down(img):
    return img.resize((N, N), Image.LANCZOS)


def background():
    img = Image.new("RGB", (S, S))
    px = img.load()
    c0, c1 = (92, 76, 235), (16, 186, 168)
    for y in range(S):
        for x in range(S):
            t = (x + y) / (2.0 * S)
            px[x, y] = tuple(int(c0[i] + (c1[i] - c0[i]) * t) for i in range(3))
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((S * 0.05, -S * 0.35, S * 0.95, S * 0.45), fill=(255, 255, 255, 46))
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.06))
    img = Image.alpha_composite(img.convert("RGBA"), glow)
    return down(img)


def sparkle(draw, cx, cy, r, fill):
    k = r * 0.26
    pts = [(cx, cy - r), (cx + k, cy - k), (cx + r, cy), (cx + k, cy + k),
           (cx, cy + r), (cx - k, cy + k), (cx - r, cy), (cx - k, cy - k)]
    draw.polygon(sc(pts), fill=fill)


def foreground():
    shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.polygon(sc([L, TL, TR, R, P]), fill=(20, 20, 80, 90))
    shadow = shadow.filter(ImageFilter.GaussianBlur(S * 0.018))
    shadow = shadow.transform(shadow.size, Image.AFFINE, (1, 0, 0, 0, 1, -8 * SS))

    gem = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(gem)
    for pts, col in FACETS:
        gd.polygon(sc(pts), fill=col)
    line = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ld = ImageDraw.Draw(line)
    for pts, _ in FACETS:
        ld.line(sc(pts + [pts[0]]), fill=(70, 90, 200, 70), width=int(2.0 * SS), joint="curve")
    gem = Image.alpha_composite(gem, line)

    out = Image.alpha_composite(shadow, gem)
    sp = ImageDraw.Draw(out)
    sparkle(sp, 306, 108, 34, (255, 224, 130, 255))
    sparkle(sp, 340, 150, 14, (255, 255, 255, 235))
    return down(out)


def monochrome():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.polygon(sc([L, TL, TR, R, P]), fill=(0, 0, 0, 255))
    gaps = Image.new("L", (S, S), 0)
    gd = ImageDraw.Draw(gaps)
    for pts, _ in FACETS:
        gd.line(sc(pts + [pts[0]]), fill=255, width=int(4.2 * SS), joint="curve")
    img.putalpha(Image.composite(Image.new("L", (S, S), 0), img.getchannel("A"), gaps))
    sparkle(ImageDraw.Draw(img), 306, 108, 34, (0, 0, 0, 255))
    return down(img)


def preview(bg, fg):
    size = 1024
    comp = Image.alpha_composite(bg, fg).resize((size, size), Image.LANCZOS)
    # 取中间 72/108 的可视区域再放大，圆角方形
    crop = int(size * 18 / 108)
    comp = comp.crop((crop, crop, size - crop, size - crop)).resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size * 2, size * 2), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size * 2 - 1, size * 2 - 1), radius=int(size * 2 * 0.225), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(comp, (0, 0), mask)
    return out


if __name__ == "__main__":
    os.makedirs(RES, exist_ok=True)
    bg, fg, mono = background(), foreground(), monochrome()
    bg.convert("RGB").save(os.path.join(RES, "ic_launcher_background.png"))
    fg.save(os.path.join(RES, "ic_launcher_foreground.png"))
    mono.save(os.path.join(RES, "ic_launcher_monochrome.png"))
    os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
    preview(bg, fg).save(os.path.join(ROOT, "docs", "icon.png"))
    print("done")
