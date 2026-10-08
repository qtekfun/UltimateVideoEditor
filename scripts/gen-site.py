#!/usr/bin/env python3
"""Builds the static documentation site (GitHub Pages) and the page the PDF guide is printed from.

    scripts/gen-site.py --out _site

Needs only Python 3 and pandoc (set PANDOC to point at a binary). No network, no fonts or scripts from a CDN, no
analytics: every page is one self-contained HTML file with inline CSS. The icons page draws each toolbar symbol as an
inline SVG from the same path data the app uses (EditorIcons.kt, SelectionBar.kt) and takes names and help texts from
ToolbarGuide.kt, so the website cannot show a different symbol than the app.
"""
import argparse
import html
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = os.path.join(ROOT, "app/src/main/kotlin/com/qtekfun/ultimatevideoeditor/ui/editor")
REPO = "https://github.com/qtekfun/UltimateVideoEditor"

# source markdown (relative to docs/) -> (output name, title)
DOCS = [
    ("USER_GUIDE.md", "user-guide.html", "User guide"),
    ("PRIVACY.md", "privacy.html", "Privacy"),
    ("ARCHITECTURE.md", "architecture.html", "Architecture"),
    ("RELEASE.md", "release.html", "Releasing"),
    ("HUAWEI_TABLET.md", "huawei-tablet.html", "Huawei tablet"),
]

CSS = """
:root{--bg:#fff;--fg:#1b1f24;--muted:#59636e;--line:#d0d7de;--card:#f6f8fa;--link:#0b5cad}
@media (prefers-color-scheme:dark){:root{--bg:#0f1115;--fg:#e6e8eb;--muted:#9aa4af;--line:#2d333b;--card:#171b21;--link:#7db4ff}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
header{border-bottom:1px solid var(--line);padding:10px 16px}
nav{max-width:860px;margin:0 auto;display:flex;flex-wrap:wrap;gap:6px 18px;align-items:center}
nav strong{margin-right:auto}
a{color:var(--link)}
main{max-width:860px;margin:0 auto;padding:16px}
table{border-collapse:collapse;display:block;overflow-x:auto;max-width:100%}
th,td{border:1px solid var(--line);padding:6px 10px;vertical-align:top;text-align:left}
th{background:var(--card)}
pre,code{background:var(--card);border-radius:4px}
code{padding:1px 4px;font-size:.92em}
pre{padding:10px;overflow-x:auto}
pre code{padding:0;background:none}
img{max-width:100%}
footer{max-width:860px;margin:24px auto;padding:0 16px;color:var(--muted);font-size:.9em}
.icons{list-style:none;padding:0;display:grid;gap:12px}
.icons li{display:flex;gap:14px;padding:10px;border:1px solid var(--line);border-radius:10px;background:var(--card)}
.icons svg{flex:none;width:36px;height:36px;fill:currentColor;background:var(--bg);border-radius:8px;padding:6px}
.icons h3{margin:0 0 2px;font-size:1.05em}
.icons p{margin:2px 0}
.when{color:var(--muted);font-size:.9em}
#q{width:100%;padding:10px;font:inherit;border:1px solid var(--line);border-radius:8px;background:var(--bg);color:var(--fg)}
.hidden{display:none}
@media print{header,footer,#q,.noprint{display:none}body{background:#fff;color:#000}main{max-width:none}a{color:#000}
.icons li{break-inside:avoid;background:#fff}}
"""


