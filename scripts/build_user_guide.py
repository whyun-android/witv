#!/usr/bin/env python3
"""把 docs/user-guide.md 编译成一个可以直接打开、也可以放上网站的 HTML 手册。

用法（在仓库根目录）：

    pip install markdown
    python scripts/build_user_guide.py                    # → build/user-guide/index.html + images/
    python scripts/build_user_guide.py --single-file      # 图片内嵌为 data URI，只出一个 html
    python scripts/build_user_guide.py --out website/guide  # 直接生成到宣传站目录下

除了转换，脚本也顺带当手册的检查器用：引用的图片不存在、页内锚点指不到任何标题时
会报出来；加 --strict 时这些问题让脚本以非零码退出，方便放进 CI。
"""

from __future__ import annotations

import argparse
import base64
import datetime as _dt
import html
import mimetypes
import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

try:
    import markdown
    from markdown.extensions.toc import slugify_unicode
except ImportError:  # pragma: no cover - 只在缺依赖时走到
    sys.exit("缺少依赖：请先运行 `pip install markdown`")

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SRC = REPO_ROOT / "docs" / "user-guide.md"
DEFAULT_OUT = REPO_ROOT / "build" / "user-guide"
# 手册里指向仓库其他文件（如 docs/multicast-udp-rtp.md）的相对链接，在 HTML 里改指 GitHub
GITHUB_BLOB = "https://github.com/whyun-android/witv/blob/main/"

# 单独成段的图片：![alt](src) 独占一段时，markdown 输出 <p><img ...></p>
_LONE_IMG_RE = re.compile(r"<p>\s*(<img\b[^>]*>)\s*</p>")
_IMG_RE = re.compile(r"<img\b[^>]*>")
_ATTR_RE = re.compile(r'(\w[\w-]*)="([^"]*)"')
_HREF_RE = re.compile(r'<a\b([^>]*?)href="([^"]*)"')
_ID_RE = re.compile(r'\bid="([^"]+)"')


def _attrs(tag: str) -> dict[str, str]:
    return {k: html.unescape(v) for k, v in _ATTR_RE.findall(tag)}


def _is_external(url: str) -> bool:
    return bool(re.match(r"^[a-z][a-z0-9+.-]*:", url, re.I)) or url.startswith("//")


class Builder:
    def __init__(self, src: Path, out: Path, single_file: bool):
        self.src = src
        self.out = out
        self.single_file = single_file
        self.problems: list[str] = []
        self.images: list[Path] = []

    # ---------- markdown → html 片段 ----------

    def convert(self, text: str) -> tuple[str, str, str]:
        md = markdown.Markdown(
            extensions=["extra", "sane_lists", "toc"],
            extension_configs={
                # slugify_unicode 保留中文，和 GitHub 上的锚点（#作为桌面启动）一致
                "toc": {"slugify": slugify_unicode, "toc_depth": "2-3", "permalink": False},
            },
            output_format="html",
        )
        body = md.convert(text)
        title = self._title(md, text)
        return title, body, md.toc

    @staticmethod
    def _title(md: markdown.Markdown, text: str) -> str:
        for tok in getattr(md, "toc_tokens", []):
            if tok.get("level") == 1:
                return html.unescape(re.sub(r"<[^>]+>", "", tok["name"]))
        m = re.search(r"^#\s+(.+)$", text, re.M)
        return m.group(1).strip() if m else "使用手册"

    # ---------- 后处理 ----------

    def _rewrite_img(self, tag: str) -> str:
        a = _attrs(tag)
        src = a.get("src", "")
        alt = a.get("alt", "")
        if _is_external(src):
            new_src = src
        else:
            path = (self.src.parent / unquote(src)).resolve()
            if not path.is_file():
                self.problems.append(f"图片不存在：{src}")
                new_src = src
            elif self.single_file:
                mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
                data = base64.b64encode(path.read_bytes()).decode("ascii")
                new_src = f"data:{mime};base64,{data}"
            else:
                self.images.append(path)
                new_src = self._out_rel(path)
        return (f'<img src="{html.escape(new_src, quote=True)}" alt="{html.escape(alt, quote=True)}"'
                f' loading="lazy" decoding="async">')

    def _out_rel(self, path: Path) -> str:
        """图片在输出目录里的相对路径：保持相对 md 文件的目录结构（images/xxx.png）。"""
        try:
            return path.relative_to(self.src.parent.resolve()).as_posix()
        except ValueError:
            return "assets/" + path.name

    def postprocess(self, body: str) -> str:
        def figure(m: re.Match) -> str:
            tag = m.group(1)
            alt = _attrs(tag).get("alt", "")
            img = self._rewrite_img(tag)
            # 单图单段 → <figure>，alt 当图注；点图看原图（单文件模式下就不包链接了，data URI 太长）
            if not self.single_file and 'src="data:' not in img:
                src = _attrs(img)["src"]
                img = f'<a class="zoom" href="{html.escape(src, quote=True)}">{img}</a>'
            cap = f"<figcaption>{html.escape(alt)}</figcaption>" if alt else ""
            return f"<figure>{img}{cap}</figure>"

        body = _LONE_IMG_RE.sub(figure, body)
        # 和文字混排的内联图片只改路径；上一步处理过的带 loading 属性，跳过
        body = _IMG_RE.sub(lambda m: m.group(0) if "loading=" in m.group(0) else self._rewrite_img(m.group(0)), body)

        ids = set(_ID_RE.findall(body))

        def link(m: re.Match) -> str:
            pre, href = m.group(1), html.unescape(m.group(2))
            if 'class="zoom"' in pre:  # 上面生成的“点图看原图”，指向输出目录里的图片，不改
                return m.group(0)
            if href.startswith("#"):
                if unquote(href[1:]) not in ids:
                    self.problems.append(f"页内锚点指不到标题：{href}")
                return m.group(0)
            if _is_external(href):
                # 外链新窗口打开
                return f'<a{pre}target="_blank" rel="noopener" href="{html.escape(href, quote=True)}"'
            path_part, _, frag = href.partition("#")
            target = (self.src.parent / unquote(path_part)).resolve()
            if not target.exists():
                self.problems.append(f"相对链接指向的文件不存在：{href}")
                return m.group(0)
            try:
                rel = target.relative_to(REPO_ROOT).as_posix()
            except ValueError:
                return m.group(0)
            url = GITHUB_BLOB + rel + (f"#{frag}" if frag else "")
            return f'<a{pre}target="_blank" rel="noopener" href="{html.escape(url, quote=True)}"'

        return _HREF_RE.sub(link, body)

    # ---------- 输出 ----------

    def write(self, title: str, body: str, toc: str) -> Path:
        self.out.mkdir(parents=True, exist_ok=True)
        for img in dict.fromkeys(self.images):
            dest = self.out / self._out_rel(img)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if not dest.exists() or dest.stat().st_mtime < img.stat().st_mtime or dest.stat().st_size != img.stat().st_size:
                shutil.copy2(img, dest)
        page = TEMPLATE.format(
            title=html.escape(title),
            toc=toc,
            body=body,
            css=CSS,
            source=html.escape(self.src.relative_to(REPO_ROOT).as_posix() if self.src.is_relative_to(REPO_ROOT) else self.src.name),
            stamp=html.escape(_stamp()),
        )
        index = self.out / "index.html"
        index.write_text(page, encoding="utf-8", newline="\n")
        return index

    def run(self) -> Path:
        title, body, toc = self.convert(self.src.read_text(encoding="utf-8"))
        body = self.postprocess(body)
        return self.write(title, body, toc)


