#!/usr/bin/env python3
"""Build the WCHNT website from markdown into flat HTML pages.

Reads ``website/content/*.md``, renders each one to ``website/_site/<name>.html``
using a shared HTML template, copies ``website/assets/`` into ``_site/``,
bundles the live editor from ``wchnt-lang/live/public/`` into ``_site/play/``,
and renders the composed seed programs as HTML (names already in
``content/`` are skipped).

Usage:
    ./website/build.sh
    python3 website/build.py

``build.sh`` runs ``prepare-live`` (which runs ``seed-from-live.sh``),
then copies ``live/public/`` into ``_site/play/``.

Prerequisites:
    - Python 3 with ``markdown-it-py`` installed
    - ``lein live`` has been run once in ``wchnt-lang/`` so
      ``wchnt-lang/live/public/js/main.js`` exists (for the Play page)
"""

import html
import re
import shutil
import sys
from pathlib import Path

from markdown_it import MarkdownIt

SITE_DIR = Path(__file__).resolve().parent
CONTENT_DIR = SITE_DIR / "content"
ASSETS_DIR = SITE_DIR / "assets"
OUT_DIR = SITE_DIR / "_site"
LIVE_DIR = SITE_DIR.parent / "wchnt-lang" / "live" / "public"
# Seed pages rendered as HTML at the site root, except names already in
# content/ and welcome (the Play wiki front page, not a site page).
SEED_HTML_SKIP = {"welcome"}

# Fixed top navigation. Each entry is (label, href) where href is relative to
# the site root (all markdown pages are written flat into _site/).
NAV = [
    ("Home", "index.html"),
    ("Tutorial", "tutorial.html"),
    ("Guide", "guide.html"),
    ("Reference", "reference.html"),
    ("Play", "play/"),
]

TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<link rel="stylesheet" href="style.css">
</head>
<body>
<header class="site-header">
  <a class="brand" href="index.html">
    <img class="brand-logo" src="witch.svg" alt="WCHNT mascot">
    <span class="brand-name">WCHNT</span>
    <span class="brand-tagline">We CAN Have Nice Things</span>
  </a>
  <nav class="site-nav">
{nav}
  </nav>
</header>
<main class="page">
{body}
</main>
<footer class="site-footer">
  <p>WCHNT is an experimental object-oriented language. This site is generated
  from markdown by <code>website/build.py</code>.</p>
</footer>
</body>
</html>
"""


# --- WCHNT syntax highlighting -----------------------------------------------
# Inspired by src/wchnt_lang/highlight.cljc (the live editor's tokenizer) and
# the colours in live/public/css/live.css. The compiler grammar lives in
# Instaparse (Clojure), so this is a small regex tokenizer that approximates
# the same token kinds rather than a port of the grammar.

_WCHNT_TOKEN = re.compile(
    r"(?P<comment>//[^\n]*|/\*.*?\*/)"
    r"|(?P<string>\"(?:\\.|[^\"\\])*\")"
    r"|(?P<number>-?\d+\.\d+|-?\d+)"
    r"|(?P<rel_mailbox>(?<![\w])>[A-Z][A-Za-z0-9_]*)"
    r"|(?P<rel_context>(?<![\w>\[]):[A-Z][A-Za-z0-9_]*)"
    r"|(?P<rel_delegate>\+[A-Z][A-Za-z0-9_]*)"
    r"|(?P<rel_reactive>\$[A-Z][A-Za-z0-9_]*)"
    r"|(?P<rel_external>@[A-Z][A-Za-z0-9_]*)"
    r"|(?P<sigil>\$|@|(?<![\w])>(?=[A-Z])|(?<![\w>\[]):(?=[A-Z]))"
    r"|(?P<keyword>if|else|or|and|not|true|false)\b"
    r"|(?P<class>(?<=\[:)[A-Z][A-Za-z0-9_]*|[A-Z][A-Za-z0-9_]*(?=\s*=|::))"
    r"|(?P<type>[A-Z][A-Za-z0-9_]*)"
    r"|(?P<method>(?<=::)[a-z_][A-Za-z0-9_]*)"
    r"|(?P<name>(?<=/)[a-z_][A-Za-z0-9_]*)"
    r"|(?P<path>[a-z_][A-Za-z0-9_]*(?:\.[a-z_][A-Za-z0-9_]*)+)"
    r"|(?P<ident>[a-z_][A-Za-z0-9_]*)"
    r"|(?P<punct>[^\sA-Za-z0-9_])",
    re.DOTALL,
)

_TOKEN_CLASS = {
    "comment": "tok-comment",
    "string": "tok-string",
    "number": "tok-number",
    "sigil": "tok-sigil",
    "rel_mailbox": "tok-rel-mailbox",
    "rel_context": "tok-rel-context",
    "rel_delegate": "tok-rel-delegate",
    "rel_reactive": "tok-rel-reactive",
    "rel_external": "tok-rel-external",
    "keyword": "tok-keyword",
    "class": "tok-class",
    "type": "tok-type",
    "method": "tok-method",
    "name": "tok-name",
    "path": "tok-path",
    # ident and punct take the default colour
}

_HOST_JS = re.compile(
    r"\bfunction\s+\w+\s*\(|\bvar\s+\w+|%(?:canvas|openfl|terminal|cli)"
)


def highlight_wchnt(code: str) -> str:
    """Return WCHNT source as HTML with span-based syntax colouring."""
    out = []
    pos = 0
    for match in _WCHNT_TOKEN.finditer(code):
        if match.start() > pos:
            out.append(html.escape(code[pos:match.start()]))
        kind = match.lastgroup
        text = match.group()
        css_class = _TOKEN_CLASS.get(kind)
        if css_class:
            out.append(f'<span class="{css_class}">{html.escape(text)}</span>')
        else:
            out.append(html.escape(text))
        pos = match.end()
    out.append(html.escape(code[pos:]))
    return "".join(out)


def highlight(code: str, lang: str, attrs) -> str | None:
    """Colour ``wchnt`` fences, and unlabelled fences that are not host JS."""
    lang = (lang or "").lower()
    if lang in ("js", "javascript", "html", "css"):
        return None
    if lang == "wchnt" or (lang == "" and not _HOST_JS.search(code)):
        return f'<pre><code class="language-wchnt">{highlight_wchnt(code)}</code></pre>'
    return None


def markdown_renderer() -> MarkdownIt:
    md = MarkdownIt(
        "commonmark",
        {"html": True, "linkify": True, "typographer": True, "highlight": highlight},
    )
    md.enable("table")
    md.enable("strikethrough")
    return md


def title_from_markdown(text: str, fallback: str) -> str:
    """Return the first level-1 heading, or fallback if there isn't one."""
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("# "):
            return line[2:].strip()
        # Stop at the first non-blank, non-heading line.
        if line:
            break
    return fallback


