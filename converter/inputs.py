"""Markdown and DOCX readers for the converter's document intermediate form.

Only local image data is read. Unsupported layout features are reported in
``warnings`` so a successful conversion does not imply complete fidelity.
"""
from __future__ import annotations

import hashlib
import io
import re
import zipfile
from pathlib import Path
from urllib.parse import unquote, urlparse

from PIL import Image


MAX_IMAGE_BYTES = 50 * 1024 * 1024
MAX_DOCX_BYTES = 512 * 1024 * 1024


def _warn(warnings, message):
    if message not in warnings:
        warnings.append(message)


def _run(text, **style):
    return {"text": text, "bold": False, "italic": False,
            "underline": False, "strike": False, **style}


def _paragraph(runs=None, heading=0, alignment="left", **extra):
    return {"type": "paragraph", "runs": runs or [], "heading": heading,
            "alignment": alignment, **extra}


def _append_run(runs, run):
    if not run["text"]:
        return
    if runs and {k: v for k, v in runs[-1].items() if k != "text"} == {
            k: v for k, v in run.items() if k != "text"}:
        runs[-1]["text"] += run["text"]
    else:
        runs.append(run)


def _save_image(blob, assets, warnings, dimensions=None, alt=""):
    if len(blob) > MAX_IMAGE_BYTES:
        _warn(warnings, "An image exceeds 50 MiB and was omitted.")
        return None
    try:
        with Image.open(io.BytesIO(blob)) as source:
            source.load()
            if getattr(source, "n_frames", 1) > 1:
                _warn(warnings, "Animated images are converted to their first frame.")
            image = source.convert("RGBA")
            width, height = image.size
            output = io.BytesIO()
            image.save(output, format="PNG")
            data = output.getvalue()
    except (OSError, ValueError, Image.DecompressionBombError):
        _warn(warnings, "An unsupported or invalid image was omitted.")
        return None
    target = assets / (hashlib.sha256(data).hexdigest() + ".png")
    # Do not follow an existing symlink while writing extracted image data.
    if target.is_symlink():
        raise ValueError(f"Image asset destination is a symlink: {target}")
    if not target.exists():
        target.write_bytes(data)
    if dimensions:
        width_pt, height_pt = dimensions
    else:
        width_pt, height_pt = width * .75, height * .75
    if width_pt <= 0 or height_pt <= 0:
        width_pt, height_pt = width * .75, height * .75
    return {"type": "image", "path": str(target.resolve()),
            "width_pt": round(width_pt, 3), "height_pt": round(height_pt, 3),
            "alt": alt}


def _md_image(node, source, assets, warnings):
    url = node.get("attrs", {}).get("url", "")
    alt = "".join(x.get("raw", "") for x in node.get("children", []))
    parsed = urlparse(url)
    if parsed.scheme or parsed.netloc:
        _warn(warnings, f"External Markdown image was not fetched: {url}")
        return None
    path = (source.parent / unquote(parsed.path)).resolve()
    try:
        if path.stat().st_size > MAX_IMAGE_BYTES:
            _warn(warnings, f"Markdown image exceeds 50 MiB: {url}")
            return None
        blob = path.read_bytes()
    except OSError:
        _warn(warnings, f"Markdown image could not be read: {url}")
        return None
    return _save_image(blob, assets, warnings, alt=alt)


def _md_inline(nodes, source, assets, warnings, style=None):
    result = []
    style = style or {}
    for node in nodes:
        kind = node["type"]
        if kind in ("text", "inline_html"):
            if kind == "inline_html":
                _warn(warnings, "Inline HTML is preserved as plain text.")
            result.append(_run(node.get("raw", ""), **style))
        elif kind in ("softbreak", "linebreak"):
            result.append(_run("\n" if kind == "linebreak" else " ", **style))
        elif kind == "codespan":
            result.append(_run(node.get("raw", ""), **{**style, "font": "Courier New"}))
        elif kind in ("strong", "emphasis", "strikethrough"):
            flag = {"strong": "bold", "emphasis": "italic",
                    "strikethrough": "strike"}[kind]
            result.extend(_md_inline(node.get("children", []), source, assets,
                                     warnings, {**style, flag: True}))
        elif kind == "image":
            image = _md_image(node, source, assets, warnings)
            if image:
                result.append(image)
            else:
                alt = "".join(x.get("raw", "") for x in node.get("children", []))
                result.append(_run(f"[image: {alt}]", **style))
        elif kind == "link":
            _warn(warnings, "Hyperlinks are converted to formatted text; link targets are omitted.")
            result.extend(_md_inline(node.get("children", []), source, assets,
                                     warnings, {**style, "underline": True}))
        else:
            _warn(warnings, f"Unsupported Markdown inline feature: {kind}")
            if "children" in node:
                result.extend(_md_inline(node["children"], source, assets, warnings, style))
            elif "raw" in node:
                result.append(_run(node["raw"], **style))
    return result


