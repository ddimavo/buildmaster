#!/usr/bin/env python3
"""Строит docs/IndustryReborn-Balance.xlsx: таблицу баланса экономики IndustryReborn с живыми формулами.

Листы: Сводка, Параметры, Цены, Электричество, Производство, Отели, Налоги и казна, Рекомендации.
Синие ячейки с жёлтой заливкой - вводимые значения, остальное считается формулами.
Запуск: python3 scripts/build-balance-xlsx.py [путь_к_файлу]
"""
import sys

from openpyxl import Workbook
from openpyxl.chart import BarChart, Reference
from openpyxl.formatting.rule import FormulaRule
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from openpyxl.workbook.defined_name import DefinedName
from openpyxl.worksheet.datavalidation import DataValidation

OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/IndustryReborn-Balance.xlsx"

wb = Workbook()
F_IN = Font(name="Calibri", size=11, color="0000FF")
F_BOLD = Font(name="Calibri", size=11, bold=True)
F_HEAD = Font(name="Calibri", size=11, bold=True, color="FFFFFF")
F_TITLE = Font(name="Calibri", size=16, bold=True, color="1F4E78")
F_SUB = Font(name="Calibri", size=12, bold=True, color="1F4E78")
F_NOTE = Font(name="Calibri", size=10, italic=True, color="595959")
FILL_IN = PatternFill("solid", fgColor="FFF2CC")
FILL_HEAD = PatternFill("solid", fgColor="1F4E78")
FILL_KEY = PatternFill("solid", fgColor="E2EFDA")
FILL_GRP = PatternFill("solid", fgColor="D9E1F2")
FILL_BAD = PatternFill("solid", fgColor="F8CBAD")
FILL_WARN = PatternFill("solid", fgColor="FFE699")
FILL_OK = PatternFill("solid", fgColor="C6E0B4")
THIN = Side(style="thin", color="BFBFBF")
BORDER = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)
WRAP = Alignment(wrap_text=True, vertical="top")
CENTER = Alignment(horizontal="center", vertical="center", wrap_text=True)

NUM = '#,##0.00'
INT = '#,##0'
PCT = '0.0%'
MULT = '0.000'


def define(name, ws, cell):
    ref = "'%s'!$%s$%s" % (ws.title, ''.join(c for c in cell if c.isalpha()), ''.join(c for c in cell if c.isdigit()))
    wb.defined_names[name] = DefinedName(name, attr_text=ref)


def put(ws, cell, value, kind=None, fmt=None, bold=False):
    c = ws[cell]
    c.value = value
    if kind == "in":
        c.font = F_IN
        c.fill = FILL_IN
        c.border = BORDER
    elif kind == "key":
        c.font = F_BOLD
        c.fill = FILL_KEY
        c.border = BORDER
    elif kind == "head":
        c.font = F_HEAD
        c.fill = FILL_HEAD
        c.alignment = CENTER
        c.border = BORDER
    elif kind == "grp":
        c.font = F_BOLD
        c.fill = FILL_GRP
    elif kind == "note":
        c.font = F_NOTE
        c.alignment = WRAP
    elif kind == "calc":
        c.border = BORDER
        if bold:
            c.font = F_BOLD
    elif kind == "title":
        c.font = F_TITLE
    elif kind == "sub":
        c.font = F_SUB
    if fmt:
        c.number_format = fmt
    return c


