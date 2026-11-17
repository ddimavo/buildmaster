#!/usr/bin/env python3
"""Собирает docs/manual.html из docs/MANUAL.md (нужен пакет markdown: pip install markdown)."""
import html
import re
import sys
from pathlib import Path

import markdown
from markdown.extensions.toc import slugify_unicode

root = Path(__file__).resolve().parent.parent
src = root / "docs" / "MANUAL.md"
out = root / "docs" / "manual.html"

text = src.read_text(encoding="utf-8")
version = re.search(r"Версия руководства: ([\d.]+)", text)
version = version.group(1) if version else ""

md = markdown.Markdown(extensions=["tables", "fenced_code", "toc", "sane_lists", "attr_list"],
                       extension_configs={"toc": {"permalink": False, "toc_depth": "2-3", "slugify": slugify_unicode}})
body = md.convert(text)

# Содержание из заголовков h2/h3 (id проставляет расширение toc)
items = []
for m in re.finditer(r'<h([23]) id="([^"]+)">(.*?)</h\1>', body, re.S):
    level, hid, title = int(m.group(1)), m.group(2), re.sub(r"<[^>]+>", "", m.group(3))
    items.append((level, hid, title))
nav = []
for level, hid, title in items:
    nav.append(f'<a class="l{level}" href="#{hid}">{html.escape(title)}</a>')
nav_html = "\n".join(nav)

# Заголовок h1 и встроенное содержание дублируют героя и боковую панель
body = re.sub(r"<h1[^>]*>.*?</h1>", "", body, count=1, flags=re.S)
body = re.sub(r'<h2 id="содержание">.*?</ol>', "", body, count=1, flags=re.S)
items = [i for i in items if i[1] != "содержание"]
nav_html = "\n".join(f'<a class="l{l}" href="#{h}">{html.escape(t)}</a>' for l, h, t in items)

# Блок-цитаты -> «советы»; таблицы в обёртку для прокрутки; ссылки на соседние md-файлы -> GitHub
body = body.replace("<blockquote>", '<blockquote class="callout">')
body = re.sub(r"(<table>.*?</table>)", r'<div class="table-wrap">\1</div>', body, flags=re.S)
body = re.sub(r'href="(?:\.\./)?([A-Z_]+\.md)"', r'href="https://github.com/ddimavo/buildmaster/blob/main/docs/\1"', body)
body = body.replace('href="CHANGELOG.md"', 'href="https://github.com/ddimavo/buildmaster/blob/main/CHANGELOG.md"')

# В таблицах Markdown вертикальная черта экранируется, в HTML обратная косая не нужна
body = body.replace("\\|", "|")

# Нумерованные h2 получают номер-«бейдж»
body = re.sub(r'<h2 id="([^"]+)">(\d+)\. (.*?)</h2>', r'<h2 id="\1"><span class="num">\2</span>\3</h2>', body)

