package ru.industryreborn.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StyleTest {

    @Test
    void parametersGetMarkersAndActionsGoBelowASeparator() {
        List<String> lore = Style.lore("<gray>Цена: <white>100", "<gray>Тип: <white>Дом", "", "<yellow>ЛКМ - купить", "<yellow>ПКМ - снять");
        assertEquals("<dark_gray>▪ <gray>Цена: <white>100", lore.get(0));
        assertEquals("<dark_gray>▪ <gray>Тип: <white>Дом", lore.get(1));
        assertTrue(lore.get(2).contains("▬"), "разделитель без пустой строки перед ним");
        assertEquals("<yellow>▶ <white>ЛКМ <gray>— купить", lore.get(3));
        assertEquals("<yellow>▶ <white>ПКМ <gray>— снять", lore.get(4));
        assertEquals(5, lore.size());
    }

    @Test
    void anActionWithoutAKeyKeepsItsWholeText() {
        List<String> lore = Style.lore("<yellow>Нажмите, чтобы открыть");
        assertEquals(List.of("<yellow>▶ <white>Нажмите, чтобы открыть"), lore, "без параметров разделитель не нужен");
    }

    @Test
    void actionColorsAreKept() {
        List<String> lore = Style.lore("<green>ЛКМ - одобрить", "<red>ПКМ - отклонить (потребуется причина)");
        assertEquals("<green>▶ <white>ЛКМ <gray>— одобрить", lore.get(0));
        assertEquals("<red>▶ <white>ПКМ <gray>— отклонить (потребуется причина)", lore.get(1));
    }

    @Test
    void descriptionsAndStatusesStayAsTheyAre() {
        List<String> lore = Style.lore("<green>● Свободен", "<gray><italic>описание: с двоеточием", "Обычный текст");
        assertEquals("<green>● Свободен", lore.get(0));
        assertEquals("<gray><italic>описание: с двоеточием", lore.get(1));
        assertEquals("Обычный текст", lore.get(2));
    }

    @Test
    void blankLinesAreCollapsedAndTrimmed() {
        List<String> lore = Style.lore("", "<gray>A: <white>1", "", "", "<gray>B: <white>2", "");
        assertEquals(List.of("<dark_gray>▪ <gray>A: <white>1", "", "<dark_gray>▪ <gray>B: <white>2"), lore);
        assertTrue(Style.lore().isEmpty());
        assertTrue(Style.lore((String) null).isEmpty());
    }

    @Test
    void theNameFirstSegmentIsBold() {
        assertEquals("<gold><bold>Магазин</bold> <gray>№ 5", Style.name("<gold>Магазин <gray>№ 5"));
        assertEquals("<green><bold>Да</bold>", Style.name("<green>Да"));
        assertEquals("<gold><bold>X</bold>", Style.name("<gold><bold>X</bold>".replace("<bold>", "").replace("</bold>", "")));
        assertEquals("<gray>Серое", Style.name("<gray>Серое"), "серые служебные подписи не выделяются");
        assertEquals("<gold><bold>Уже</bold>", Style.name("<gold><bold>Уже</bold>"));
        assertNull(Style.name(null));
        assertEquals(" ", Style.name(" "));
    }

    @Test
    void theTitleHasAnAccentAndASubtitle() {
        String t = Style.title("<dark_gray>Номера · Отель Север");
        assertTrue(t.contains("◆") && t.contains("<bold>Номера</bold>") && t.contains("· Отель Север"), t);
        assertFalse(Style.title("<dark_gray>Банк").contains("·"));
        assertFalse(Style.title("<dark_gray>Банк").contains("dark_gray"));
    }

    @Test
    void theBarIsClampedAndFilledProportionally() {
        assertEquals("<green>" + "█".repeat(5) + "<dark_gray>" + "█".repeat(5), Style.bar(0.5, 10, "green"));
        assertEquals("<red>" + "<dark_gray>" + "█".repeat(10), Style.bar(-3, 10, "red"));
        assertEquals("<red>" + "█".repeat(10) + "<dark_gray>", Style.bar(7, 10, "red"));
        assertEquals("<red>" + "<dark_gray>" + "█".repeat(4), Style.bar(Double.NaN, 4, "red"));
    }
}
