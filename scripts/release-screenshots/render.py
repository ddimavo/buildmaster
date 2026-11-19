import json,html,re
data=json.load(open('real.json'))
HL={'rooms':9,'roomclass':14,'moderation':9,'factory':15,'blueprintshop':9,'vault':9,'storefront':10,'goods':10}
C16={"0":"#000000","1":"#0000aa","2":"#00aa00","3":"#00aaaa","4":"#aa0000","5":"#aa00aa","6":"#ffaa00","7":"#aaaaaa","8":"#555555","9":"#5555ff","a":"#55ff55","b":"#55ffff","c":"#ff5555","d":"#ff55ff","e":"#ffff55","f":"#ffffff"}
def md(s):
    out="";color="#ffffff";bold=False;i=0
    s=s.replace("§x","\x00")
    while i<len(s):
        ch=s[i]
        if ch=="\x00":
            hexs="".join(s[i+2*k+2] for k in range(6)) if False else None
            # format: \x00§R§R§G§G§B§B
            h="".join(s[i+1+2*k+1] for k in range(6)); color="#"+h; i+=13; continue
        if ch=="§":
            code=s[i+1].lower()
            if code in C16: color=C16[code]; bold=False
            elif code=="l": bold=True
            elif code=="r": color="#ffffff"; bold=False
            i+=2; continue
        j=i
        while j<len(s) and s[j] not in "§\x00": j+=1
        out+=f'<span style="color:{color};{"font-weight:700;" if bold else ""}">{html.escape(s[i:j])}</span>'
        i=j
    return out
KEY=[("LIGHT_GRAY_BED","#a8a8a8|Эк"),("CYAN_BED","#2ab0b0|Ко"),("YELLOW_BED","#e0c030|Лк"),("CRAFTING_TABLE","#a07040|Вк"),("ENDER_CHEST","#2a4a4a|Хр"),("IRON_INGOT","#d8d8d8|Fe"),("IRON_NUGGET","#c8c8c8|fe"),("COAL","#303030|C"),("BLAST_FURNACE","#707070|Пл"),("PAPER","#e8e8d8|▤"),("PANE","glass"),("PLAYER_HEAD","#b88a60|☺"),("GOLD_INGOT","#f0c830|Au"),("GOLD_NUGGET","#e0b020|$"),("GOLD_BLOCK","#e8c020|▣"),("EMERALD_BLOCK","#2ecc71|Мо"),("EMERALD","#2ecc71|Э"),("LIME_CONCRETE","#5fbf3a|Св"),("RED_CONCRETE","#c03030|✕"),("BELL","#e8b030|🔔"),("NOTE_BLOCK","#a07040|♪"),
 ("BRICKS","#a05040|Стр"),("OAK_SIGN","#b08850|Раб"),("CHEST","#a07030|Ящ"),("BARREL","#8a6030|Бч"),("WRITABLE_BOOK","#7a5a3a|Кн"),("BOOK","#8a5a30|Кн"),("BARRIER","#c02020|✕"),("COMPARATOR","#b0b0b0|⚙"),("PAPER","#e8e8d8|▤"),
 ("ANVIL","#505058|Мод"),("IRON_BARS","#808088|Ар"),("LIGHTNING_ROD","#c06820|Эл"),("LEVER","#909090|⇄"),("CLOCK","#d0b040|⏱"),("DIAMOND","#40d0d0|◆"),("NAME_TAG","#d0d0d0|Aa"),("ENDER_PEARL","#2a6a5a|⚑"),("ENDER_EYE","#4a8a6a|◉"),
 ("TNT","#c03030|!"),("LIME_DYE","#5fd040|●"),("GRAY_DYE","#808080|○"),("YELLOW_DYE","#e0c030|●"),("RED_DYE","#c04040|●"),("ARROW","#b0a070|←"),("SPECTRAL_ARROW","#c8a830|▶"),("NETHER_STAR","#e8e0a0|★"),("COMPASS","#9090a0|⌕"),("HOPPER","#606060|⌄"),
 ("VILLAGER_SPAWN_EGG","#a08070|Ра"),("BLAST_FURNACE","#707070|Пр"),("FURNACE","#707070|Пр"),("OAK_DOOR","#a07840|Дом"),("IRON_DOOR","#c0c0c0|Дв"),("CAKE","#e8d0b0|Рес"),("RED_BED","#c04050|От"),("LIME_BED","#50c050|От"),("MAP","#c0a070|▦"),("FILLED_MAP","#c0a070|▦"),("TRIPWIRE_HOOK","#808080|⚓"),("LEAD","#a09070|⌇"),("DIAMOND","#40d0d0|◆")]