def _split_paragraph(items, **properties):
    result, runs = [], []
    first = True
    for item in items:
        if item.get("type") == "image":
            if runs:
                result.append(_paragraph(runs, **properties))
                runs = []
                first = False
            result.append(item)
        else:
            _append_run(runs, item)
    if runs or not result:
        if not first:
            properties = {k: v for k, v in properties.items()
                          if k not in ("list_prefix", "page_break_before")}
        result.append(_paragraph(runs, **properties))
    return result


def _md_blocks(nodes, source, assets, warnings):
    blocks = []
    for node in nodes:
        kind = node["type"]
        if kind == "blank_line":
            continue
        if kind in ("paragraph", "block_text", "heading"):
            properties = {"heading": node.get("attrs", {}).get("level", 0)}
            blocks.extend(_split_paragraph(
                _md_inline(node.get("children", []), source, assets, warnings), **properties))
        elif kind == "list":
            attrs = node.get("attrs", {})
            ordered = attrs.get("ordered", False)
            start = attrs.get("start", 1)
            depth = attrs.get("depth", 0)
            for index, item in enumerate(node.get("children", [])):
                children = _md_blocks(item.get("children", []), source, assets, warnings)
                prefix = (f"{start + index}." if ordered else "•")
                if item["type"] == "task_list_item":
                    prefix = "☑" if item.get("attrs", {}).get("checked") else "☐"
                prefix = "  " * depth + prefix
                first = next((b for b in children if b["type"] == "paragraph"), None)
                if first is not None:
                    first["list_prefix"] = prefix
                else:
                    children.insert(0, _paragraph(list_prefix=prefix))
                blocks.extend(children)
        elif kind == "table":
            rows = []
            for section in node.get("children", []):
                candidates = ([section] if section["type"] == "table_head"
                              else section.get("children", []))
                for row in candidates:
                    cells = []
                    for cell in row.get("children", []):
                        alignment = cell.get("attrs", {}).get("align") or "left"
                        items = _md_inline(cell.get("children", []), source, assets, warnings,
                                           {"bold": True} if section["type"] == "table_head" else {})
                        cell_blocks = _split_paragraph(items, alignment=alignment)
                        if any(b["type"] != "paragraph" for b in cell_blocks):
                            _warn(warnings, "Images inside table cells are omitted; their alt text is retained.")
                            cell_blocks = [b if b["type"] == "paragraph" else
                                           _paragraph([_run(b.get("alt", ""))]) for b in cell_blocks]
                        cells.append({"blocks": cell_blocks, "colspan": 1, "rowspan": 1})
                    rows.append(cells)
            blocks.append({"type": "table", "rows": rows})
        elif kind == "block_code":
            blocks.append(_paragraph([_run(node.get("raw", ""), font="Courier New")]))
        elif kind == "block_quote":
            _warn(warnings, "Block quote indentation is not retained.")
            blocks.extend(_md_blocks(node.get("children", []), source, assets, warnings))
        elif kind == "thematic_break":
            _warn(warnings, "Horizontal rules are converted to text separators.")
            blocks.append(_paragraph([_run("────────")]))
        elif kind == "block_html":
            raw = node.get("raw", "")
            if re.search(r"page[-_ ]?break", raw, re.I):
                blocks.append(_paragraph(page_break_before=True))
            else:
                _warn(warnings, "Block HTML is preserved as plain text.")
                blocks.append(_paragraph([_run(raw)]))
        else:
            _warn(warnings, f"Unsupported Markdown block feature: {kind}")
            if "children" in node:
                blocks.extend(_md_blocks(node["children"], source, assets, warnings))
            elif "raw" in node:
                blocks.append(_paragraph([_run(node["raw"])]))
    return blocks


def _parse_md(source, assets, warnings):
    import mistune
    markdown = mistune.create_markdown(renderer="ast", plugins=["table", "strikethrough", "task_lists"])
    text = source.read_text(encoding="utf-8-sig")
    return _md_blocks(markdown(text), source, assets, warnings)