def site_title(title: str) -> str:
    """Page title for <title>. Avoid 'WCHNT … WCHNT' on the landing page."""
    if "wchnt" in title.lower():
        return title
    return f"{title} · WCHNT"


def render_nav() -> str:
    links = "\n".join(f'    <a href="{href}">{label}</a>' for label, href in NAV)
    return links


def build_page(md: MarkdownIt, source: Path, out: Path) -> None:
    text = source.read_text(encoding="utf-8")
    title = title_from_markdown(text, source.stem.replace("-", " ").title())
    body = md.render(text)
    html = TEMPLATE.format(title=site_title(title), nav=render_nav(), body=body)
    out.write_text(html, encoding="utf-8")
    try:
        shown = source.relative_to(SITE_DIR.parent)
    except ValueError:
        shown = source.name
    print(f"  rendered {shown} -> {out.relative_to(OUT_DIR)}")


def copy_tree(src: Path, dst: Path) -> None:
    if not src.exists():
        return
    if dst.exists():
        shutil.rmtree(dst, ignore_errors=True)
    if dst.exists():
        for item in src.iterdir():
            target = dst / item.name
            if item.is_dir():
                copy_tree(item, target)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(item, target)
        return
    shutil.copytree(src, dst)


def render_seed_html(md: MarkdownIt) -> None:
    """Render the composed play/seed programs as HTML unless content/ owns that name."""
    composed = OUT_DIR / "play" / "seed"
    if not composed.exists():
        return
    content_stems = {p.stem for p in CONTENT_DIR.glob("*.md")}
    skip = SEED_HTML_SKIP | content_stems
    for src in sorted(composed.glob("*.wcn")):
        if src.stem in skip:
            continue
        build_page(md, src, OUT_DIR / f"{src.stem}.html")


def main() -> int:
    md = markdown_renderer()

    if OUT_DIR.exists():
        shutil.rmtree(OUT_DIR, ignore_errors=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    sources = sorted(CONTENT_DIR.glob("*.md"))
    if not sources:
        print(f"error: no markdown files in {CONTENT_DIR}", file=sys.stderr)
        return 1

    print(f"Building site into {OUT_DIR}")
    for source in sources:
        build_page(md, source, OUT_DIR / f"{source.stem}.html")

    print("  copying assets")
    for asset in sorted(ASSETS_DIR.iterdir()):
        if asset.is_file():
            shutil.copy2(asset, OUT_DIR / asset.name)

    print("  bundling live editor")
    if LIVE_DIR.exists():
        copy_tree(LIVE_DIR, OUT_DIR / "play")
    else:
        print(
            f"  warning: {LIVE_DIR} not found — run `lein live` in wchnt-lang/ "
            "first, then rebuild the site.",
            file=sys.stderr,
        )

    print("  rendering seed pages as HTML")
    render_seed_html(md)

    print("Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
