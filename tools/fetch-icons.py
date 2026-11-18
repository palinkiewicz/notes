#!/usr/bin/env python3
"""Fetch the Material Symbols Rounded (outline, and filled where asked) used by DakNote.

Reads tools/icons.txt and writes Android vector drawables into core/ui/src/main/res/drawable as
sym_<name>.xml and, for names marked " fill", sym_<name>_fill.xml. NotesIcons maps them to the
app's own names. Material Symbols are Apache-2.0 licensed
(https://github.com/google/material-design-icons).

Drawables rather than Kotlin path strings: dex is stored uncompressed in the APK, while resource XML
is deflated, so the same paths cost about a third as much this way.

Usage: python3 -I tools/fetch-icons.py
"""
import concurrent.futures
import pathlib
import re
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
LIST = ROOT / "tools" / "icons.txt"
RES = ROOT / "core/ui/src/main/res/drawable"
URL = "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/{n}/materialsymbolsrounded/{f}"

# Directional glyphs that must flip in right-to-left layouts.
MIRRORED = {
    "arrow_back", "arrow_forward", "chevron_right", "undo", "redo", "open_in_new", "sort",
    "format_list_bulleted", "format_list_numbered", "checklist", "format_indent_decrease",
    "format_indent_increase", "format_quote", "input", "drive_file_move", "label", "note_stack",
}


def fetch(name: str, filled: bool) -> str | None:
    file = f"{name}_fill1_24px.xml" if filled else f"{name}_24px.xml"
    try:
        with urllib.request.urlopen(URL.format(n=name, f=file), timeout=30) as response:
            return response.read().decode("utf-8")
    except urllib.error.HTTPError:
        return None


def clean(xml: str, mirrored: bool) -> str:
    # Tint comes from Compose's LocalContentColor; a theme-attr tint only gets in the way.
    xml = re.sub(r'\s*android:tint="[^"]*"', "", xml)
    xml = re.sub(r'\s*android:autoMirrored="[^"]*"', "", xml)
    if mirrored:
        xml = xml.replace("<vector ", '<vector android:autoMirrored="true" ', 1)
    return xml


def main() -> int:
    wanted: dict[str, bool] = {}
    for line in LIST.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        wanted[parts[0]] = wanted.get(parts[0], False) or (len(parts) > 1 and parts[1] == "fill")

    RES.mkdir(parents=True, exist_ok=True)
    for old in RES.glob("sym_*.xml"):
        old.unlink()

    def download(item):
        name, fill = item
        return name, fetch(name, filled=False), fetch(name, filled=True) if fill else None, fill

    missing = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        for name, outline, filled, fill in pool.map(download, wanted.items()):
            if outline is None or (fill and filled is None):
                missing.append(name)
                continue
            mirrored = name in MIRRORED
            (RES / f"sym_{name}.xml").write_text(clean(outline, mirrored))
            if fill:
                (RES / f"sym_{name}_fill.xml").write_text(clean(filled, mirrored))

    count = len(list(RES.glob("sym_*.xml")))
    print(f"Wrote {count} drawables to {RES.relative_to(ROOT)}")
    if missing:
        print("Missing (check the name at fonts.google.com/icons):", ", ".join(missing), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