def widths(ws, ws_widths):
    for i, w in enumerate(ws_widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = w


def status_formats(ws, rng, first):
    ws.conditional_formatting.add(rng, FormulaRule(formula=['ISNUMBER(SEARCH("Убыточ",%s))' % first], fill=FILL_BAD))
    ws.conditional_formatting.add(rng, FormulaRule(formula=['ISNUMBER(SEARCH("Завышен",%s))' % first], fill=FILL_WARN))
    ws.conditional_formatting.add(rng, FormulaRule(formula=['ISNUMBER(SEARCH("Перегруз",%s))' % first], fill=FILL_BAD))
    ws.conditional_formatting.add(rng, FormulaRule(formula=['ISNUMBER(SEARCH("Близко",%s))' % first], fill=FILL_WARN))
    ws.conditional_formatting.add(rng, FormulaRule(formula=['ISNUMBER(SEARCH("Ниже цели",%s))' % first], fill=FILL_WARN))
    ws.conditional_formatting.add(rng, FormulaRule(formula=['ISNUMBER(SEARCH("В норме",%s))' % first], fill=FILL_OK))
    ws.conditional_formatting.add(rng, FormulaRule(formula=['ISNUMBER(SEARCH("Норма",%s))' % first], fill=FILL_OK))


# ====================================================================== листы
s_sum = wb.active
s_sum.title = "Сводка"
s_par = wb.create_sheet("Параметры")
s_prc = wb.create_sheet("Цены")
s_el = wb.create_sheet("Электричество")
s_prod = wb.create_sheet("Производство")
s_hot = wb.create_sheet("Отели")
s_tax = wb.create_sheet("Налоги и казна")
s_rec = wb.create_sheet("Рекомендации")

# ====================================================================== Параметры
ws = s_par
put(ws, "A1", "Параметры экономики", "title")
put(ws, "A2", "Синие ячейки на жёлтом фоне - вводимые значения. Всё остальное в книге считается формулами. "
              "Меняйте здесь, а результат смотрите на листах «Сводка» и «Рекомендации».", "note")
ws.merge_cells("A2:F2")
ws.row_dimensions[2].height = 32
for col, h in zip("ABCDEF", ["Группа", "Параметр", "Значение", "Ед.", "Ключ в config.yml", "Пояснение"]):
    put(ws, col + "4", h, "head")

PARAMS = [
    ("Игроки и экономика", None, None, None, None, None, None),
    (None, "players_online", "Игроков онлайн в среднем", 20, "чел", "", "Сколько игроков одновременно получают деньги за работы и аукцион.", INT),
    (None, "hours_day", "Часов игры на игрока в сутки", 2, "ч", "", "Среднее время игры одного игрока за сутки.", NUM),
    (None, "income_hour", "Доход игрока (работы + аукцион) в час", 600, "в час", "", "Сколько денег игрок зарабатывает в час вне плагина. Это приток денег на сервер.", NUM),
    (None, "target_ratio", "Целевое отношение стока к притоку", 0.95, "", "", "Сток - поступления в казну за сутки, приток - заработок игроков. 1.0 - деньги не копятся и не исчезают.", NUM),
    (None, "ratio_band", "Допустимое отклонение от цели", 0.10, "", "", "Внутри цели ± это значения статус «в норме».", NUM),
    (None, "index_k", "Сила регулировки (k)", 0.8, "", "", "Индекс цен = 1 + k × (цель − факт) / цель. Больше k - резче реакция.", NUM),
    (None, "index_min", "Индекс цен: нижний предел", 0.7, "", "", "Тарифы и налоги не опустятся ниже базы × это значение.", NUM),
    (None, "index_max", "Индекс цен: верхний предел", 1.5, "", "", "Тарифы и налоги не поднимутся выше базы × это значение.", NUM),
    ("Строительство зданий", None, None, None, None, None, None),
    (None, "build_blocks_day", "Блоков зданий, заказанных игроками за сутки", 1500, "блоков", "", "Игроки заказывают здания на своих участках; платят за каждый блок, деньги идут в казну.", INT),
    (None, "build_price_block", "Цена одного блока здания", 2.0, "за блок", "auto-build.price-per-block", "Базовая цена; множители типов зданий - auto-build.price-categories. Автокорректировка двигает цену индексом цен.", NUM),
    ("Электричество", None, None, None, None, None, None),
    (None, "coef_smelter", "Коэффициент: плавильные станки", 1.0, "V/сут", "electricity.stations.smelter", "Печь, плавильная печь, коптильня.", NUM),
    (None, "coef_anvil", "Коэффициент: наковальня", 1.5, "V/сут", "electricity.stations.anvil", "", NUM),
    (None, "coef_alchemy", "Коэффициент: варочная стойка", 2.5, "V/сут", "electricity.stations.alchemy", "", NUM),
    (None, "coef_enchanting", "Коэффициент: стол зачарований", 4.0, "V/сут", "electricity.stations.enchanting", "", NUM),
    (None, "progression", "Прогрессия за каждый следующий станок типа", 0.10, "", "electricity.progression-per-extra-same-type", "", NUM),
    (None, "ws_count", "Мастерских на сервере", 6, "шт", "", "Типовая мастерская описана на листе «Производство» (число линий).", INT),
    (None, "batches_day", "Реальных партий в сутки: плавка", 200, "шт", "", "Ограничивается сырьём, а не временем станка: сколько партий в сутки действительно делает один станок.", INT),
    (None, "batches_assemble", "Реальных партий в сутки: сборка", 40, "шт", "", "То же для наковальни.", INT),
    (None, "batches_enchant", "Реальных партий в сутки: зачарование", 10, "шт", "", "То же для стола зачарований.", INT),
    (None, "energy_share", "Целевая доля электричества в выручке мастерской", 0.10, "", "", "Из неё считается цена электричества: энергия ≈ эта доля выручки типовой мастерской.", PCT),
    (None, "price_v_min", "Цена 1 V: минимум", 5, "в сутки", "electricity.base-rate-per-day", "Жёсткие границы рекомендации.", NUM),
    (None, "price_v_max", "Цена 1 V: максимум", 500, "в сутки", "electricity.base-rate-per-day", "", NUM),
    (None, "plant_capacity", "Мощность одной электростанции", 1000, "V/сут", "grid.default-capacity", "", INT),
    (None, "plants_count", "Электростанций на сервере", 2, "шт", "", "", INT),
    (None, "target_load", "Целевая загрузка сети", 0.8, "", "", "Выше этой доли рекомендуется строить ещё станцию.", PCT),
    (None, "grid_step", "Шаг множителя при перегрузке", 0.02, "", "grid.surcharge-step", "Справочно.", NUM),
    (None, "grid_max", "Максимум множителя при перегрузке", 3.0, "", "grid.max-multiplier", "Справочно.", NUM),
    ("Производство", None, None, None, None, None, None),
    (None, "time_factor", "Множитель времени партии", 1.0, "", "production.time-factor", "", NUM),
    (None, "asm_base", "Сборка: база времени", 4, "сек", "production.assembly-seconds-base", "", NUM),
    (None, "asm_per_item", "Сборка: секунд за предмет сырья", 0.5, "сек", "production.assembly-seconds-per-item", "", NUM),
    (None, "lapis_base", "Зачарование: лазурит, база", 1, "шт", "enchanting.lapis-base", "", INT),
    (None, "lapis_per_level", "Зачарование: лазурит за уровень", 2, "шт", "enchanting.lapis-per-level", "", INT),
    (None, "ench_base", "Зачарование: база времени", 8, "сек", "enchanting.seconds-base", "", NUM),
    (None, "ench_per_level", "Зачарование: секунд за уровень", 4, "сек", "enchanting.seconds-per-level", "", NUM),
    (None, "margin_target", "Целевая маржа мастерской", 0.25, "", "", "Наценка на себестоимость в рекомендуемой цене.", PCT),
    (None, "price_tol", "Допуск цены вокруг рекомендуемой", 0.20, "", "", "Коридор «в норме» для рыночной цены.", PCT),
    (None, "other_cost_batch", "Прочие расходы на партию", 0, "", "", "Зарплата работников и т.п., на одну партию.", NUM),
    (None, "sold_share", "Доля продукции, продаваемая через витрину", 0.7, "", "", "Остальное - заказы игроков и склад (налог с продаж не платится).", PCT),
    (None, "inst_smelter", "Установка станка: плавильный", 200, "", "station-install.smelter", "Для расчёта окупаемости.", NUM),
    (None, "inst_anvil", "Установка станка: наковальня", 300, "", "station-install.anvil", "", NUM),
    (None, "inst_alchemy", "Установка станка: варочная стойка", 500, "", "station-install.alchemy", "", NUM),
    (None, "inst_enchanting", "Установка станка: стол зачарований", 1000, "", "station-install.enchanting", "", NUM),
    ("Отели", None, None, None, None, None, None),
    (None, "hotel_base", "Потребление номера: начальное значение", 0.2, "V/сут", "hotel-electricity.base-units", "", NUM),
    (None, "hotel_per_area", "Потребление номера: рост за блок площади", 0.01, "V/сут", "hotel-electricity.per-area", "", NUM),
    (None, "occupancy", "Средняя занятость номеров", 0.6, "", "", "Электричество платят только за занятые номера.", PCT),
    (None, "hotel_margin", "Целевая маржа управляющего отеля", 0.30, "", "", "Над расходами на электричество.", PCT),
    (None, "hotels_count", "Отелей на сервере", 2, "шт", "", "Набор номеров описан на листе «Отели».", INT),
    ("Налоги и казна", None, None, None, None, None, None),
    (None, "sales_tax_cur", "Налог с продаж: базовая ставка", 0.03, "", "goods.sales-tax-percent", "Стартовая ставка; регулировка применяется к ней.", PCT),
    (None, "sales_tax_min", "Налог с продаж: минимум", 0.0, "", "", "", PCT),
    (None, "sales_tax_max", "Налог с продаж: максимум", 0.12, "", "", "", PCT),
    (None, "rent_tax_cur", "Налог на аренду: базовая ставка", 0.05, "", "bank.rent-tax-percent", "Платит управляющий отеля и арендодатели участков.", PCT),
    (None, "rent_tax_min", "Налог на аренду: минимум", 0.0, "", "", "", PCT),
    (None, "rent_tax_max", "Налог на аренду: максимум", 0.20, "", "", "", PCT),
    (None, "period_days", "Период налога на имущество", 7, "сут", "bank.property-tax-period-days", "", INT),
    (None, "deposit_comm", "Комиссия за пополнение счёта", 0.01, "", "bank.deposit-commission-percent", "", PCT),
    (None, "withdraw_comm", "Комиссия за снятие со счёта", 0.01, "", "bank.withdraw-commission-percent", "", PCT),
    (None, "deposits_day", "Пополнения счетов за сутки", 5000, "", "", "Оборот операций по счетам предприятий.", INT),
    (None, "withdrawals_day", "Снятия со счетов за сутки", 3000, "", "", "", INT),
    (None, "shops_count", "Магазинов на сервере", 6, "шт", "", "", INT),
    (None, "shop_sales_day", "Оборот одного магазина за сутки", 1500, "", "", "", INT),
    (None, "other_income_day", "Прочие поступления в казну за сутки", 300, "", "", "Штрафы при изъятии, аукцион казны и т.п.", INT),
    (None, "treasury_target", "Желаемые поступления в казну за сутки", 20000, "", "", "Из этого считаются нехватка и нужная ставка налога.", INT),
    (None, "subsidy_share", "Доля поступлений на субсидии", 0.40, "", "", "Фонд развития.", PCT),
    (None, "prize_share", "Доля поступлений на призы", 0.10, "", "", "Мероприятия.", PCT),
    (None, "burst_days", "Запас расхода (сутки)", 3, "сут", "treasury.daily-limit", "Суточный лимит = бюджет в сутки × это значение.", INT),
    (None, "jobs_block_min", "Работы: минут в оплачиваемом блоке", 30, "мин", "jobs.work-block-minutes", "", INT),
    (None, "wage_cap", "Работы: предел ставки (доля от заработка вне плагина)", 1.5, "", "jobs.max-rate", "Ставка за блок не выше заработка игрока × этот коэффициент.", NUM),
]
row = 5
for item in PARAMS:
    if item[1] is None:
        put(ws, "A%d" % row, item[0], "grp")
        for col in "BCDEF":
            ws["%s%d" % (col, row)].fill = FILL_GRP
        row += 1
        continue
    _, name, label, value, unit, key, note, fmt = item
    put(ws, "B%d" % row, label)
    put(ws, "C%d" % row, value, "in", fmt)
    put(ws, "D%d" % row, unit)
    put(ws, "E%d" % row, key)
    put(ws, "F%d" % row, note, "note")
    define(name, ws, "C%d" % row)
    row += 1
widths(ws, [22, 50, 14, 10, 42, 70])
ws.freeze_panes = "A5"

# ====================================================================== Цены
ws = s_prc
put(ws, "A1", "Справочник рыночных цен", "title")
put(ws, "A2", "Цены сырья и продукции на рынке (аукцион, магазины). Из справочника берутся стоимость сырья и рыночная цена продукции. "
              "Добавляйте свои строки в конец списка (диапазон захватывает 60 строк).", "note")
ws.merge_cells("A2:D2")
ws.row_dimensions[2].height = 32
for col, h in zip("ABC", ["Предмет", "Цена за штуку", "Комментарий"]):
    put(ws, col + "4", h, "head")
PRICES = [
    ("RAW_IRON", 5, "Железная руда (сырьё)"), ("RAW_GOLD", 13, "Золотая руда (сырьё)"), ("RAW_COPPER", 3.5, "Медная руда (сырьё)"),
    ("IRON_INGOT", 8, "как auction.prices.IRON_INGOT"), ("GOLD_INGOT", 20, "как auction.prices.GOLD_INGOT"), ("COPPER_INGOT", 6, ""),
    ("STICK", 0.3, ""), ("DIAMOND", 100, "как auction.prices.DIAMOND"), ("LAPIS_LAZULI", 3, "для зачарований"),
    ("BEEF", 2, ""), ("COOKED_BEEF", 4.5, ""),
    ("IRON_BLOCK", 96, ""), ("GOLD_BLOCK", 245, ""), ("COPPER_BLOCK", 68, ""),
    ("IRON_PICKAXE", 42, ""), ("IRON_SWORD", 20, ""), ("DIAMOND_PICKAXE", 400, ""), ("DIAMOND_SWORD", 220, "заготовка для зачарования"),
    ("DIAMOND_SWORD +Острота V", 440, "зачарованный предмет"), ("DIAMOND_PICKAXE +Эффективность V", 570, "зачарованный предмет"),
]
r0 = 5
for i, (name, price, note) in enumerate(PRICES):
    put(ws, "A%d" % (r0 + i), name, "in")
    put(ws, "B%d" % (r0 + i), price, "in", NUM)
    put(ws, "C%d" % (r0 + i), note, "note")
for i in range(len(PRICES), 60):
    put(ws, "A%d" % (r0 + i), None, "in")
    put(ws, "B%d" % (r0 + i), None, "in", NUM)
wb.defined_names["book"] = DefinedName("book", attr_text="'Цены'!$A$5:$B$64")
widths(ws, [38, 16, 50])
ws.freeze_panes = "A5"

# ====================================================================== Производство
ws = s_prod
put(ws, "A1", "Производство: себестоимость и рекомендуемые цены", "title")
put(ws, "A2", "Строки с числом линий больше нуля образуют типовую мастерскую: из них считаются потребление электричества и выручка. "
              "Остальные строки - справочные расчёты цены. Рецепты и время берутся из игры (для плавки время рецепта вводится вручную).", "note")
ws.merge_cells("A2:AA2")
ws.row_dimensions[2].height = 30
HEAD = ["Продукт", "Вид", "Станок", "Уровень зачар.", "Сырьё 1", "Кол-во 1", "Сырьё 2", "Кол-во 2", "Выход, шт",
        "Время рецепта игры, сек", "Время партии, сек", "Макс. партий в сутки", "Линий (станков)", "Партий в сутки на линию",
        "Продукции в сутки", "Сырьё на партию", "Энергия на партию", "Прочее на партию", "Себестоимость партии",
        "Себестоимость штуки", "Нижняя цена (без убытка)", "Рекомендуемая цена", "Верхняя цена (потолок)", "Рыночная цена",
        "Статус цены", "Выручка в сутки (рынок)", "Прибыль в сутки (все линии)", "Окупаемость станка, сут"]
HR = 4
for i, h in enumerate(HEAD, 1):
    put(ws, "%s%d" % (get_column_letter(i), HR), h, "head")
ws.row_dimensions[HR].height = 48
# вид: smelt / assemble / enchant ; станок: smelter / anvil / enchanting
PRODUCTS = [
    # name, kind, station, level, ing1, q1, ing2, q2, yield, game_sec, lines
    ("IRON_INGOT", "smelt", "smelter", None, "RAW_IRON", 1, None, None, 1, 5, 2),
    ("GOLD_INGOT", "smelt", "smelter", None, "RAW_GOLD", 1, None, None, 1, 5, 2),
    ("COPPER_INGOT", "smelt", "smelter", None, "RAW_COPPER", 1, None, None, 1, 5, 1),
    ("IRON_BLOCK", "assemble", "anvil", None, "IRON_INGOT", 9, None, None, 1, None, 1),
    ("IRON_PICKAXE", "assemble", "anvil", None, "IRON_INGOT", 3, "STICK", 2, 1, None, 1),
    ("DIAMOND_SWORD +Острота V", "enchant", "enchanting", 5, "DIAMOND_SWORD", 1, "LAPIS_LAZULI", None, 1, None, 1),
    ("COOKED_BEEF", "smelt", "smelter", None, "BEEF", 1, None, None, 1, 5, 0),
    ("GOLD_BLOCK", "assemble", "anvil", None, "GOLD_INGOT", 9, None, None, 1, None, 0),
    ("COPPER_BLOCK", "assemble", "anvil", None, "COPPER_INGOT", 9, None, None, 1, None, 0),
    ("IRON_SWORD", "assemble", "anvil", None, "IRON_INGOT", 2, "STICK", 1, 1, None, 0),
    ("DIAMOND_PICKAXE", "assemble", "anvil", None, "DIAMOND", 3, "STICK", 2, 1, None, 0),
    ("DIAMOND_PICKAXE +Эффективность V", "enchant", "enchanting", 5, "DIAMOND_PICKAXE", 1, "LAPIS_LAZULI", None, 1, None, 0),
]
P0 = HR + 1
PN = P0 + len(PRODUCTS) - 1
for i, (name, kind, station, level, i1, q1, i2, q2, yld, gsec, lines) in enumerate(PRODUCTS):
    r = P0 + i
    put(ws, "A%d" % r, name, "in")
    put(ws, "B%d" % r, kind, "in")
    put(ws, "C%d" % r, station, "in")
    put(ws, "D%d" % r, level, "in", INT)
    put(ws, "E%d" % r, i1, "in")
    put(ws, "F%d" % r, q1, "in", NUM)
    put(ws, "G%d" % r, i2, "in")
    if kind == "enchant":
        put(ws, "H%d" % r, "=lapis_base+lapis_per_level*D%d" % r, "calc", NUM)
    else:
        put(ws, "H%d" % r, q2, "in", NUM)
    put(ws, "I%d" % r, yld, "in", NUM)
    put(ws, "J%d" % r, gsec, "in", NUM)
    # время партии
    put(ws, "K%d" % r,
        '=IF(B{r}="smelt",J{r}*time_factor,IF(B{r}="enchant",(ench_base+ench_per_level*D{r})*time_factor,'
        '(asm_base+asm_per_item*(F{r}+N(H{r})))*time_factor))'.format(r=r), "calc", NUM)
    put(ws, "L%d" % r, "=86400/K%d" % r, "calc", INT)
    put(ws, "M%d" % r, lines, "in", INT)
    put(ws, "N%d" % r, '=MIN(IF(B{r}="smelt",batches_day,IF(B{r}="assemble",batches_assemble,batches_enchant)),L{r})'.format(r=r), "calc", INT)
    put(ws, "O%d" % r, "=M{r}*N{r}*I{r}".format(r=r), "calc", INT)
    put(ws, "P%d" % r, '=F{r}*IFERROR(VLOOKUP(E{r},book,2,FALSE),0)+N(H{r})*IF(G{r}="",0,IFERROR(VLOOKUP(G{r},book,2,FALSE),0))'.format(r=r), "calc", NUM)
    put(ws, "Q%d" % r, "=IFERROR(INDEX('Электричество'!$H$6:$H$9,MATCH(C{r},'Электричество'!$A$6:$A$9,0))/N{r},0)".format(r=r), "calc", '0.000')
    put(ws, "R%d" % r, "=other_cost_batch", "calc", NUM)
    put(ws, "S%d" % r, "=P{r}+Q{r}+R{r}".format(r=r), "calc", NUM)
    put(ws, "T%d" % r, "=S{r}/I{r}".format(r=r), "calc", NUM)
    put(ws, "U%d" % r, "=T{r}/(1-sales_tax_final)".format(r=r), "calc", NUM)
    put(ws, "V%d" % r, "=T{r}*(1+margin_target)/(1-sales_tax_final)".format(r=r), "key", NUM)
    put(ws, "W%d" % r, "=V{r}*(1+price_tol)".format(r=r), "calc", NUM)
    put(ws, "X%d" % r, "=IFERROR(VLOOKUP(A{r},book,2,FALSE),0)".format(r=r), "calc", NUM)
    put(ws, "Y%d" % r,
        '=IF(X{r}=0,"Нет рыночной цены",IF(X{r}<U{r},"Убыточно: рынок ниже себестоимости",IF(X{r}>W{r},"Завышена: рынок выше потолка, ограничьте цену",'
        'IF(X{r}<V{r},"Ниже цели по марже","В норме"))))'.format(r=r), "calc")
    put(ws, "Z%d" % r, "=O{r}*X{r}".format(r=r), "calc", INT)
    put(ws, "AA%d" % r, "=M{r}*N{r}*(I{r}*X{r}*(1-sales_tax_final*sold_share)-S{r})".format(r=r), "calc", INT)
    put(ws, "AB%d" % r,
        '=IF(OR(M{r}=0,AA{r}<=0),"-",INDEX(inst_prices,MATCH(C{r},inst_names,0))/(AA{r}/M{r}))'.format(r=r), "calc", INT)
status_formats(ws, "Y%d:Y%d" % (P0, PN), "Y%d" % P0)
tot = PN + 2
put(ws, "A%d" % tot, "Итого типовая мастерская", "grp")
put(ws, "M%d" % tot, "=SUM(M%d:M%d)" % (P0, PN), "key", INT)
put(ws, "Z%d" % tot, "=SUM(Z%d:Z%d)" % (P0, PN), "key", INT)
put(ws, "AA%d" % tot, "=SUM(AA%d:AA%d)" % (P0, PN), "key", INT)
define("ws_revenue", ws, "Z%d" % tot)
define("ws_profit", ws, "AA%d" % tot)
put(ws, "A%d" % (tot + 2), "Пояснение к столбцам", "sub")
notes = [
    "Станок: smelter - печь/плавильная печь/коптильня, anvil - наковальня, enchanting - стол зачарований.",
    "Вид: smelt - плавка (время рецепта вводится из игры), assemble - сборка (время = база + за предмет), enchant - зачарование (формула из config).",
    "Энергия на партию = суточная цена станка этого типа ÷ реальных партий в сутки: электричество списывается непрерывно, независимо от работы.",
    "Рекомендуемая цена = себестоимость × (1 + целевая маржа) ÷ (1 − налог с продаж). Нижняя цена - без убытка; потолок = рекомендуемая × (1 + допуск).",
    "Статус сравнивает рыночную цену из листа «Цены» с коридором. «Убыточно» - рынок не покрывает себестоимость, «Завышена» - рынок выше потолка.",
    "Окупаемость станка = цена установки станка ÷ суточная прибыль одной линии (при рыночных ценах).",
]
for i, n in enumerate(notes):
    put(ws, "A%d" % (tot + 3 + i), n, "note")
    ws.merge_cells("A%d:N%d" % (tot + 3 + i, tot + 3 + i))
widths(ws, [34, 10, 12, 8, 16, 8, 14, 8, 8, 10, 10, 10, 9, 10, 11, 11, 11, 10, 12, 12, 12, 13, 12, 12, 40, 13, 14, 12])
ws.freeze_panes = "B5"
dv = DataValidation(type="list", formula1='"smelt,assemble,enchant"', allow_blank=False)
ws.add_data_validation(dv)
dv.add("B%d:B%d" % (P0, PN))
dv2 = DataValidation(type="list", formula1='"smelter,anvil,alchemy,enchanting"', allow_blank=False)
ws.add_data_validation(dv2)
dv2.add("C%d:C%d" % (P0, PN))

# ====================================================================== Электричество
ws = s_el
put(ws, "A1", "Электричество: потребление, цена 1 V и нагрузка сети", "title")
put(ws, "A2", "Цена 1 V считается из выручки типовой мастерской: электричество должно составлять целевую долю выручки. "
              "Затем цена корректируется индексом цен (см. «Сводка») и ограничивается минимумом и максимумом.", "note")
ws.merge_cells("A2:H2")
ws.row_dimensions[2].height = 32
for col, h in zip("ABCDEFGH", ["Тип станка", "Коэффициент", "Станков в типовой мастерской", "V в сутки (с прогрессией)",
                              "Средний множитель прогрессии", "Суточная цена 1 станка (база)", "Суточная цена 1 станка (рекомендуемая)", "Суточная цена 1 станка (для расчёта партий)"]):
    put(ws, col + "5", h, "head")
ws.row_dimensions[5].height = 62
TYPES = [("smelter", "coef_smelter"), ("anvil", "coef_anvil"), ("alchemy", "coef_alchemy"), ("enchanting", "coef_enchanting")]
for i, (t, coef) in enumerate(TYPES):
    r = 6 + i
    put(ws, "A%d" % r, t)
    put(ws, "B%d" % r, "=" + coef, "calc", NUM)
    put(ws, "C%d" % r, "=SUMIF('Производство'!$C$%d:$C$%d,A%d,'Производство'!$M$%d:$M$%d)" % (P0, PN, r, P0, PN), "calc", INT)
    put(ws, "D%d" % r, "=B{r}*(C{r}+progression*C{r}*(C{r}-1)/2)".format(r=r), "calc", NUM)
    put(ws, "E%d" % r, "=IF(C{r}=0,1,(C{r}+progression*C{r}*(C{r}-1)/2)/C{r})".format(r=r), "calc", MULT)
    put(ws, "F%d" % r, "=B{r}*E{r}*price_v_base".format(r=r), "calc", NUM)
    put(ws, "G%d" % r, "=B{r}*E{r}*price_v_final".format(r=r), "calc", NUM)
    put(ws, "H%d" % r, "=G%d" % r, "calc", NUM)
put(ws, "A10", "Итого V мастерской в сутки", "grp")
put(ws, "D10", "=SUM(D6:D9)", "key", NUM)
define("v_ws", ws, "D10")
rows = [
    (12, "Выручка типовой мастерской в сутки (рынок)", "=ws_revenue", INT, False),
    (13, "Цена 1 V: из целевой доли электричества", "=IF(v_ws=0,0,energy_share*ws_revenue/v_ws)", NUM, False),
    (14, "Индекс цен (регулировка из «Сводка»)", "=econ_index", MULT, False),
    (15, "Цена 1 V: рекомендуемая (electricity.base-rate-per-day)", "=MAX(price_v_min,MIN(price_v_max,B13*B14))", NUM, True),
    (16, "Расход мастерской на электричество в сутки", "=v_ws*B15", INT, False),
    (17, "Доля электричества в выручке мастерской", "=IF(ws_revenue=0,0,B16/ws_revenue)", PCT, False),
]
for r, label, f, fmt, key in rows:
    put(ws, "A%d" % r, label)
    put(ws, "B%d" % r, f, "key" if key else "calc", fmt)
define("price_v_base", ws, "B13")
define("price_v_final", ws, "B15")
put(ws, "A19", "Нагрузка сети", "sub")
grid_rows = [
    (20, "Потребление всех мастерских, V в сутки", "=ws_count*v_ws", NUM),
    (21, "Потребление отелей при полной занятости, V в сутки", "=hotel_peak_v*hotels_count", NUM),
    (22, "Потребление отелей при средней занятости, V в сутки", "=hotel_exp_v*hotels_count", NUM),
    (23, "Пиковое потребление сети, V в сутки", "=B20+B21", NUM),
    (24, "Мощность сети (станции × мощность)", "=plants_count*plant_capacity", NUM),
    (25, "Загрузка сети в пике", "=IF(B24=0,0,B23/B24)", PCT),
    (26, "Нужно электростанций при целевой загрузке", "=ROUNDUP(B23/(plant_capacity*target_load),0)", INT),
    (27, "Состояние сети", '=IF(B25>1,"Перегрузка: множитель тарифа растёт до ×"&TEXT(grid_max,"0.0"),IF(B25>target_load,"Близко к пределу: стройте станцию","Норма"))', None),
]
for r, label, f, fmt in grid_rows:
    put(ws, "A%d" % r, label)
    put(ws, "B%d" % r, f, "calc", fmt)
ws["B25"].font = F_BOLD
status_formats(ws, "B27:B27", "B27")
define("grid_load", ws, "B25")
define("plants_needed", ws, "B26")
define("demand_total", ws, "B23")
define("price_v_ws_cost", ws, "B16")
widths(ws, [58, 22, 16, 16, 16, 18, 20, 20])
# вспомогательные диапазоны для окупаемости
wb.defined_names["inst_names"] = DefinedName("inst_names", attr_text="'Электричество'!$A$31:$A$34")
wb.defined_names["inst_prices"] = DefinedName("inst_prices", attr_text="'Электричество'!$B$31:$B$34")
put(ws, "A30", "Цена установки станка (из config station-install)", "sub")
for i, (t, nm) in enumerate([("smelter", "inst_smelter"), ("anvil", "inst_anvil"), ("alchemy", "inst_alchemy"), ("enchanting", "inst_enchanting")]):
    put(ws, "A%d" % (31 + i), t)
    put(ws, "B%d" % (31 + i), "=" + nm, "calc", NUM)

# ====================================================================== Отели
ws = s_hot
put(ws, "A1", "Отели: электричество номеров и рекомендуемая аренда", "title")
put(ws, "A2", "Номер потребляет в сутки: начальное значение + рост за блок площади × площадь пола. Электричество платят только за занятые номера. "
              "Описан один типовой отель; число отелей задано в «Параметрах».", "note")
ws.merge_cells("A2:L2")
ws.row_dimensions[2].height = 30
HH = ["Номер", "Длина, бл.", "Ширина, бл.", "Площадь пола", "V в сутки (занят)", "Электричество за сутки занятого номера",
      "Текущая аренда за сутки", "Безубыточная аренда", "Рекомендуемая аренда", "Статус аренды", "Электричество за сутки (с занятостью)", "Доход за сутки (с занятостью)"]
for i, h in enumerate(HH, 1):
    put(ws, "%s4" % get_column_letter(i), h, "head")
ws.row_dimensions[4].height = 62
ROOMS = [("101", 3, 4, 80), ("102", 3, 4, 80), ("201", 4, 5, 140), ("202", 4, 5, 140), ("301", 6, 6, 260), ("302", 6, 8, 340)]
H0 = 5
HN = H0 + len(ROOMS) - 1
for i, (rid, lx, lz, rent) in enumerate(ROOMS):
    r = H0 + i
    put(ws, "A%d" % r, rid, "in")
    put(ws, "B%d" % r, lx, "in", INT)
    put(ws, "C%d" % r, lz, "in", INT)
    put(ws, "D%d" % r, "=B%d*C%d" % (r, r), "calc", INT)
    put(ws, "E%d" % r, "=hotel_base+hotel_per_area*D%d" % r, "calc", NUM)
    put(ws, "F%d" % r, "=E%d*price_v_final" % r, "calc", NUM)
    put(ws, "G%d" % r, rent, "in", NUM)
    put(ws, "H%d" % r, "=F{r}/(1-rent_tax_final)".format(r=r), "calc", NUM)
    put(ws, "I%d" % r, "=F{r}*(1+hotel_margin)/(1-rent_tax_final)".format(r=r), "key", NUM)
    put(ws, "J%d" % r,
        '=IF(G{r}<H{r},"Убыточно: аренда не покрывает электричество",IF(G{r}<I{r},"Ниже цели по марже","В норме"))'.format(r=r), "calc")
    put(ws, "K%d" % r, "=F%d*occupancy" % r, "calc", NUM)
    put(ws, "L%d" % r, "=G%d*occupancy" % r, "calc", NUM)
status_formats(ws, "J%d:J%d" % (H0, HN), "J%d" % H0)
t = HN + 2
put(ws, "A%d" % t, "Итого типовой отель", "grp")
put(ws, "D%d" % t, "=SUM(D%d:D%d)" % (H0, HN), "key", INT)
put(ws, "E%d" % t, "=SUM(E%d:E%d)" % (H0, HN), "key", NUM)
put(ws, "K%d" % t, "=SUM(K%d:K%d)" % (H0, HN), "key", NUM)
put(ws, "L%d" % t, "=SUM(L%d:L%d)" % (H0, HN), "key", NUM)
put(ws, "A%d" % (t + 1), "Потребление при средней занятости, V в сутки")
put(ws, "E%d" % (t + 1), "=E%d*occupancy" % t, "key", NUM)
define("hotel_peak_v", ws, "E%d" % t)
define("hotel_exp_v", ws, "E%d" % (t + 1))
define("hotel_rent_day_all", ws, "L%d" % t)
put(ws, "A%d" % (t + 2), "Выручка всех отелей за сутки (занятость, аренда)")
put(ws, "E%d" % (t + 2), "=L%d*hotels_count" % t, "key", NUM)
define("hotel_revenue_day", ws, "E%d" % (t + 2))
widths(ws, [30, 11, 11, 12, 14, 20, 16, 16, 16, 44, 18, 18])
ws.freeze_panes = "B5"

# ====================================================================== Налоги и казна
ws = s_tax
put(ws, "A1", "Налоги и казна", "title")
put(ws, "A2", "Левая колонка - поступления в казну за сутки при базовых ставках, правая - при рекомендуемых после регулировки индексом цен "
              "и целевых поступлений. Казна - фонд администрации: деньги не печатаются, расходуются только из поступлений.", "note")
ws.merge_cells("A2:H2")
ws.row_dimensions[2].height = 32
put(ws, "A4", "Налог на имущество и аренда административных участков", "sub")
for col, h in zip("ABCDEFGH", ["Тип участка", "Занято участков", "Аренда за сутки", "Налог за период (база)", "Налог: минимум", "Налог: максимум",
                              "Налог за период (рекомендуемый)", "Аренда за сутки: всего"]):
    put(ws, col + "5", h, "head")
ws.row_dimensions[5].height = 48
CATS = [("Магазин", 6, 50, 200, 0, 800), ("Мастерская", 6, 80, 300, 0, 1200), ("Ресторан", 2, 60, 200, 0, 800),
        ("Склад", 2, 40, 150, 0, 600), ("Отель", 2, 100, 300, 0, 1200), ("Дом", 10, 30, 0, 0, 400)]
C0 = 6
CN = C0 + len(CATS) - 1
for i, (name, n, rent, tax, tmin, tmax) in enumerate(CATS):
    r = C0 + i
    put(ws, "A%d" % r, name)
    put(ws, "B%d" % r, n, "in", INT)
    put(ws, "C%d" % r, rent, "in", NUM)
    put(ws, "D%d" % r, tax, "in", NUM)
    put(ws, "E%d" % r, tmin, "in", NUM)
    put(ws, "F%d" % r, tmax, "in", NUM)
    put(ws, "G%d" % r, "=MAX(E{r},MIN(F{r},D{r}*econ_index))".format(r=r), "key", NUM)
    put(ws, "H%d" % r, "=B{r}*C{r}".format(r=r), "calc", NUM)
put(ws, "A%d" % (CN + 1), "Итого", "grp")
put(ws, "H%d" % (CN + 1), "=SUM(H%d:H%d)" % (C0, CN), "key", NUM)
define("rent_total", ws, "H%d" % (CN + 1))
put(ws, "A%d" % (CN + 2), "Налог на имущество за сутки (база / рекомендуемый)")
put(ws, "D%d" % (CN + 2), "=SUMPRODUCT(B{a}:B{b},D{a}:D{b})/period_days".format(a=C0, b=CN), "calc", NUM)
put(ws, "G%d" % (CN + 2), "=SUMPRODUCT(B{a}:B{b},G{a}:G{b})/period_days".format(a=C0, b=CN), "calc", NUM)
define("prop_tax_base_day", ws, "D%d" % (CN + 2))
define("prop_tax_rec_day", ws, "G%d" % (CN + 2))

SR = CN + 5
put(ws, "A%d" % (SR - 1), "Поступления в казну за сутки", "sub")
for col, h in zip("ABCDE", ["Источник", "Оборот / база за сутки", "Ставка (база)", "Базовые поступления", "Рекомендуемые поступления"]):
    put(ws, col + str(SR), h, "head")
ws.row_dimensions[SR].height = 36
R = SR + 1
# 1 аренда
put(ws, "A%d" % R, "Аренда административных участков")
put(ws, "B%d" % R, "=rent_total", "calc", NUM)
put(ws, "C%d" % R, "-")
put(ws, "D%d" % R, "=B%d" % R, "calc", NUM)
put(ws, "E%d" % R, "=B%d" % R, "calc", NUM)
r_rent = R
R += 1
# 2 налог на имущество
put(ws, "A%d" % R, "Налог на имущество")
put(ws, "B%d" % R, "=prop_tax_base_day", "calc", NUM)
put(ws, "C%d" % R, "-")
put(ws, "D%d" % R, "=prop_tax_base_day", "calc", NUM)
put(ws, "E%d" % R, "=prop_tax_rec_day", "calc", NUM)
r_prop = R
R += 1
# 3 налог на аренду
put(ws, "A%d" % R, "Налог на аренду (отели)")
put(ws, "B%d" % R, "=hotel_revenue_day", "calc", NUM)
put(ws, "C%d" % R, "=rent_tax_cur", "calc", PCT)
put(ws, "D%d" % R, "=B%d*C%d" % (R, R), "calc", NUM)
put(ws, "E%d" % R, "=B%d*rent_tax_final" % R, "calc", NUM)
r_rtax = R
R += 1
# 4 налог с продаж
put(ws, "A%d" % R, "Налог с продаж (магазины и мастерские)")
put(ws, "B%d" % R, "=shops_count*shop_sales_day+ws_count*ws_revenue*sold_share", "calc", NUM)
put(ws, "C%d" % R, "=sales_tax_cur", "calc", PCT)
put(ws, "D%d" % R, "=B%d*C%d" % (R, R), "calc", NUM)
put(ws, "E%d" % R, "=B%d*sales_tax_final" % R, "calc", NUM)
r_stax = R
R += 1
# 5 электричество
put(ws, "A%d" % R, "Электричество (мастерские и отели)")
put(ws, "B%d" % R, "=ws_count*v_ws+hotel_exp_v*hotels_count", "calc", NUM)
put(ws, "C%d" % R, "=price_v_base", "calc", NUM)
put(ws, "D%d" % R, "=B%d*price_v_base" % R, "calc", NUM)
put(ws, "E%d" % R, "=B%d*price_v_final" % R, "calc", NUM)
r_el = R
R += 1
# 6 комиссии
put(ws, "A%d" % R, "Комиссии банка")
put(ws, "B%d" % R, "=deposits_day+withdrawals_day", "calc", NUM)
put(ws, "C%d" % R, "=(deposits_day*deposit_comm+withdrawals_day*withdraw_comm)/MAX(1,deposits_day+withdrawals_day)", "calc", PCT)
put(ws, "D%d" % R, "=deposits_day*deposit_comm+withdrawals_day*withdraw_comm", "calc", NUM)
put(ws, "E%d" % R, "=D%d" % R, "calc", NUM)
r_comm = R
R += 1
# 6.5 строительство
put(ws, "A%d" % R, "Строительство зданий игроками")
put(ws, "B%d" % R, "=build_blocks_day", "calc", NUM)
put(ws, "C%d" % R, "=build_price_block", "calc", NUM)
put(ws, "D%d" % R, "=B%d*C%d" % (R, R), "calc", NUM)
put(ws, "E%d" % R, "=B%d*C%d*econ_index" % (R, R), "calc", NUM)
r_build = R
R += 1
# 7 прочее
put(ws, "A%d" % R, "Прочее (штрафы, аукцион казны)")
put(ws, "B%d" % R, "=other_income_day", "calc", NUM)
put(ws, "C%d" % R, "-")
put(ws, "D%d" % R, "=B%d" % R, "calc", NUM)
put(ws, "E%d" % R, "=B%d" % R, "calc", NUM)
r_oth = R
R += 1
put(ws, "A%d" % R, "Итого поступлений в казну за сутки", "grp")
put(ws, "D%d" % R, "=SUM(D%d:D%d)" % (r_rent, r_oth), "key", NUM)
put(ws, "E%d" % R, "=SUM(E%d:E%d)" % (r_rent, r_oth), "key", NUM)
r_tot = R
define("sink_base", ws, "D%d" % r_tot)
define("sink_rec", ws, "E%d" % r_tot)
define("sales_turnover", ws, "B%d" % r_stax)
R += 2
put(ws, "A%d" % R, "Регулировка налогов", "sub")
R += 1
for col, h in zip("ABCDEF", ["Налог", "База", "По индексу цен", "По цели казны", "Итоговая ставка", "Пояснение"]):
    put(ws, col + str(R), h, "head")
R += 1
put(ws, "A%d" % R, "Налог с продаж")
put(ws, "B%d" % R, "=sales_tax_cur", "calc", PCT)
put(ws, "C%d" % R, "=MAX(sales_tax_min,MIN(sales_tax_max,B%d*econ_index))" % R, "calc", PCT)
# нужная ставка: сколько нужно, чтобы закрыть нехватку остальных поступлений до цели
put(ws, "D%d" % R, "=IF(sales_turnover=0,0,MAX(sales_tax_min,MIN(sales_tax_max,(treasury_target-(SUM(E{a}:E{b})+SUM(E{c}:E{d})))/sales_turnover)))".format(a=r_rent, b=r_rtax, c=r_el, d=r_oth), "calc", PCT)
put(ws, "E%d" % R, "=MAX(C{r},D{r})".format(r=R), "key", PCT)
put(ws, "F%d" % R, "Итог - большее из значения по индексу и ставки, закрывающей нехватку казны до цели.", "note")
define("sales_tax_final", ws, "E%d" % R)
R += 1
put(ws, "A%d" % R, "Налог на аренду")
put(ws, "B%d" % R, "=rent_tax_cur", "calc", PCT)
put(ws, "C%d" % R, "=MAX(rent_tax_min,MIN(rent_tax_max,B%d*econ_index))" % R, "calc", PCT)
put(ws, "D%d" % R, "-")
put(ws, "E%d" % R, "=C%d" % R, "key", PCT)
put(ws, "F%d" % R, "Регулируется только индексом цен.", "note")
define("rent_tax_final", ws, "E%d" % R)
R += 2
put(ws, "A%d" % R, "Цель казны и бюджет", "sub")
R += 1
BUD = [
    ("Желаемые поступления за сутки", "=treasury_target", NUM),
    ("Рекомендуемые поступления за сутки", "=sink_rec", NUM),
    ("Нехватка до цели (минус - запас)", "=treasury_target-sink_rec", NUM),
    ("Бюджет субсидий за сутки", "=sink_rec*subsidy_share", NUM),
    ("Бюджет призов за сутки", "=sink_rec*prize_share", NUM),
    ("Резерв казны за сутки", "=sink_rec*(1-subsidy_share-prize_share)", NUM),
    ("Суточный лимит расходов (treasury.daily-limit)", "=ROUND((sink_rec*(subsidy_share+prize_share))*burst_days,0)", INT),
    ("Предел одной выплаты (treasury.max-single-payout)", "=ROUND(B{prev}/2,0)", INT),
]
b_start = R
for label, f, fmt in BUD:
    put(ws, "A%d" % R, label)
    put(ws, "B%d" % R, f.format(prev=R - 1), "key" if "treasury." in label else "calc", fmt)
    R += 1
define("daily_limit", ws, "B%d" % (b_start + 6))
define("max_single", ws, "B%d" % (b_start + 7))
define("subsidy_day", ws, "B%d" % (b_start + 3))
define("prize_day", ws, "B%d" % (b_start + 4))
widths(ws, [52, 20, 18, 22, 24, 40, 28, 20])
# диаграмма поступлений
ch = BarChart()
ch.type = "bar"
ch.style = 10
ch.title = "Поступления в казну за сутки"
ch.y_axis.title = None
ch.x_axis.title = None
data = Reference(ws, min_col=4, max_col=5, min_row=SR, max_row=r_oth)
cats = Reference(ws, min_col=1, min_row=r_rent, max_row=r_oth)
ch.add_data(data, titles_from_data=True)
ch.set_categories(cats)
ch.height = 9
ch.width = 18
ws.add_chart(ch, "H%d" % (SR))

# ====================================================================== Сводка
ws = s_sum
put(ws, "A1", "IndustryReborn: баланс экономики", "title")
put(ws, "A2", "Таблица сама считает цену электричества, рекомендуемые цены продукции и ставки налогов по показателям экономики. "
              "Меняйте синие ячейки на листах «Параметры», «Цены», «Производство», «Отели» и «Налоги и казна».", "note")
ws.merge_cells("A2:E2")
ws.row_dimensions[2].height = 34
put(ws, "A4", "Показатели притока и стока денег", "sub")
SUM = [
    (5, "Приток денег: заработок игроков в сутки", "=players_online*hours_day*income_hour", INT, None),
    (6, "Сток: поступления в казну в сутки (база)", "=sink_base", INT, None),
    (7, "Отношение стока к притоку", "=IF(B5=0,0,B6/B5)", NUM, None),
    (8, "Цель", "=target_ratio", NUM, None),
    (9, "Индекс цен (>1 - повышать цены и налоги, <1 - снижать)", "=MAX(index_min,MIN(index_max,1+index_k*(target_ratio-B7)/target_ratio))", MULT, "key"),
    (10, "Состояние денежной массы",
     '=IF(B7<target_ratio-ratio_band,"Деньги копятся (инфляция): цены и налоги повышаются",IF(B7>target_ratio+ratio_band,"Деньги изымаются слишком сильно: цены и налоги снижаются","В норме"))', None, None),
]
for r, label, f, fmt, kind in SUM:
    put(ws, "A%d" % r, label)
    put(ws, "B%d" % r, f, kind or "calc", fmt)
define("econ_index", ws, "B9")
ws["A10"].alignment = WRAP
ws.merge_cells("B10:E10")
status_formats(ws, "B10:B10", "B10")
ws.conditional_formatting.add("B10:B10", FormulaRule(formula=['ISNUMBER(SEARCH("копятся",B10))'], fill=FILL_WARN))
ws.conditional_formatting.add("B10:B10", FormulaRule(formula=['ISNUMBER(SEARCH("изымаются",B10))'], fill=FILL_WARN))
put(ws, "A12", "Рекомендуемые значения для config.yml", "sub")
SUM2 = [
    (13, "electricity.base-rate-per-day (цена 1 V в сутки)", "=price_v_final", NUM),
    (14, "goods.sales-tax-percent", "=sales_tax_final*100", NUM),
    (15, "bank.rent-tax-percent", "=rent_tax_final*100", NUM),
    (16, "treasury.daily-limit", "=daily_limit", INT),
    (17, "treasury.max-single-payout", "=max_single", INT),
    (18, "Электростанций нужно (при целевой загрузке)", "=plants_needed", INT),
]
for r, label, f, fmt in SUM2:
    put(ws, "A%d" % r, label)
    put(ws, "B%d" % r, f, "key", fmt)
put(ws, "A20", "Сеть и казна", "sub")
SUM3 = [
    (21, "Загрузка сети в пике", "=grid_load", PCT),
    (22, "Доля электричества в выручке типовой мастерской", "=IF(ws_revenue=0,0,price_v_ws_cost/ws_revenue)", PCT),
    (23, "Рекомендуемые поступления в казну за сутки", "=sink_rec", INT),
    (24, "Бюджет субсидий за сутки", "=subsidy_day", INT),
    (25, "Бюджет призов за сутки", "=prize_day", INT),
    (26, "Прибыль типовой мастерской в сутки (рынок)", "=ws_profit", INT),
]
for r, label, f, fmt in SUM3:
    put(ws, "A%d" % r, label)
    put(ws, "B%d" % r, f, "calc", fmt)
put(ws, "A28", "Как пользоваться", "sub")
HOW = [
    "1. Введите в «Параметры» реальные числа сервера: сколько игроков, сколько они зарабатывают, сколько мастерских, магазинов и отелей.",
    "2. Проверьте цены сырья и продукции на листе «Цены» (возьмите из аукциона и магазинов).",
    "3. На «Производство» задайте число линий (станков) типовой мастерской, на «Отели» - номера, на «Налоги и казна» - число занятых участков.",
    "4. Читайте «Рекомендации»: готовые значения для config.yml и рекомендуемые цены продукции. Статус «Убыточно» или «Завышена» подсказывает, что цену надо менять.",
    "5. Периодически обновляйте притоки и обороты (по журналу и отчёту /district treasury report): индекс цен подстроит тарифы и налоги.",
]
for i, h in enumerate(HOW):
    put(ws, "A%d" % (29 + i), h, "note")
    ws.merge_cells("A%d:E%d" % (29 + i, 29 + i))
    ws.row_dimensions[29 + i].height = 30
widths(ws, [62, 22, 18, 18, 18])

# диаграмма: цена продукции
ch2 = BarChart()
ch2.type = "col"
ch2.style = 10
ch2.title = "Себестоимость, рекомендуемая и рыночная цена штуки"
dsrc = s_prod
ch2.add_data(Reference(dsrc, min_col=20, min_row=HR, max_row=P0 + 5), titles_from_data=True)
ch2.add_data(Reference(dsrc, min_col=22, min_row=HR, max_row=P0 + 5), titles_from_data=True)
ch2.add_data(Reference(dsrc, min_col=24, min_row=HR, max_row=P0 + 5), titles_from_data=True)
ch2.set_categories(Reference(dsrc, min_col=1, min_row=P0, max_row=P0 + 5))
ch2.height = 9
ch2.width = 20
ws.add_chart(ch2, "G4")

# ====================================================================== Рекомендации
ws = s_rec
put(ws, "A1", "Рекомендации для config.yml", "title")
put(ws, "A2", "Скопируйте значения в config.yml и выполните /district reload. Столбец «Текущее» - стартовые значения из «Параметров», где они заданы.", "note")
ws.merge_cells("A2:E2")
ws.row_dimensions[2].height = 30
for col, h in zip("ABCDE", ["Ключ config.yml", "Текущее (база)", "Рекомендуемое", "Единица", "Комментарий"]):
    put(ws, col + "4", h, "head")
RECS = [
    ("electricity.base-rate-per-day", "=price_v_base", "=price_v_final", "за 1 V в сутки", "Цена электричества: целевая доля выручки мастерской × индекс цен.", NUM),
    ("goods.sales-tax-percent", "=sales_tax_cur*100", "=sales_tax_final*100", "%", "Регулируется индексом и целью казны.", NUM),
    ("bank.rent-tax-percent", "=rent_tax_cur*100", "=rent_tax_final*100", "%", "Регулируется индексом.", NUM),
    ("bank.property-tax.shop", "='Налоги и казна'!D6", "='Налоги и казна'!G6", "за период", "", NUM),
    ("bank.property-tax.workshop", "='Налоги и казна'!D7", "='Налоги и казна'!G7", "за период", "", NUM),
    ("bank.property-tax.restaurant", "='Налоги и казна'!D8", "='Налоги и казна'!G8", "за период", "", NUM),
    ("bank.property-tax.warehouse", "='Налоги и казна'!D9", "='Налоги и казна'!G9", "за период", "", NUM),
    ("bank.property-tax.hotel", "='Налоги и казна'!D10", "='Налоги и казна'!G10", "за период", "", NUM),
    ("bank.property-tax.house", "='Налоги и казна'!D11", "='Налоги и казна'!G11", "за период", "", NUM),
    ("auto-build.price-per-block", "=build_price_block", "=ROUND(build_price_block*econ_index,2)", "за блок", "Цена блока при строительстве игроками; индекс цен двигает её вместе с остальными.", NUM),
    ("hotel-electricity.base-units", "=hotel_base", "=hotel_base", "V в сутки", "Начальное потребление занятого номера.", NUM),
    ("hotel-electricity.per-area", "=hotel_per_area", "=hotel_per_area", "V за блок", "Рост потребления за блок площади пола.", NUM),
    ("treasury.daily-limit", 0, "=daily_limit", "в сутки", "Суточный лимит расходов казны.", INT),
    ("treasury.max-single-payout", 0, "=max_single", "за раз", "Предел одной выплаты.", INT),
    ("jobs.max-rate", 500, "=ROUND(income_hour*jobs_block_min/60*wage_cap,0)", "за блок", "Предел ставки зарплаты за блок времени.", INT),
    ("Электростанций на сервере", "=plants_count", "=plants_needed", "шт", "Хватит при целевой загрузке сети в пике.", INT),
]
for i, (key, cur, rec, unit, note, fmt) in enumerate(RECS):
    r = 5 + i
    put(ws, "A%d" % r, key)
    put(ws, "B%d" % r, cur, "calc", fmt)
    put(ws, "C%d" % r, rec, "key", fmt)
    put(ws, "D%d" % r, unit)
    put(ws, "E%d" % r, note, "note")
RN = 5 + len(RECS)
put(ws, "A%d" % (RN + 1), "Готовый фрагмент config.yml", "sub")
def fx(cell, d=2):
    return 'SUBSTITUTE(TEXT(%s,"0.%s"),",",".")' % (cell, "0" * d)
yaml = ('="electricity:"&CHAR(10)&"  base-rate-per-day: "&' + fx("price_v_final") + '&CHAR(10)'
        '&"hotel-electricity:"&CHAR(10)&"  base-units: "&' + fx("hotel_base") + '&CHAR(10)&"  per-area: "&' + fx("hotel_per_area") + '&CHAR(10)'
        '&"auto-build:"&CHAR(10)&"  price-per-block: "&' + fx("build_price_block*econ_index") + '&CHAR(10)'
        '&"goods:"&CHAR(10)&"  sales-tax-percent: "&' + fx("sales_tax_final*100") + '&CHAR(10)'
        '&"bank:"&CHAR(10)&"  rent-tax-percent: "&' + fx("rent_tax_final*100") + '&CHAR(10)'
        '&"  property-tax:"&CHAR(10)&"    shop: "&' + fx("'Налоги и казна'!G6", 0) + '&CHAR(10)'
        '&"    workshop: "&' + fx("'Налоги и казна'!G7", 0) + '&CHAR(10)'
        '&"    restaurant: "&' + fx("'Налоги и казна'!G8", 0) + '&CHAR(10)'
        '&"    warehouse: "&' + fx("'Налоги и казна'!G9", 0) + '&CHAR(10)'
        '&"    hotel: "&' + fx("'Налоги и казна'!G10", 0) + '&CHAR(10)'
        '&"    house: "&' + fx("'Налоги и казна'!G11", 0) + '&CHAR(10)'
        '&"treasury:"&CHAR(10)&"  daily-limit: "&' + fx("daily_limit", 0) + '&CHAR(10)&"  max-single-payout: "&' + fx("max_single", 0))
put(ws, "A%d" % (RN + 2), yaml, "calc")
ws["A%d" % (RN + 2)].alignment = Alignment(wrap_text=True, vertical="top")
ws["A%d" % (RN + 2)].font = Font(name="Consolas", size=10)
ws.merge_cells("A%d:E%d" % (RN + 2, RN + 2))
ws.row_dimensions[RN + 2].height = 300
put(ws, "A%d" % (RN + 4), "Рекомендуемые цены продукции (из листа «Производство»)", "sub")
for col, h in zip("ABCDEF", ["Продукт", "Себестоимость", "Нижняя цена", "Рекомендуемая цена", "Потолок", "Статус"]):
    put(ws, col + str(RN + 5), h, "head")
for i in range(len(PRODUCTS)):
    r = RN + 6 + i
    pr = P0 + i
    put(ws, "A%d" % r, "='Производство'!A%d" % pr)
    put(ws, "B%d" % r, "='Производство'!T%d" % pr, "calc", NUM)
    put(ws, "C%d" % r, "='Производство'!U%d" % pr, "calc", NUM)
    put(ws, "D%d" % r, "='Производство'!V%d" % pr, "key", NUM)
    put(ws, "E%d" % r, "='Производство'!W%d" % pr, "calc", NUM)
    put(ws, "F%d" % r, "='Производство'!Y%d" % pr, "calc")
status_formats(ws, "F%d:F%d" % (RN + 6, RN + 5 + len(PRODUCTS)), "F%d" % (RN + 6))
widths(ws, [44, 18, 18, 18, 62, 44])

for sh in wb.worksheets:
    sh.sheet_view.showGridLines = False
    sh.sheet_properties.tabColor = "1F4E78"
s_sum.sheet_properties.tabColor = "70AD47"
s_rec.sheet_properties.tabColor = "70AD47"
wb.save(OUT)
print("OK", OUT)
