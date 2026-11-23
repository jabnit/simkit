"""Extract selected CS-25 (Amdt 27) paragraphs from the EASA Easy Access Rules PDF to Markdown.

Usage: python -I extract_cs25.py <pdf> <out.md>
"""
import re
import sys

import pymupdf

HEADER_Y = 80    # page header band (pt from top)
FOOTER_Y = 775   # page footer band starts here
LABEL = re.compile(r"^(\([a-z]{1,4}\)|\([0-9]{1,2}\)|\([A-Z]\)|[0-9]{1,2}\.|[a-z]\.|[ivx]{1,4}\.)$")
LABEL_START = re.compile(r"^(\([a-zA-Z0-9]{1,4}\)|[0-9]{1,2}\.|[a-z]\.|\[|Note|NOTE|Figure|Table|Appendix|Part [IVX]+|CS |AMC |GM |ED Decision)")


def clean_block(text):
    lines = [ln.strip() for ln in text.replace(" ", " ").split("\n")]
    lines = [ln for ln in lines if ln]
    out = ""
    for ln in lines:
        if not out:
            out = ln
        elif re.search(r"[A-Za-z]-$", out):
            out = out + ln               # source has no automatic hyphenation; keep compound hyphen
        else:
            out = out + " " + ln
    out = re.sub(r"\s+", " ", out).strip()
    return out


def read_blocks(doc, first, last):
    """Body blocks as dicts (page, x0, y0, text), header/footer removed."""
    blocks = []
    for pn in range(first, last + 1):
        for bl in doc[pn - 1].get_text("blocks", sort=True):
            if bl[6] != 0 or bl[1] < HEADER_Y or bl[3] > FOOTER_Y:
                continue
            text = clean_block(bl[4])
            if text:
                blocks.append({"page": pn, "x0": bl[0], "y0": bl[1], "text": text})
    # merge continuation blocks (split by page break or layout)
    merged = []
    for b in blocks:
        prev = merged[-1] if merged else None
        if prev and not LABEL_START.match(b["text"]) and not re.search(r"[.;:\]]$", prev["text"]) \
                and not LABEL.match(prev["text"]) and b["text"][0].islower():
            prev["text"] += " " + b["text"]
            prev["pages"].add(b["page"])
            continue
        b["pages"] = {b["page"]}
        merged.append(b)
    for i, b in enumerate(merged):
        nxt = merged[i + 1]["text"] if i + 1 < len(merged) else ""
        b["heading"] = nxt.startswith("ED Decision")
    return merged


def find_item(blocks, pattern):
    """Return blocks from the heading matching pattern up to (excluding) the next heading."""
    rx = re.compile(pattern)
    for i, b in enumerate(blocks):
        if b["heading"] and rx.match(b["text"]):
            j = i + 1
            while j < len(blocks) and not blocks[j]["heading"]:
                j += 1
            return blocks[i:j]
    return None


def sub_para(item, start_rx, x_level, include_head=True):
    """Blocks of one sub-paragraph: from block matching start_rx to next block at same or lower indent."""
    head = item[:3] if include_head else []
    head = [b for b in head if b["heading"] or b["text"].startswith(("ED Decision", "(See"))]
    out = []
    for b in item:
        if not out:
            if re.match(start_rx, b["text"]) and abs(b["x0"] - x_level) < 3:
                out.append(b)
            continue
        if b["x0"] <= x_level + 3 and (LABEL_START.match(b["text"])):
            break
        out.append(b)
    return head + out


def pages_of(blocks):
    pages = sorted({p for b in blocks for p in b["pages"]})
    return f"{pages[0]}" if len(pages) == 1 else f"{pages[0]}-{pages[-1]}"


def to_md(blocks):
    lines = []
    for b in blocks:
        t = b["text"]
        if b["heading"]:
            lines.append(f"**{t}**")
        elif t.startswith("ED Decision"):
            lines.append(t)
        else:
            indent = max(0, round((b["x0"] - 72) / 28))   # em-space per indent level
            lines.append(" " * indent + t)
    return "\n\n".join(lines)


