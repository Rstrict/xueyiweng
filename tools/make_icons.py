"""生成「学一问」Android 图标（mipmap 各密度 + adaptive icon 前景）。
纯 Pillow 绘制。
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"

BG = (43, 91, 132, 255)      # #2B5B84
FG = (247, 246, 242, 255)    # #F7F6F2

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
# adaptive icon 前景画布是 108dp，安全区 72dp
FOREGROUND = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}


def draw_clock(size: int, scale: float = 0.72, bg=BG, fg=FG, transparent_bg=False) -> Image.Image:
    ss = 4
    S = size * ss
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    if not transparent_bg:
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.235), fill=bg)

    cx = cy = S / 2
    r = S * scale / 2
    ring_w = max(2, int(S * 0.048))

    box = [cx - r, cy - r, cx + r, cy + r]
    d.ellipse(box, outline=(fg[0], fg[1], fg[2], 78), width=ring_w)
    d.arc(box, start=-90, end=-90 + int(360 * 0.78), fill=fg, width=ring_w)

    hand_w = max(2, int(S * 0.042))
    d.line([cx, cy, cx, cy - r * 0.72], fill=fg, width=hand_w)
    d.line([cx, cy, cx + r * 0.46, cy], fill=fg, width=hand_w)
    dot = max(2, int(S * 0.056))
    d.ellipse([cx - dot, cy - dot, cx + dot, cy + dot], fill=fg)

    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    for name, px in DENSITIES.items():
        d = RES / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)
        draw_clock(px).save(d / "ic_launcher.png", "PNG", optimize=True)
        # round：圆形裁切
        base = draw_clock(px)
        mask = Image.new("L", (px * 4, px * 4), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, px * 4 - 1, px * 4 - 1], fill=255)
        mask = mask.resize((px, px), Image.LANCZOS)
        out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        out.paste(base, (0, 0), mask)
        out.save(d / "ic_launcher_round.png", "PNG", optimize=True)
        print(f"mipmap-{name}: {px}px")

    # adaptive icon 前景：透明底，内容压在安全区内
    for name, px in FOREGROUND.items():
        d = RES / f"mipmap-{name}"
        draw_clock(px, scale=0.52, transparent_bg=True).save(
            d / "ic_launcher_foreground.png", "PNG", optimize=True
        )
    print("adaptive foreground done")


if __name__ == "__main__":
    main()