def page(title, body, nav_extra=""):
    links = "".join(f'<a href="{h}">{html.escape(t)}</a>' for _, h, t in DOCS[:2])
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{html.escape(title)} - ultimateVE</title>
<style>{CSS}</style></head>
<body><header><nav><strong><a href="index.html">ultimateVE</a></strong>{links}<a href="icons.html">Toolbar symbols</a><a href="{REPO}">Source</a>{nav_extra}</nav></header>
<main>
{body}
</main>
<footer>ultimateVE is free software (GPL-3.0). This site loads no scripts, fonts or trackers from anywhere.</footer>
</body></html>
"""


def pandoc(md_path):
    exe = os.environ.get("PANDOC", "pandoc")
    out = subprocess.run([exe, "-f", "gfm", "-t", "html5", "--wrap=none", md_path], check=True, capture_output=True, text=True)
    return out.stdout


def fix_links(fragment):
    names = {os.path.basename(src): dst for src, dst, _ in DOCS}

    def repl(match):
        target = match.group(1)
        base = os.path.basename(target.split("#")[0])
        frag = "#" + target.split("#", 1)[1] if "#" in target else ""
        if target.startswith(("http://", "https://", "#", "mailto:")):
            return match.group(0)
        if base in names:
            return f'href="{names[base]}{frag}"'
        if base.endswith((".md", ".sh", ".py", ".kt")):
            return f'href="{REPO}/blob/master/{target.replace("../", "")}"'
        return match.group(0)

    return re.sub(r'href="([^"]*)"', repl, fragment)


# --- icons page --------------------------------------------------------------------------------------------------

STRING = r'"((?:[^"\\]|\\.)*)"'


def unescape(text):
    return text.replace('\\"', '"').replace("\\\\", "\\")


def parse_icons():
    """name -> (path data, evenOdd) from every `icon(...)` definition in the Kotlin icon objects."""
    icons = {}
    for path in (os.path.join(KT, "EditorIcons.kt"), os.path.join(KT, "SelectionBar.kt")):
        text = re.sub(r"/\*.*?\*/", "", open(path, encoding="utf-8").read(), flags=re.S)
        chunks = re.split(r"\n\s*(?=val \w+ = icon\()", text)
        for chunk in chunks[1:]:
            chunk = re.split(r"\n\s*(?:private fun|\}\s*$)", chunk)[0]
            literals = re.findall(STRING, chunk)
            if len(literals) < 2:
                continue
            icons[literals[0]] = ("".join(literals[1:]), "evenOdd = true" in chunk)
    return icons


def parse_guide():
    text = open(os.path.join(KT, "guide/ToolbarGuide.kt"), encoding="utf-8").read()
    sections = {
        m.group(1): (unescape(m.group(2)), unescape(m.group(3)))
        for m in re.finditer(r"^\s*([A-Z_]+)\(" + STRING + r",\s*" + STRING + r"\),$", text, flags=re.M)
    }
    entry = re.compile(
        r'GuideEntry\("([^"]+)",\s*GuideSection\.(\w+),\s*(?:EditorIcons|SelectionIcons)\.(\w+),\s*' + STRING + r",\s*" + STRING + r",\s*" + STRING + r"\)"
    )
    entries = [(m.group(1), m.group(2), m.group(3), unescape(m.group(4)), unescape(m.group(5)), unescape(m.group(6))) for m in entry.finditer(text)]
    gesture = re.compile(r'GuideGesture\("([^"]+)",\s*' + STRING + r",\s*" + STRING + r"\)")
    gestures = [(m.group(1), unescape(m.group(2)), unescape(m.group(3))) for m in gesture.finditer(text)]
    # A new entry the regexes cannot read must fail the build rather than silently vanish from the site.
    if len(entries) != len(re.findall(r'GuideEntry\("', text)) or len(gestures) != len(re.findall(r'GuideGesture\("', text)):
        sys.exit("gen-site.py: ToolbarGuide.kt has entries it cannot parse (keep each on one line with plain string literals)")
    return sections, entries, gestures


def icons_page():
    icons = parse_icons()
    sections, entries, gestures = parse_guide()
    out = ["<h1>Toolbar symbols</h1>",
           "<p>Every symbol of the editor, drawn from the same shapes the app uses. In the app the same list is under "
           "<em>About, privacy and help</em> and the <strong>?</strong> button at the top of the editor, and works offline.</p>",
           '<input id="q" type="search" placeholder="Search symbols and gestures" aria-label="Search">']
    for key, (title, where) in sections.items():
        group = [e for e in entries if e[1] == key]
        if not group:
            continue
        out.append(f'<section><h2>{html.escape(title)}</h2><p class="when">{html.escape(where)}</p><ul class="icons">')
        for eid, _, icon, name, help_text, when in group:
            if icon not in icons:
                sys.exit(f"gen-site.py: no path data found for icon {icon}")
            data, even = icons[icon]
            rule = ' fill-rule="evenodd"' if even else ""
            svg = f'<svg viewBox="0 0 24 24" role="img" aria-label="{html.escape(name)}"><path d="{data}"{rule}/></svg>'
            when_html = f'<p class="when">Available when: {html.escape(when)}</p>' if when else ""
            out.append(f'<li id="{eid}">{svg}<div><h3>{html.escape(name)}</h3><p>{html.escape(help_text)}</p>{when_html}</div></li>')
        out.append("</ul></section>")
    out.append("<section><h2>Gestures</h2><p class=\"when\">Things you do with your fingers; they have no button.</p><ul class=\"icons\">")
    for gid, title, help_text in gestures:
        out.append(f'<li id="{gid}"><div><h3>{html.escape(title)}</h3><p>{html.escape(help_text)}</p></div></li>')
    out.append("</ul></section>")
    out.append(
        "<script>(function(){var q=document.getElementById('q');q.addEventListener('input',function(){var w=q.value.toLowerCase().split(/\\s+/).filter(Boolean);"
        "document.querySelectorAll('.icons li').forEach(function(li){var t=li.textContent.toLowerCase();"
        "li.classList.toggle('hidden',!w.every(function(x){return t.indexOf(x)>=0}))});"
        "document.querySelectorAll('section').forEach(function(s){s.classList.toggle('hidden',!s.querySelector('li:not(.hidden)'))})})})();</script>"
    )
    return "\n".join(out)


def index_page():
    return f"""<h1>ultimateVE</h1>
<p>A lightweight video editor for Android with a LumaFusion-style timeline. Free software, GPL-3.0. It works completely offline:
no accounts, no analytics, no network permission.</p>
<ul>
<li><a href="user-guide.html"><strong>User guide</strong></a>: screens, gestures, editing, colour, sound, export.</li>
<li><a href="icons.html"><strong>Toolbar symbols</strong></a>: what every icon of the editor does (searchable).</li>
<li><a href="privacy.html">Privacy</a> and <a href="architecture.html">architecture</a>.</li>
<li><a href="{REPO}/releases">Download the app</a> (the release page also has the guide as a PDF) and the <a href="{REPO}">source code</a>.</li>
</ul>
<p>Prefer not to use the web? The same toolbar guide is inside the app (About, then Toolbar guide, or the ? button in the editor).</p>
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=os.path.join(ROOT, "_site"))
    args = parser.parse_args()
    out = args.out
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    open(os.path.join(out, ".nojekyll"), "w").close()
    for src, dst, title in DOCS:
        body = fix_links(pandoc(os.path.join(ROOT, "docs", src)))
        open(os.path.join(out, dst), "w", encoding="utf-8").write(page(title, body))
    open(os.path.join(out, "icons.html"), "w", encoding="utf-8").write(page("Toolbar symbols", icons_page()))
    open(os.path.join(out, "index.html"), "w", encoding="utf-8").write(page("Home", index_page()))
    images = os.path.join(ROOT, "docs", "images")
    if os.path.isdir(images):
        shutil.copytree(images, os.path.join(out, "images"))
    print(f"site written to {out}")


if __name__ == "__main__":
    main()
