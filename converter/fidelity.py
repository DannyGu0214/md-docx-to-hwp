"""DOCX layout values expressed in points for the HWP writer."""
from docx.enum.text import WD_LINE_SPACING


def paragraph_layout(paragraph):
    formats = [paragraph.paragraph_format]
    style, seen = paragraph.style, set()
    while style is not None and style.style_id not in seen:
        seen.add(style.style_id)
        formats.append(style.paragraph_format)
        style = style.base_style
    result = {}
    for attr in ("space_before", "space_after", "left_indent", "right_indent", "first_line_indent"):
        value = next((getattr(f, attr) for f in formats if getattr(f, attr) is not None), None)
        if value is not None:
            result[attr + "_pt"] = round(value.pt, 3)
    spacing = next((f.line_spacing for f in formats if f.line_spacing is not None), None)
    rule = next((f.line_spacing_rule for f in formats if f.line_spacing_rule is not None), None)
    if spacing is not None:
        if rule in (WD_LINE_SPACING.EXACTLY, WD_LINE_SPACING.AT_LEAST):
            result["line_spacing"] = {"kind": "exact" if rule == WD_LINE_SPACING.EXACTLY else "minimum", "value": spacing.pt}
        else:
            result["line_spacing"] = {"kind": "percent", "value": float(spacing) * 100}
    for attr in ("keep_with_next", "keep_together", "page_break_before", "widow_control"):
        value = next((getattr(f, attr) for f in formats if getattr(f, attr) is not None), None)
        if value is not None:
            result[attr] = value
    return result


def page_layout(section):
    return {name + "_pt": round(getattr(section, name).pt, 3)
            for name in ("page_width", "page_height", "left_margin", "right_margin",
                         "top_margin", "bottom_margin", "header_distance", "footer_distance")
            if getattr(section, name) is not None}