def _stamp() -> str:
    date = _dt.date.today().isoformat()
    try:
        rev = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=REPO_ROOT,
                             capture_output=True, text=True, timeout=5).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        rev = ""
    return f"{date} · {rev}" if rev else date


CSS = r"""
:root {
  --navy: #081434; --navy-deep: #030a1e; --navy-mid: #0b1c4a; --line: #1a2f6b;
  --ink: #e8eefc; --mist: #afc0e6; --accent: #f2b705; --link: #8fb4ff;
  --code-bg: rgba(3, 10, 30, .7);
  --font: "Noto Sans SC", "PingFang SC", "Microsoft YaHei", "Hiragino Sans GB", sans-serif;
  --mono: "JetBrains Mono", "Cascadia Mono", Consolas, "Courier New", monospace;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; scroll-padding-top: 1.5rem; }
body {
  margin: 0; background: var(--navy-deep); color: var(--ink);
  font: 16px/1.75 var(--font); -webkit-font-smoothing: antialiased;
}
.layout { display: grid; grid-template-columns: 17rem minmax(0, 1fr); max-width: 76rem; margin: 0 auto; }
nav.toc {
  position: sticky; top: 0; height: 100vh; overflow-y: auto; padding: 2.5rem 1.25rem 2rem 1.5rem;
  border-right: 1px solid var(--line); font-size: .9rem;
}
nav.toc .brand { display: block; font-weight: 700; font-size: 1.05rem; color: var(--ink); text-decoration: none; margin-bottom: 1.25rem; }
nav.toc ul { list-style: none; margin: 0; padding: 0; }
nav.toc ul ul { padding-left: .9rem; margin: .15rem 0 .5rem; font-size: .85rem; }
nav.toc a { display: block; padding: .2rem .5rem; border-radius: 4px; color: var(--mist); text-decoration: none; }
nav.toc a:hover { color: var(--ink); background: var(--navy-mid); }
nav.toc a.active { color: var(--navy); background: var(--ink); }
main { padding: 2.5rem clamp(1.25rem, 4vw, 3.5rem) 4rem; max-width: 52rem; }
h1 { font-size: 2rem; line-height: 1.3; margin: 0 0 1rem; }
h2 { font-size: 1.5rem; margin: 3rem 0 1rem; padding-top: .5rem; border-top: 1px solid var(--line); }
h3 { font-size: 1.2rem; margin: 2.25rem 0 .75rem; }
h4 { font-size: 1.05rem; margin: 1.75rem 0 .5rem; color: var(--mist); }
hr { display: none; } /* 章节之间已经有 h2 的分隔线 */
a { color: var(--link); text-underline-offset: 3px; }
strong { color: #fff; }
code { font-family: var(--mono); font-size: .88em; background: var(--code-bg); padding: .1em .35em; border-radius: 4px; }
pre { background: var(--code-bg); border: 1px solid var(--line); border-radius: 6px; padding: .9rem 1rem; overflow-x: auto; line-height: 1.5; }
pre code { background: none; padding: 0; }
blockquote { margin: 1.25rem 0; padding: .6rem 1rem; border-left: 3px solid var(--accent); background: var(--navy); color: var(--mist); border-radius: 0 6px 6px 0; }
blockquote p { margin: .3rem 0; }
table { border-collapse: collapse; width: 100%; margin: 1.25rem 0; font-size: .95rem; display: block; overflow-x: auto; }
th, td { border: 1px solid var(--line); padding: .5rem .75rem; text-align: left; vertical-align: top; }
th { background: var(--navy-mid); white-space: nowrap; }
tr:nth-child(even) td { background: rgba(11, 28, 74, .35); }
figure { margin: 1.5rem 0; }
figure img { display: block; max-width: 100%; height: auto; border: 1px solid var(--line); border-radius: 6px; background: #000; }
figure .zoom { display: block; width: fit-content; max-width: 100%; cursor: zoom-in; }
figcaption { margin-top: .5rem; font-size: .85rem; color: var(--mist); }
li > figure { margin: 1rem 0; }
img { max-width: 100%; }
footer { margin-top: 4rem; padding-top: 1rem; border-top: 1px solid var(--line); font-size: .8rem; color: var(--mist); }
@media (max-width: 860px) {
  .layout { display: block; }
  nav.toc { position: static; height: auto; border-right: 0; border-bottom: 1px solid var(--line); padding: 1.25rem; }
  nav.toc > div > ul > li > ul { display: none; }
  main { padding-top: 1.5rem; }
}
@media print {
  body { background: #fff; color: #000; }
  nav.toc { display: none; }
  .layout { display: block; }
  strong { color: #000; }
  blockquote, pre, code, th, tr:nth-child(even) td { background: #f4f4f4; color: #000; }
}
"""

