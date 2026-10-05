"""Render the README hero and GitHub social preview from synthetic native captures.

    python -m pip install -r tools/showcase-requirements.txt
    python tools/render-showcase.py

Onest Bold/Medium are bundled under SIL OFL 1.1 (tools/fonts/FONT-LICENSE.txt),
unchanged from pavel-logachev/pavel-logachev/scripts/fonts. Technical labels use
Consolas on Windows or DejaVu Sans Mono on Linux, falling back to bundled Onest.
Screenshots come only from docs/screenshots; never read private local artifacts.
The control-room palette and typography follow the owner's profile design system.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "assets"
SCREENS = ROOT / "docs" / "screenshots"
FONTS = Path(__file__).resolve().parent / "fonts"
BG = (5, 7, 10)
BG2 = (9, 13, 18)
FG = (233, 238, 242)
MUTED = (143, 154, 163)
LIME = (195, 244, 81)
STEEL = (127, 153, 173)
HAIR = (39, 46, 53)
MONO = next((p for p in (
    Path("C:/Windows/Fonts/consola.ttf"),
    Path("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"),
) if p.is_file()), FONTS / "Onest-Medium.ttf")


def font(size: int, bold: bool = False, mono: bool = False) -> ImageFont.FreeTypeFont:
    path = MONO if mono else FONTS / ("Onest-Bold.ttf" if bold else "Onest-Medium.ttf")
    return ImageFont.truetype(str(path), size)


def phone(name: str, width: int) -> Image.Image:
    """Keep the full native screenshot intact, inside a thin rounded device frame."""
    bezel = 10
    shot = Image.open(SCREENS / name).convert("RGB")
    shot = ImageOps.contain(shot, (width - 2 * bezel, 2400), Image.Resampling.LANCZOS)
    tile = Image.new("RGBA", (width, shot.height + 2 * bezel), (0, 0, 0, 0))
    d = ImageDraw.Draw(tile)
    bounds = (0, 0, tile.width - 1, tile.height - 1)
    d.rounded_rectangle(bounds, radius=44, fill=(16, 20, 24), outline=(83, 96, 107), width=2)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, shot.width - 1, shot.height - 1), radius=34, fill=255)
    tile.paste(shot, (bezel, bezel), mask)
    return tile


def render() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    canvas = Image.new("RGBA", (2400, 1260))
    d = ImageDraw.Draw(canvas)
    for y in range(canvas.height):
        t = y / (canvas.height - 1)
        colour = tuple(round(a + (b - a) * t) for a, b in zip(BG, BG2))
        d.line((0, y, canvas.width, y), fill=colour)

    def text(x: int, y: int, label: str, size: int, colour=FG, bold=False, mono=False, right=1140):
        face = font(size, bold=bold, mono=mono)
        box = d.textbbox((x, y), label, font=face, anchor="lt")
        assert box[2] <= right and box[3] <= 1200, f"Text exceeds its safe area: {label}"
        d.text((x, y), label, font=face, fill=colour, anchor="lt")

    text(110, 104, "NATIVE ANDROID / LOCAL-FIRST", 27, STEEL, mono=True)
    d.line((110, 168, 2290, 168), fill=HAIR, width=2)
    text(110, 249, "DSH Mobile", 142, bold=True)
    text(110, 470, "Чаты DeepSeek Harness", 65, bold=True)
    text(110, 553, "со всех проектов — в телефоне", 60, LIME, bold=True)
    text(114, 695, "Компьютер работает.", 38, MUTED)
    text(114, 750, "Телефон показывает и управляет. Без облака.", 38, MUTED)

    x = 114
    for label in ("ANDROID 8+", "QR-ПРИВЯЗКА", "WI-FI / TAILSCALE"):
        face = font(25, mono=True)
        width = round(d.textlength(label, font=face)) + 40
        assert x + width <= 1140, "Chip row exceeds the text column"
        d.rounded_rectangle((x, 865, x + width, 925), radius=30, outline=HAIR, width=2)
        d.text((x + 20, 884), label, font=face, fill=FG, anchor="lt")
        x += width + 18

    # A quiet computer-to-phone route: execution stays on the computer.
    d.rounded_rectangle((116, 1045, 180, 1087), radius=5, outline=STEEL, width=2)
    d.line((148, 1087, 148, 1100), fill=STEEL, width=2)
    d.line((130, 1100, 166, 1100), fill=STEEL, width=2)
    d.line((202, 1066, 372, 1066), fill=HAIR, width=2)
    d.ellipse((280, 1061, 290, 1071), fill=LIME)
    d.rounded_rectangle((394, 1036, 424, 1097), radius=7, outline=STEEL, width=2)
    text(452, 1054, "ПК ↔ ТЕЛЕФОН", 26, STEEL, mono=True)
    text(114, 1155, "Независимый неофициальный проект", 26, MUTED)

    placements = (
        ("home-dark.png", 1210, 330, 330, "01 / ВСЕ ЧАТЫ"),
        ("chat-markdown-running.png", 1570, 250, 370, "02 / ЖИВОЙ ОТВЕТ"),
        ("new-chat-sheet.png", 1970, 390, 320, "03 / НОВЫЙ ЧАТ"),
    )
    for name, x, y, width, label in placements:
        tile = phone(name, width)
        assert x + tile.width <= 2290 and y + tile.height <= 1140, "Phone exceeds its safe area"
        canvas.alpha_composite(tile, (x, y))
        text(x, y - 45, label, 22, STEEL, mono=True, right=2290)

    # The fixture is a UI illustration, not proof of real agent execution.
    text(1210, 1164, "Экраны приложения · синтетические демо-данные", 24, MUTED, right=2290)
    hero = canvas.convert("RGB")
    social = hero.crop((0, 30, 2400, 1230)).resize((1280, 640), Image.Resampling.LANCZOS)
    for image, name in ((hero, "dsh-mobile-showcase.png"), (social, "dsh-mobile-social-preview.png")):
        path = OUT / name
        image.save(path, optimize=True)
        assert path.stat().st_size < 1_000_000, f"PNG exceeds the 1 MB budget: {name}"
        print(f"{path.relative_to(ROOT)}: {image.width}x{image.height}, {path.stat().st_size:,} bytes")


if __name__ == "__main__":
    render()
