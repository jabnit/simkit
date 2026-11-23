"""Export fire_thermal_analysis_method.md to .docx with pandoc (local), then check the result."""
import shutil
import subprocess
import sys
from pathlib import Path

from docx import Document
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

DOC_DIR = Path(__file__).resolve().parent.parent
SRC = DOC_DIR / "fire_thermal_analysis_method.md"
OUT = DOC_DIR / "fire_thermal_analysis_method.docx"
PANDOC_FALLBACK = Path.home() / "AppData/Local/Pandoc/pandoc.exe"


def pandoc_exe():
    exe = shutil.which("pandoc")
    if exe:
        return exe
    if PANDOC_FALLBACK.exists():
        return str(PANDOC_FALLBACK)
    sys.exit("pandoc not found")


def run_pandoc():
    cmd = [pandoc_exe(), str(SRC.name), "-f", "markdown+tex_math_dollars+pipe_tables+task_lists",
           "-t", "docx", "--resource-path", str(DOC_DIR), "-o", str(OUT)]
    subprocess.run(cmd, check=True, cwd=DOC_DIR)


def add_table_borders(doc):
    # pandoc default "Table" style has no borders
    for table in doc.tables:
        tbl_pr = table._tbl.tblPr
        for old in tbl_pr.findall(qn("w:tblBorders")):
            tbl_pr.remove(old)
        borders = OxmlElement("w:tblBorders")
        for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
            el = OxmlElement(f"w:{edge}")
            el.set(qn("w:val"), "single")
            el.set(qn("w:sz"), "4")
            el.set(qn("w:space"), "0")
            el.set(qn("w:color"), "808080")
            borders.append(el)
        tbl_pr.append(borders)


HEADING_STYLES = ["Title", "Subtitle"] + [f"Heading {i}" for i in range(1, 10)]
CAPTION_STYLES = ["Image Caption", "Caption", "Table Caption"]


def set_colour(style, hex_rgb):
    rpr = style.element.get_or_add_rPr()
    for old in rpr.findall(qn("w:color")):
        rpr.remove(old)
    col = OxmlElement("w:color")
    col.set(qn("w:val"), hex_rgb)
    rpr.append(col)


def style_colours(doc):
    # headings black, figure and table captions grey; linked character styles follow
    names = {s.name: s for s in doc.styles if s.name}
    for n in HEADING_STYLES:
        for key in (n, n + " Char"):
            if key in names:
                set_colour(names[key], "000000")
    for n in CAPTION_STYLES:
        if n in names:
            set_colour(names[n], "606060")


def check(doc):
    body = doc.element.body
    n_images = len(body.findall(".//" + qn("w:drawing")))
    n_math = len(body.findall(".//{http://schemas.openxmlformats.org/officeDocument/2006/math}oMath"))
    headings = {}
    for p in doc.paragraphs:
        name = p.style.name
        if name.startswith("Heading") or name == "Title":
            headings[name] = headings.get(name, 0) + 1
    captions = sum(1 for p in doc.paragraphs if p.style.name in ("Image Caption", "Caption"))
    widths = [round(s.width.inches, 2) for s in doc.inline_shapes]
    print(f"tables: {len(doc.tables)}")
    print(f"images: {n_images} (inline shapes {len(doc.inline_shapes)}, widths in: {widths})")
    print(f"captions: {captions}")
    print(f"OMML equations: {n_math}")
    print("headings: " + ", ".join(f"{k} {v}" for k, v in sorted(headings.items()))
          + f" (total {sum(headings.values())})")


if __name__ == "__main__":
    run_pandoc()
    doc = Document(OUT)
    add_table_borders(doc)
    style_colours(doc)
    doc.save(OUT)
    check(Document(OUT))