GL={"BLACK":"#1d1d21","GRAY":"#4a4a4e","LIME":"#5fbf3a","YELLOW":"#e0c030","CYAN":"#2ab0b0","RED":"#c04040","BLUE":"#4a6fd0","LIGHT_BLUE":"#4a9fd0","ORANGE":"#e08030","PURPLE":"#8a3ab0","MAGENTA":"#b040b0"}
def icon(mat):
    if mat.endswith("_STAINED_GLASS_PANE"):
        return GL.get(mat.replace("_STAINED_GLASS_PANE",""),"#888"),""
    for k,v in KEY:
        if k==mat:
            c,l=v.split("|"); return c,l
    return "#888888",mat[:2]
def render(sc,path):
    rows=sc["rows"]; slots=sc["slots"]
    # choose highlighted slot with long tooltip
    hl=HL.get(sc["name"])
    if hl is not None and str(hl) not in slots: hl=None
    cells=""
    for i in range(rows*9):
        it=slots.get(str(i))
        if not it: cells+='<div class=slot></div>'; continue
        bg,lab=icon(it["m"])
        glass="PANE" in it["m"]
        cnt=f'<span class=cnt>{it["n"]}</span>' if it["n"]>1 else ""
        cells+=f'<div class="slot item{" hl" if i==hl else ""}"><div class="ic{" glass" if glass else ""}" style="background:{bg}">{html.escape(lab)}</div>{cnt}</div>'
    tip=""
    if hl is not None:
        it=slots[str(hl)]; r,c=divmod(hl,9)
        lines=md(it["name"])+"".join("<div>"+md(l)+"</div>" for l in it["lore"])
        left=40+c*54+60 if c<5 else 40+c*54-330
        tip=f'<div class=tip style="left:{left}px;top:{70+r*54}px"><div class=tn>{md(it["name"])}</div>'+"".join("<div>"+md(l)+"</div>" for l in it["lore"])+'</div>'
    title=md(sc["title"]).replace("#ffffff","#404040")
    body=f'''<html><head><meta charset=utf-8><style>
body{{margin:0;background:#2b2e36;font-family:"DejaVu Sans Mono",monospace;font-size:13px}}
.wrap{{padding:30px}}
.win{{position:relative;width:{9*54+16}px;background:#c6c6c6;border:3px solid #000;box-shadow:inset 3px 3px 0 #fff,inset -3px -3px 0 #555;padding:8px 8px 10px 8px}}
.title{{color:#404040;margin:2px 0 8px 4px;font-size:14px}}
.grid{{display:grid;grid-template-columns:repeat(9,54px)}}
.slot{{width:54px;height:54px;box-sizing:border-box;background:#8b8b8b;border:3px solid;border-color:#373737 #fff #fff #373737;position:relative}}
.ic{{position:absolute;left:5px;top:5px;width:36px;height:36px;border:2px solid rgba(0,0,0,.35);box-sizing:border-box;display:flex;align-items:center;justify-content:center;color:#fff;font-size:11px;font-weight:700;text-shadow:1px 1px 0 #000}}
.glass{{left:0;top:0;width:48px;height:48px;border:none;opacity:.9}}
.hl{{background:#b0b0b0}}
.cnt{{position:absolute;right:3px;bottom:0;color:#fff;text-shadow:1px 1px 0 #3f3f3f;font-weight:700}}
.tip{{position:absolute;background:#100010f2;border:2px solid #2a0a5a;border-radius:3px;padding:6px 9px;width:330px;z-index:5;line-height:1.35}}
.tn{{margin-bottom:2px}}
</style></head><body><div class=wrap><div class=win><div class=title>{title}</div><div class=grid>{cells}</div>{tip}</div></div></body></html>'''
    open(path,"w").write(body)
for sc in data: render(sc,sc["name"]+".html")
print([s["name"] for s in data])