page = f"""<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>IndustryReborn - руководство</title>
<style>
:root {{
  --bg:#f6f7fb; --panel:#ffffff; --text:#1d2433; --muted:#667085; --line:#e4e7ec;
  --accent:#2f6fed; --accent2:#12b76a; --warn:#f79009; --code:#0f172a; --codetext:#e2e8f0;
  --side:#111827; --sidetext:#cbd5e1; --shadow:0 1px 3px rgba(16,24,40,.08),0 8px 24px rgba(16,24,40,.06);
}}
:root[data-theme=dark] {{
  --bg:#0b1020; --panel:#121a2e; --text:#e5e9f2; --muted:#98a2b3; --line:#243049;
  --accent:#6ea0ff; --accent2:#32d583; --warn:#fdb022; --code:#070b16; --codetext:#dbe4f5;
  --side:#0a0f1d; --sidetext:#9fb0cc; --shadow:0 1px 3px rgba(0,0,0,.4),0 8px 24px rgba(0,0,0,.35);
}}
* {{ box-sizing:border-box; }}
html {{ scroll-behavior:smooth; scroll-padding-top:20px; }}
body {{ margin:0; font:16px/1.65 -apple-system,"Segoe UI",Roboto,"Noto Sans",Arial,sans-serif; background:var(--bg); color:var(--text); }}
.layout {{ display:flex; min-height:100vh; }}
aside {{ position:sticky; top:0; align-self:flex-start; height:100vh; width:320px; flex:none; background:var(--side); color:var(--sidetext);
        overflow-y:auto; padding:22px 14px 30px; }}
aside .brand {{ display:flex; gap:12px; align-items:center; color:#fff; padding:0 8px 14px; }}
aside .logo {{ width:42px; height:42px; border-radius:12px; display:grid; place-items:center; font-size:22px;
              background:linear-gradient(135deg,#2f6fed,#12b76a); }}
aside .brand b {{ display:block; font-size:18px; }}
aside .brand small {{ color:#8ea0bf; }}
#search {{ width:100%; padding:10px 12px; border-radius:10px; border:1px solid #243049; background:#0d1424; color:#fff; margin:6px 0 12px; font-size:14px; }}
#search::placeholder {{ color:#6b7c99; }}
aside nav a {{ display:block; color:var(--sidetext); text-decoration:none; padding:6px 10px; border-radius:8px; font-size:14px; line-height:1.35; }}
aside nav a.l2 {{ margin-top:8px; font-weight:600; color:#e8eefb; }}
aside nav a.l3 {{ padding-left:26px; font-size:13px; }}
aside nav a:hover, aside nav a.active {{ background:rgba(110,160,255,.16); color:#fff; }}
aside nav a.hide {{ display:none; }}
main {{ flex:1; min-width:0; padding:0 28px 80px; }}
.hero {{ margin:28px auto 8px; max-width:980px; border-radius:20px; padding:34px 36px; color:#fff; box-shadow:var(--shadow);
        background:radial-gradient(1200px 400px at 90% -20%,#3b82f6 0,transparent 60%),linear-gradient(135deg,#0f172a,#1e3a8a 55%,#0f766e); }}
.hero h1 {{ margin:0 0 8px; font-size:34px; letter-spacing:-.5px; }}
.hero p {{ margin:0; color:#d6e2ff; max-width:760px; }}
.chips {{ display:flex; flex-wrap:wrap; gap:8px; margin-top:16px; }}
.chips span {{ background:rgba(255,255,255,.14); padding:5px 12px; border-radius:999px; font-size:13px; }}
.content {{ max-width:980px; margin:0 auto; }}
.content > h2 {{ margin:56px 0 14px; font-size:27px; display:flex; align-items:center; gap:12px; padding-top:12px; border-top:1px solid var(--line); }}
.num {{ display:inline-grid; place-items:center; min-width:38px; height:38px; padding:0 8px; border-radius:11px; background:var(--accent); color:#fff; font-size:17px; }}
h3 {{ margin:30px 0 8px; font-size:20px; }}
h4 {{ margin:22px 0 6px; font-size:16px; color:var(--muted); text-transform:uppercase; letter-spacing:.04em; }}
p, li {{ max-width:860px; }}
a {{ color:var(--accent); }}
code {{ font-family:ui-monospace,"JetBrains Mono",Consolas,monospace; font-size:.9em; background:rgba(47,111,237,.1); color:var(--accent);
       padding:2px 6px; border-radius:6px; }}
pre {{ position:relative; background:var(--code); color:var(--codetext); padding:16px 18px; border-radius:14px; overflow:auto; box-shadow:var(--shadow); }}
pre code {{ background:none; color:inherit; padding:0; font-size:13.5px; line-height:1.6; }}
.copy {{ position:absolute; top:8px; right:8px; border:0; background:rgba(255,255,255,.12); color:#cbd5e1; padding:4px 10px; border-radius:8px;
        cursor:pointer; font-size:12px; }}
.copy:hover {{ background:rgba(255,255,255,.25); }}
.table-wrap {{ overflow-x:auto; margin:14px 0 18px; border:1px solid var(--line); border-radius:14px; background:var(--panel); box-shadow:var(--shadow); }}
table {{ border-collapse:collapse; width:100%; font-size:14.5px; }}
th {{ background:rgba(47,111,237,.08); text-align:left; font-weight:600; }}
th, td {{ padding:9px 14px; border-bottom:1px solid var(--line); vertical-align:top; }}
tr:last-child td {{ border-bottom:0; }}
tbody tr:hover {{ background:rgba(47,111,237,.04); }}
blockquote.callout {{ margin:16px 0; padding:12px 18px; border-left:4px solid var(--accent2); background:rgba(18,183,106,.09); border-radius:0 12px 12px 0; }}
blockquote.callout p {{ margin:6px 0; }}
hr {{ border:0; border-top:1px solid var(--line); margin:34px 0; }}
ul, ol {{ padding-left:24px; }}
li {{ margin:4px 0; }}
.topbar {{ display:none; }}
.tools {{ position:fixed; right:18px; bottom:18px; display:flex; flex-direction:column; gap:8px; z-index:5; }}
.tools button {{ width:44px; height:44px; border-radius:50%; border:0; background:var(--accent); color:#fff; font-size:18px; cursor:pointer; box-shadow:var(--shadow); }}
footer {{ max-width:980px; margin:50px auto 0; color:var(--muted); font-size:13px; text-align:center; }}
@media (max-width:980px) {{
  aside {{ position:fixed; z-index:10; left:-340px; transition:left .2s; }}
  aside.open {{ left:0; }}
  .topbar {{ display:flex; position:sticky; top:0; z-index:4; align-items:center; gap:12px; padding:10px 14px; background:var(--panel); border-bottom:1px solid var(--line); }}
  .topbar button {{ border:1px solid var(--line); background:var(--bg); color:var(--text); border-radius:8px; padding:6px 12px; font-size:15px; }}
  main {{ padding:0 14px 80px; }}
  .hero {{ padding:24px 20px; }} .hero h1 {{ font-size:26px; }}
}}
@media print {{
  aside, .tools, .topbar, .copy {{ display:none !important; }}
  body {{ background:#fff; color:#000; font-size:12px; }}
  main {{ padding:0; }} .hero {{ color:#000; background:none; box-shadow:none; padding:0; }} .hero p, .chips span {{ color:#000; }}
  .table-wrap, pre {{ box-shadow:none; }} pre {{ background:#f3f4f6; color:#111; }}
}}
</style>
</head>
<body data-version="{html.escape(version)}">
<div class="topbar"><button id="menu">☰ Разделы</button><b>IndustryReborn</b></div>
<div class="layout">
<aside id="side">
  <div class="brand"><div class="logo">🏙️</div><div><b>IndustryReborn</b><small>Руководство {html.escape(version)}</small></div></div>
  <input id="search" type="search" placeholder="Поиск по разделам…" autocomplete="off">
  <nav id="nav">
{nav_html}
  </nav>
</aside>
<main>
  <div class="hero">
    <h1>IndustryReborn</h1>
    <p>Район коммерции для Minecraft: участки, аренда, мастерские и электричество, отели, работники и зарплаты, банк, общий аукцион и участки игроков на WorldGuard.</p>
    <div class="chips"><span>Paper 1.21.6 – 26.x</span><span>Java 21</span><span>Vault</span><span>WorldGuard · ChestShop · zAuctionHouse (по желанию)</span><span>Руководство {html.escape(version)}</span></div>
  </div>
  <div class="content">
{body}
  </div>
  <footer>Документ собран из docs/MANUAL.md (scripts/build-manual.py). Не правьте HTML вручную.</footer>
</main>
</div>
<div class="tools"><button id="theme" title="Тема">🌓</button><button id="up" title="Наверх">↑</button></div>
<script>
const root=document.documentElement;
if(localStorage.getItem('bm-theme')) root.dataset.theme=localStorage.getItem('bm-theme');
else if(matchMedia('(prefers-color-scheme: dark)').matches) root.dataset.theme='dark';
document.getElementById('theme').onclick=()=>{{const t=root.dataset.theme==='dark'?'light':'dark';root.dataset.theme=t;localStorage.setItem('bm-theme',t);}};
document.getElementById('up').onclick=()=>scrollTo({{top:0}});
const side=document.getElementById('side');
document.getElementById('menu').onclick=()=>side.classList.toggle('open');
side.addEventListener('click',e=>{{if(e.target.tagName==='A')side.classList.remove('open');}});
// копирование кода
document.querySelectorAll('pre').forEach(pre=>{{const b=document.createElement('button');b.className='copy';b.textContent='Копировать';
  b.onclick=()=>{{navigator.clipboard.writeText(pre.innerText.replace(/Копировать$/,'').trim());b.textContent='Готово';setTimeout(()=>b.textContent='Копировать',1200);}};pre.appendChild(b);}});
// поиск по оглавлению
const links=[...document.querySelectorAll('#nav a')];
document.getElementById('search').addEventListener('input',e=>{{const q=e.target.value.trim().toLowerCase();
  links.forEach(a=>a.classList.toggle('hide',q&&!a.textContent.toLowerCase().includes(q)));}});
// подсветка текущего раздела
const heads=[...document.querySelectorAll('.content h2,.content h3')];
const obs=new IntersectionObserver(es=>{{es.forEach(en=>{{if(en.isIntersecting){{links.forEach(a=>a.classList.toggle('active',a.getAttribute('href')==='#'+en.target.id));}}}});}},{{rootMargin:'0px 0px -75% 0px'}});
heads.forEach(h=>obs.observe(h));
</script>
</body>
</html>
"""
out.write_text(page, encoding="utf-8")
print(f"{out} ({out.stat().st_size // 1024} КБ, разделов: {len(items)})", file=sys.stderr)