def _tag(element):
    return element.tag.rsplit("}", 1)[-1]


def _docx_style(run, paragraph):
    styles = [run.font]
    if run.style:
        styles.append(run.style.font)
    current = paragraph.style
    visited = set()
    while current is not None and current.style_id not in visited:
        visited.add(current.style_id)
        styles.append(current.font)
        current = current.base_style
    result = {}
    for property_name, default in (("bold", False), ("italic", False),
                                   ("underline", False), ("strike", False)):
        value = next((getattr(font, property_name) for font in styles
                      if getattr(font, property_name) is not None), default)
        result[property_name] = bool(value)
    for property_name, key in (("size", "size"), ("name", "font")):
        value = next((getattr(font, property_name) for font in styles
                      if getattr(font, property_name) is not None), None)
        if value is not None:
            result[key] = round(value.pt, 3) if key == "size" else value
    color = next((font.color.rgb for font in styles if font.color.rgb is not None), None)
    if color is not None:
        result["color"] = str(color)
    return result


class _DocxReader:
    def __init__(self, document, assets, warnings):
        self.document = document
        self.assets = assets
        self.warnings = warnings
        self.list_counters = {}

    def list_prefix(self, paragraph):
        from docx.oxml.ns import qn
        props = paragraph._p.pPr
        num = props.numPr if props is not None else None
        if num is None:
            style = paragraph.style
            while style is not None:
                ppr = style.element.pPr
                if ppr is not None and ppr.numPr is not None:
                    num = ppr.numPr
                    break
                style = style.base_style
        if num is None or num.numId is None or num.numId.val == 0:
            return None
        num_id = num.numId.val
        level = num.ilvl.val if num.ilvl is not None else 0
        try:
            root = self.document.part.numbering_part.element
            number = next(x for x in root.findall(qn("w:num"))
                          if int(x.get(qn("w:numId"))) == num_id)
            abstract_id = int(number.find(qn("w:abstractNumId")).get(qn("w:val")))
            abstract = next(x for x in root.findall(qn("w:abstractNum"))
                            if int(x.get(qn("w:abstractNumId"))) == abstract_id)
            entry = next(x for x in abstract.findall(qn("w:lvl"))
                         if int(x.get(qn("w:ilvl"))) == level)
            fmt = entry.find(qn("w:numFmt")).get(qn("w:val"))
            text = entry.find(qn("w:lvlText")).get(qn("w:val"))
            start_element = entry.find(qn("w:start"))
            start = int(start_element.get(qn("w:val"))) if start_element is not None else 1
            key = (num_id, level)
            count = self.list_counters.get(key, start - 1) + 1
            self.list_counters[key] = count
            if fmt == "bullet":
                return "  " * level + "•"
            if fmt != "decimal":
                _warn(self.warnings, f"DOCX {fmt} numbering is converted to decimal numbering.")
            for index in range(9):
                text = text.replace(f"%{index + 1}", str(self.list_counters.get((num_id, index), 1)))
            return "  " * level + text
        except (AttributeError, StopIteration, ValueError, KeyError):
            _warn(self.warnings, "Unresolved DOCX list numbering is converted to a bullet.")
            return "  " * level + "•"

    def image(self, drawing):
        from docx.oxml.ns import qn
        blips = drawing.xpath(".//a:blip")
        if not blips:
            _warn(self.warnings, "A DOCX drawing without a raster image was omitted.")
            return None
        blip = blips[0]
        rel_id = blip.get(qn("r:embed"))
        if not rel_id:
            _warn(self.warnings, "An externally linked DOCX image was not fetched.")
            return None
        try:
            part = self.document.part.related_parts[rel_id]
        except KeyError:
            _warn(self.warnings, "An unresolved DOCX image relationship was omitted.")
            return None
        extents = drawing.xpath(".//wp:extent")
        dimensions = None
        if extents:
            dimensions = (int(extents[0].get("cx")) / 12700,
                          int(extents[0].get("cy")) / 12700)
        properties = drawing.xpath(".//wp:docPr")
        alt = properties[0].get("descr", "") if properties else ""
        if drawing.xpath(".//wp:anchor"):
            _warn(self.warnings, "Floating DOCX images are converted to standalone inline images.")
        return _save_image(part.blob, self.assets, self.warnings, dimensions, alt)

    def paragraph(self, element, parent):
        from docx.text.paragraph import Paragraph
        from docx.text.run import Run
        from docx.oxml.ns import qn
        paragraph = Paragraph(element, parent)
        heading = 0
        style = paragraph.style
        name = style.name if style else ""
        match = re.search(r"(?:Heading|제목)\s*(\d+)$", name, re.I)
        if match:
            heading = min(6, int(match.group(1)))
        elif name.lower() == "title":
            heading = 1
        outline = element.xpath("./w:pPr/w:outlineLvl")
        if outline and int(outline[0].get(qn("w:val"))) < 6:
            heading = int(outline[0].get(qn("w:val"))) + 1
        alignment = {0: "left", 1: "center", 2: "right", 3: "justify"}.get(
            paragraph.alignment, "left")
        if paragraph.alignment is None and style:
            alignment = {0: "left", 1: "center", 2: "right", 3: "justify"}.get(
                style.paragraph_format.alignment, "left")
        properties = {"heading": heading, "alignment": alignment}
        if paragraph.paragraph_format.page_break_before:
            properties["page_break_before"] = True
        prefix = self.list_prefix(paragraph)
        if prefix:
            properties["list_prefix"] = prefix
        blocks, runs = [], []

        def flush(force=False):
            nonlocal runs, properties
            if runs or force:
                blocks.append(_paragraph(runs, **properties))
                runs = []
                properties = {"heading": heading, "alignment": alignment}

        def visit(container, hyperlink=False):
            nonlocal properties
            for child in container:
                kind = _tag(child)
                if kind == "r":
                    run = Run(child, paragraph)
                    formatting = _docx_style(run, paragraph)
                    if hyperlink:
                        formatting["underline"] = True
                    for item in child:
                        item_kind = _tag(item)
                        if item_kind in ("t", "instrText"):
                            if item_kind == "instrText":
                                _warn(self.warnings, "DOCX fields are preserved as their cached text; instructions are omitted.")
                                continue
                            _append_run(runs, _run(item.text or "", **formatting))
                        elif item_kind == "tab":
                            _append_run(runs, _run("\t", **formatting))
                        elif item_kind in ("br", "cr"):
                            if item.get(qn("w:type")) == "page":
                                flush()
                                properties["page_break_before"] = True
                            else:
                                _append_run(runs, _run("\n", **formatting))
                        elif item_kind == "drawing":
                            image = self.image(item)
                            if image:
                                flush()
                                blocks.append(image)
                        elif item_kind in ("pict", "object", "footnoteReference", "endnoteReference"):
                            _warn(self.warnings, f"Unsupported DOCX inline content was omitted: {item_kind}")
                        elif item_kind == "noBreakHyphen":
                            _append_run(runs, _run("‑", **formatting))
                        elif item_kind == "softHyphen":
                            _append_run(runs, _run("\u00ad", **formatting))
                        elif item_kind == "sym":
                            _warn(self.warnings, "A font-specific DOCX symbol was omitted.")
                        elif item_kind == "fldChar":
                            _warn(self.warnings, "DOCX fields are preserved as their cached text; instructions are omitted.")
                elif kind == "hyperlink":
                    _warn(self.warnings, "Hyperlinks are converted to formatted text; link targets are omitted.")
                    visit(child, hyperlink=True)
                elif kind in ("ins", "del", "moveFrom", "moveTo"):
                    _warn(self.warnings, "DOCX tracked changes are flattened to the current accepted text.")
                    if kind in ("ins", "moveTo"):
                        visit(child, hyperlink)
                elif kind == "sdt":
                    _warn(self.warnings, "DOCX content controls are flattened to their visible content.")
                    content = child.find(qn("w:sdtContent"))
                    if content is not None:
                        visit(content, hyperlink)
                elif kind == "fldSimple":
                    _warn(self.warnings, "DOCX fields are preserved as their cached text; instructions are omitted.")
                    visit(child, hyperlink)
                elif kind not in ("pPr", "bookmarkStart", "bookmarkEnd", "proofErr"):
                    _warn(self.warnings, f"Unsupported DOCX paragraph feature: {kind}")
        visit(element)
        flush(force=not blocks or bool(properties.get("page_break_before")))
        return blocks

    def table(self, element, parent):
        from docx.table import Table, _Cell
        from docx.oxml.ns import qn
        table = Table(element, parent)
        rows, active = [], {}
        for row_element in element.findall(qn("w:tr")):
            row, next_active, column = [], {}, 0
            for cell_element in row_element.findall(qn("w:tc")):
                cell = _Cell(cell_element, table)
                props = cell_element.tcPr
                span = props.gridSpan.val if props is not None and props.gridSpan is not None else 1
                merge = props.vMerge if props is not None else None
                if merge is not None and merge.val != "restart" and column in active:
                    existing = active[column]
                    existing["rowspan"] += 1
                    next_active[column] = existing
                    column += span
                    continue
                blocks = self.body(cell_element, cell)
                if any(b["type"] != "paragraph" for b in blocks):
                    _warn(self.warnings, "Nested tables and images in DOCX table cells are flattened to text.")
                    flat = []
                    for block in blocks:
                        if block["type"] == "paragraph":
                            flat.append(block)
                        elif block["type"] == "image":
                            flat.append(_paragraph([_run(block.get("alt", ""))]))
                        else:
                            for nested_row in block["rows"]:
                                for nested_cell in nested_row:
                                    flat.extend(nested_cell["blocks"])
                    blocks = flat
                item = {"blocks": blocks or [_paragraph()], "colspan": span, "rowspan": 1}
                row.append(item)
                if merge is not None and merge.val == "restart":
                    next_active[column] = item
                column += span
            rows.append(row)
            active = next_active
        widths = [round(col.width.pt, 3) if col.width else 0 for col in table.columns]
        if element.xpath("./w:tr/w:trPr/w:gridBefore | ./w:tr/w:trPr/w:gridAfter"):
            _warn(self.warnings, "DOCX tables with omitted leading or trailing grid cells may lose alignment.")
        if element.xpath("./w:tblPr/w:tblpPr"):
            _warn(self.warnings, "Floating DOCX tables are converted to inline tables.")
        return {"type": "table", "rows": rows, "col_widths": widths}

    def body(self, element, parent):
        from docx.oxml.ns import qn
        blocks = []
        for child in element:
            kind = _tag(child)
            if kind == "p":
                blocks.extend(self.paragraph(child, parent))
            elif kind == "tbl":
                blocks.append(self.table(child, parent))
            elif kind == "sdt":
                _warn(self.warnings, "DOCX content controls are flattened to their visible content.")
                content = child.find(qn("w:sdtContent"))
                if content is not None:
                    blocks.extend(self.body(content, parent))
            elif kind == "sectPr":
                pass
            elif kind in ("tcPr",):
                pass
            else:
                _warn(self.warnings, f"Unsupported DOCX block feature was omitted: {kind}")
        return blocks