# 目录高亮：滚动时把当前所在章节在侧栏里标出来
TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<style>{css}</style>
</head>
<body>
<div class="layout">
<nav class="toc" aria-label="目录">
<a class="brand" href="#">{title}</a>
{toc}
</nav>
<main>
{body}
<footer>由 <code>{source}</code> 生成 · {stamp}</footer>
</main>
</div>
<script>
(function () {{
  var links = Array.prototype.slice.call(document.querySelectorAll('nav.toc a[href^="#"]'));
  var map = {{}};
  links.forEach(function (a) {{ map[decodeURIComponent(a.getAttribute('href').slice(1))] = a; }});
  var heads = Array.prototype.slice.call(document.querySelectorAll('main h2[id], main h3[id]'));
  if (!('IntersectionObserver' in window) || !heads.length) return;
  var current = null;
  var io = new IntersectionObserver(function (entries) {{
    entries.forEach(function (e) {{
      if (!e.isIntersecting) return;
      var a = map[e.target.id];
      if (!a || a === current) return;
      if (current) current.classList.remove('active');
      a.classList.add('active');
      current = a;
    }});
  }}, {{ rootMargin: '0px 0px -70% 0px' }});
  heads.forEach(function (h) {{ io.observe(h); }});
}})();
</script>
</body>
</html>
"""


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="把使用手册 Markdown 编译成 HTML")
    p.add_argument("--src", type=Path, default=DEFAULT_SRC, help="源 Markdown（默认 docs/user-guide.md）")
    p.add_argument("--out", type=Path, default=DEFAULT_OUT, help="输出目录（默认 build/user-guide）")
    p.add_argument("--single-file", action="store_true", help="图片内嵌进 HTML，只生成一个文件")
    p.add_argument("--strict", action="store_true", help="有缺图、坏锚点时以非零码退出")
    args = p.parse_args(argv)

    src = args.src.resolve()
    if not src.is_file():
        print(f"找不到源文件：{src}", file=sys.stderr)
        return 2
    b = Builder(src, args.out.resolve(), args.single_file)
    index = b.run()

    for msg in dict.fromkeys(b.problems):
        print(f"警告：{msg}", file=sys.stderr)
    size_kb = index.stat().st_size / 1024
    extra = "" if args.single_file else f"，复制图片 {len(set(b.images))} 张"
    print(f"已生成 {index}（{size_kb:.0f} KB{extra}）")
    return 1 if (args.strict and b.problems) else 0


if __name__ == "__main__":
    sys.exit(main())
