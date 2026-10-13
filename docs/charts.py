#!/usr/bin/env python3
"""Draws the README charts. Run from the repository root: python3 docs/charts.py

The numbers are the ones reported in the README; edit them here when you re-measure.
"""
import os

OUT = os.path.dirname(os.path.abspath(__file__))
FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif"
THEMES = {
    "light": dict(ink="#18202c", muted="#5a6577", none="#aab2bf",
                  path="#d03b3b", infl="#e09400", unpr="#6250d6", iso="#12a06e"),
    "dark": dict(ink="#e6eaf1", muted="#9aa5b8", none="#5b6676",
                 path="#d03b3b", infl="#c98500", unpr="#9085e9", iso="#199e70"),
}

VERDICTS = [("path", "On path"), ("infl", "Influences"), ("unpr", "Unproven"), ("iso", "Isolated")]
CODEBASES = [
    ("Spring PetClinic", "116 methods · sink: repository saves", [5.2, 57.8, 12.9, 24.1]),
    ("Spring Framework PetClinic", "179 methods · sink: repository saves", [7.8, 69.3, 6.1, 16.8]),
    ("JPetStore", "248 methods · sink: placing an order", [1.6, 63.3, 1.2, 33.9]),
    ("XXL-JOB", "928 methods · sink: run or kill a job", [2.2, 66.3, 2.4, 29.2]),
    ("Broadleaf Commerce", "7,785 methods · sink: payment gateway", [1.6, 62.3, 15.3, 20.8]),
    ("Kill Bill", "10,002 methods · sink: payment plugins", [2.7, 68.4, 18.6, 10.3]),
]

# Commits by what they needed: no code change, isolated only, should review, must review.
# From experiments/2026-10/results.json: every project under the same rule (data writes are the sinks).
REPLAY = [
    ("XXL-JOB", "195 commits, 2024 to 2026", [32, 11, 55, 97]),
    ("Spring PetClinic", "84 commits, 2019 to 2026", [31, 14, 20, 19]),
    ("PetClinic REST", "78 commits, 2018 to 2026", [12, 12, 23, 31]),
    ("Apollo", "68 commits, 2024 to 2026", [1, 14, 25, 28]),
    ("litemall", "45 commits, 2020 to 2025", [4, 18, 4, 19]),
    ("newbee-mall", "37 commits, 2019 to 2025", [4, 8, 9, 16]),
    ("Spring Framework PetClinic", "26 commits, 2015 to 2026", [5, 4, 13, 4]),
    ("JPetStore", "22 commits, 2020 to 2026", [13, 0, 6, 3]),
    ("vhr", "20 commits, 2019 to 2020", [2, 2, 8, 8]),
    ("All nine projects", "575 commits", [104, 83, 163, 225]),
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


def verdicts(t):
    w, left, bw = 780, 250, 520
    body = legend(left, 18, VERDICTS, t)
    y = 44
    for name, sub, shares in CODEBASES:
        body += text(0, y + 12, name, t["ink"], 14, weight="600") + text(0, y + 30, sub, t["muted"], 12)
        body += bar(left, y, bw, 22, list(zip([k for k, _ in VERDICTS], shares)), t, lambda v, total: f"{v:g}%")
        y += 64
    return wrap(w, y - 8, body, "Share of methods by verdict in six open-source codebases")


def replay(t):
    w, left, bw = 780, 250, 520
    keys = [("none", "No code change"), ("iso", "Isolated only"), ("infl", "Should review"), ("path", "Must review")]
    body = legend(left, 18, keys, t)
    y = 44
    for name, sub, counts in REPLAY:
        body += text(0, y + 12, name, t["ink"], 14, weight="600") + text(0, y + 30, sub, t["muted"], 12)
        body += bar(left, y, bw, 22, list(zip([k for k, _ in keys], counts)), t, lambda v, total: f"{v}")
        y += 64
    return wrap(w, y - 8, body, "What 575 real commits to nine open-source projects needed, under one fixed rule")


for name, t in THEMES.items():
    open(os.path.join(OUT, f"verdicts-{name}.svg"), "w").write(verdicts(t))
    open(os.path.join(OUT, f"replay-{name}.svg"), "w").write(replay(t))
print("charts written to", OUT)