def _parse_docx(source, assets, warnings):
    from docx import Document
    # python-docx reads ZIP members directly, never extracts their filenames.
    with zipfile.ZipFile(source) as archive:
        if sum(entry.file_size for entry in archive.infolist()) > MAX_DOCX_BYTES:
            raise ValueError("DOCX expanded data exceeds 512 MiB.")
        names = set(archive.namelist())
        for feature, label in (("footnotes", "footnotes"), ("endnotes", "endnotes"),
                               ("comments", "comments"), ("vbaProject", "macros")):
            if any(feature in name for name in names):
                _warn(warnings, f"DOCX {label} are not included in the converted body.")
        if any(name.startswith(("word/header", "word/footer")) for name in names):
            _warn(warnings, "DOCX headers and footers are not included in the converted body.")
    document = Document(source)
    if len(document.sections) > 1:
        _warn(warnings, "Multiple DOCX sections are flattened; section-specific layout is not retained.")
    if document.element.xpath(".//w:cols[@w:num != '1']"):
        _warn(warnings, "DOCX multi-column layout is not retained.")
    if document.element.xpath(".//w:txbxContent"):
        _warn(warnings, "DOCX text boxes are not included in the converted body.")
    return _DocxReader(document, assets, warnings).body(document.element.body, document)


def parse_input(path, asset_dir):
    """Read .md/.markdown/.docx into blocks and explicit fidelity warnings."""
    source = Path(path).expanduser().resolve()
    if not source.is_file():
        raise FileNotFoundError(source)
    suffix = source.suffix.lower()
    if suffix not in (".md", ".markdown", ".docx"):
        raise ValueError(f"Unsupported input extension: {suffix}")
    assets = Path(asset_dir).expanduser().resolve()
    assets.mkdir(parents=True, exist_ok=True)
    warnings = []
    blocks = (_parse_docx(source, assets, warnings) if suffix == ".docx"
              else _parse_md(source, assets, warnings))
    return {"blocks": blocks, "warnings": warnings}