def main(pdf, out_path):
    doc = pymupdf.open(pdf)
    body = read_blocks(doc, 540, 800)
    appx = read_blocks(doc, 1255, 1320)
    full_text = "\n".join(p.get_text() for p in doc)

    sections = []
    missing = []

    def add(title, blocks, note=""):
        if not blocks:
            missing.append(title)
            return
        sections.append((title, pages_of(blocks), note, to_md(blocks)))

    # CS-Definitions entries are not part of the CS-25 Easy Access Rules document
    defs = re.findall(r"[‘'\"“]Fire ?proof[’'\"”]\s+(?:means|,)", full_text, re.I)
    defs += re.findall(r"[‘'\"“]Fire[- ]resistant[’'\"”]\s+(?:means|,)", full_text, re.I)
    if not defs:
        missing.append("CS-Definitions: 'fireproof' and 'fire resistant' (no definition entries in this PDF)")

    it = find_item(body, r"CS 25\.853 ")
    if it:
        sel = []
        for b in it:
            if b["text"].startswith("(e)"):
                break
            sel.append(b)
        add("CS 25.853 Compartment interiors (heading to (d)(4))", sel,
            "Partial: sub-paragraphs (e) to (h) omitted.")
    else:
        missing.append("CS 25.853")

    add("CS 25.855 Cargo or baggage compartments", find_item(body, r"CS 25\.855 "))
    add("CS 25.856 Thermal/acoustic insulation materials", find_item(body, r"CS 25\.856 "))
    for amc in (r"AMC 25\.856\(a\)", r"AMC 25\.856\(b\)"):
        it = find_item(body, amc)
        if it:
            first = min(b["page"] for b in it)
            it = [b for b in it if b["page"] == first]
        add(amc.replace("\\", "") + " (first page only)", it)

    it = find_item(body, r"CS 25\.857 ")
    if it:
        sel = [b for b in it if b["heading"] or b["text"].startswith(("ED Decision", "(See"))
               or (abs(b["x0"] - 72) < 3 and re.match(r"\([a-f]\)", b["text"]))]
        add("CS 25.857 Cargo compartment classification (class headings only)", sel,
            "Partial: top-level paragraphs (a) to (f) only; numbered sub-items omitted.")
    else:
        missing.append("CS 25.857")

    add("CS 25.865", find_item(body, r"CS 25\.865 "))
    add("CS 25.867", find_item(body, r"CS 25\.867 "))
    it = find_item(body, r"CS 25\.869 ")
    add("CS 25.869(a)", sub_para(it, r"\(a\)", 72) if it else None,
        "Partial: sub-paragraph (a) only.")

    it = find_item(body, r"CS 25\.903 ")
    if it:
        d = sub_para(it, r"\(d\)", 72)
        head = [b for b in d if b["heading"] or b["text"].startswith(("ED Decision", "(See"))]
        rest = [b for b in d if b not in head]
        d1 = rest[:1] + [b for b in sub_para(rest, r"\(1\)", rest[1]["x0"], False)] if len(rest) > 1 else rest
        add("CS 25.903(d)(1)", head + d1, "Partial: heading, (d) lead-in and (d)(1) only.")
    else:
        missing.append("CS 25.903(d)(1)")
    add("AMC 25.903(d)(1)", find_item(body, r"AMC 25\.903\(d\)\(1\)"))

    add("CS 25.1181", find_item(body, r"CS 25\.1181 "))
    add("AMC 25.1181", find_item(body, r"AMC 25\.1181 "))
    add("CS 25.1182", find_item(body, r"CS 25\.1182 "))
    add("CS 25.1183", find_item(body, r"CS 25\.1183 "))
    add("CS 25.1191", find_item(body, r"CS 25\.1191 "))
    add("CS 25.1193", find_item(body, r"CS 25\.1193 "))
    add("AMC 25.1193(e)", find_item(body, r"AMC 25\.1193\(e\) "))

    add("Appendix F Part III", find_item(appx, r"Part III "))
    add("Appendix F Part VII", find_item(appx, r"Part VII "))

    with open(out_path, "w", encoding="utf-8") as f:
        f.write("# CS-25 Amendment 27 (Easy Access Rules, Jan 2023) - extracted paragraphs\n\n")
        f.write("Source: CS-25 (Amendment 27).pdf. Text extracted verbatim; page headers/footers removed; "
                "line breaks joined; superscripts appear inline (e.g. cm2, oC as printed). Page numbers are PDF page numbers (equal to printed page numbers).\n\n")
        f.write("## Items not found\n\n" + ("\n".join(f"- {m}" for m in missing) or "- none") + "\n\n")
        for title, pages, note, md in sections:
            f.write(f"## {title} (PDF p. {pages})\n\n")
            if note:
                f.write(f"*{note}*\n\n")
            f.write(md + "\n\n")
    for title, pages, note, _ in sections:
        print(f"{title}: p. {pages}")
    print("missing:", missing)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
