#!/usr/bin/env python3
"""Draws the chart in docs/evidence.md. Run from the repository root: python3 docs/charts.py

The numbers are the ones reported there; edit them here when you re-measure.
"""
import os

OUT = os.path.dirname(os.path.abspath(__file__))
FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif"
THEMES = {
    "light": dict(ink="#18202c", muted="#5a6577", none="#aab2bf", how="#6e7f86", what="#c47a00"),
    "dark": dict(ink="#e6eaf1", muted="#9aa5b8", none="#5b6676", how="#8d9ca2", what="#e9b54f"),
}

# Commits by what skimit diff said: no change in behaviour, only how it does what it already did,
# a change to what it can do. Every project with the built-in rule and no policy.
# From experiments/2026-10/feed-0.2.3: python3 docs/charts.py after editing these.
REPLAY = [
    ("XXL-JOB", "195 commits, 2024 to 2026", [113, 71, 11]),
    ("Spring PetClinic", "84 commits, 2019 to 2026", [69, 13, 2]),
    ("PetClinic REST", "78 commits, 2018 to 2026", [48, 24, 6]),
    ("Apollo", "68 commits, 2024 to 2026", [31, 23, 14]),
    ("litemall", "50 commits, 2019 to 2025", [31, 9, 10]),
    ("newbee-mall", "37 commits, 2019 to 2025", [26, 10, 1]),
    ("Spring Framework PetClinic", "26 commits, 2015 to 2026", [24, 2, 0]),
    ("JPetStore", "22 commits, 2020 to 2026", [21, 0, 1]),
    ("vhr", "20 commits, 2019 to 2020", [12, 3, 5]),
    ("All nine projects", "580 commits", [375, 155, 50]),
]


def text(x, y, s, fill, size=13, anchor="start", weight="400"):
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-family="{FONT}" font-size="{size}" font-weight="{weight}" '
            f'fill="{fill}" text-anchor="{anchor}">{s}</text>')


def bar(x, y, w, h, parts, t, fmt):
    """One stacked bar. parts: (colour key, value). Labels sit under each segment wide enough for one."""
    total = sum(v for _, v in parts)
    out, cx, gap = [], x, 2
    for key, v in parts:
        if v == 0:
            continue
        sw = w * v / total
        out.append(f'<rect x="{cx:.1f}" y="{y}" width="{max(sw - gap, 1):.1f}" height="{h}" rx="3" fill="{t[key]}"/>')
        if sw >= 30:
            out.append(text(cx + (sw - gap) / 2, y + h + 16, fmt(v, total), t["muted"], 12, "middle"))
        cx += sw
    return "".join(out)


def legend(x, y, items, t):
    out, cx = [], x
    for key, label in items:
        out.append(f'<rect x="{cx}" y="{y - 10}" width="11" height="11" rx="2" fill="{t[key]}"/>')
        out.append(text(cx + 17, y, label, t["ink"], 13))
        cx += 17 + 7.2 * len(label) + 22
    return "".join(out)


def wrap(w, h, body, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img" '
            f'aria-label="{title}"><title>{title}</title>{body}</svg>\n')


def replay(t):
    w, left, bw = 780, 250, 520
    keys = [("none", "No change to what a way in does"), ("how", "How it does it"), ("what", "What it can do")]
    body = legend(left, 18, keys, t)
    y = 44
    for name, sub, counts in REPLAY:
        body += text(0, y + 12, name, t["ink"], 14, weight="600") + text(0, y + 30, sub, t["muted"], 12)
        body += bar(left, y, bw, 22, list(zip([k for k, _ in keys], counts)), t, lambda v, total: f"{v}")
        y += 64
    return wrap(w, y - 8, body, "What 580 real commits to nine open-source projects did to behaviour")


for name, t in THEMES.items():
    open(os.path.join(OUT, f"replay-{name}.svg"), "w").write(replay(t))
print("charts written to", OUT)
