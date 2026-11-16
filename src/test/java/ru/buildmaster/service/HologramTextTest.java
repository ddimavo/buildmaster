package ru.buildmaster.service;

import static org.junit.jupiter.api.Assertions.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import ru.buildmaster.util.Text;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;

class HologramTextTest {

    private static String money(double v) {
        return v + "$";
    }

    private static Plot plot() {
        Plot p = new Plot("shop-1", "w", new Box(0, 0, 0, 9, 4, 9));
        p.category = Category.SHOP;
        return p;
    }

    private static String joined(Plot p) {
        return String.join("\n", HologramText.lines(p, HologramTextTest::money));
    }

    @Test
    void freePlotShowsPricesAndTypeButNoOwner() {
        Plot p = plot();
        p.price = 5000;
        p.rentPerDay = 120;
        String text = joined(p);
        assertTrue(text.contains("Магазин shop-1"));
        assertTrue(text.contains("Свободен"));
        assertTrue(text.contains("Продажа: <white>5000.0$"));
        assertTrue(text.contains("Аренда: <white>120.0$ / сутки"));
        assertFalse(text.contains("Управляющий"));
    }

    @Test
    void freePlotOmitsUnavailableOffers() {
        Plot p = plot();
        p.rentPerDay = 10;
        String text = joined(p);
        assertFalse(text.contains("Продажа"));
        assertTrue(text.contains("Аренда"));
    }

    @Test
    void occupiedPlotShowsNameOwnerAndDescription() {
        Plot p = plot();
        p.status = Plot.Status.RENTED;
        p.owner = UUID.randomUUID();
        p.ownerName = "Steve";
        p.name = "Лавка Стива";
        p.description = "Лучшие мечи и броня на всём сервере, скидки по пятницам";
        String text = joined(p);
        assertTrue(text.contains("Лавка Стива"));
        assertTrue(text.contains("Управляющий: <white>Steve"));
        assertTrue(text.contains("Лучшие мечи"));
        assertFalse(text.contains("Свободен"));
        assertFalse(text.contains("Продажа"));
    }

    @Test
    void frozenPlotIsMarked() {
        Plot p = plot();
        p.status = Plot.Status.RENTED;
        p.ownerName = "Steve";
        p.graceUntil = System.currentTimeMillis() + 100_000;
        assertTrue(joined(p).contains("Платёж просрочен"));
    }

    @Test
    void workshopHologramShowsElectricityLeftOrBlackout() {
        Plot p = plot();
        p.category = Category.WORKSHOP;
        p.status = Plot.Status.RENTED;
        p.ownerName = "Steve";
        p.account.restore(720, 360, 0);

        String lit = String.join("\n", HologramText.lines(p, HologramTextTest::money, 0L));
        assertTrue(lit.contains("Электричество"), lit);
        assertTrue(lit.contains("2 д."), lit);

        String dark = String.join("\n", HologramText.lines(p, HologramTextTest::money, 3 * 86_400L));
        assertTrue(dark.contains("Обесточено"), dark);
        assertFalse(dark.contains("Электричество"), dark);
    }

    @Test
    void usageModeHologramShowsPaymentMethodOrEmptyAccount() {
        Plot p = plot();
        p.category = Category.WORKSHOP;
        p.status = Plot.Status.RENTED;
        p.ownerName = "Steve";
        p.account.restore(50, 0, 0);
        String paid = String.join("\n", HologramText.lines(p, HologramTextTest::money, 0L, true));
        assertTrue(paid.contains("оплата по использованию"), paid);
        assertFalse(paid.contains("Обесточено"), paid);

        p.account.restore(0, 0, 0);
        String empty = String.join("\n", HologramText.lines(p, HologramTextTest::money, 0L, true));
        assertTrue(empty.contains("Обесточено"), empty);
    }

    @Test
    void bankAndPowerPlantHaveTheirOwnHolograms() {
        Plot bank = plot();
        bank.category = Category.BANK;
        String text = String.join("\n", HologramText.lines(bank, HologramTextTest::money));
        assertTrue(text.contains("Банк"), text);
        assertTrue(text.contains("Счета, налоги, казна"), text);
        assertFalse(text.contains("Свободен"), text);
        assertFalse(text.contains("Продажа"), text);

        Plot plant = plot();
        plant.category = Category.POWER_PLANT;
        plant.plantCapacity = 1500;
        String calm = String.join("\n", HologramText.lines(plant, HologramTextTest::money));
        assertTrue(calm.contains("Электростанция"), calm);
        assertTrue(calm.contains("1500.0 V/сут"), calm);
        assertFalse(calm.contains("Перегрузка"), calm);

        plant.plantMultiplier = 1.12;
        assertTrue(String.join("\n", HologramText.lines(plant, HologramTextTest::money)).contains("тариф ×1.12"));
    }

    @Test
    void disconnectedWorkshopHologramShowsTheCutOff() {
        Plot p = plot();
        p.category = Category.WORKSHOP;
        p.status = Plot.Status.RENTED;
        p.ownerName = "Steve";
        p.account.restore(500, 100, 0);
        String text = String.join("\n", HologramText.lines(p, HologramTextTest::money, 0L, false, "авария"));
        assertTrue(text.contains("Отключено от электростанции"), text);
        assertFalse(text.contains("Электричество:"), text);
    }

    @Test
    void nonWorkshopHasNoElectricityLine() {
        Plot p = plot();
        p.status = Plot.Status.RENTED;
        p.ownerName = "Steve";
        p.account.restore(0, 360, 0);
        String text = String.join("\n", HologramText.lines(p, HologramTextTest::money, 99_999L));
        assertFalse(text.contains("Обесточено"));
        assertFalse(text.contains("Электричество"));
    }

    @Test
    void userTextIsEscapedAgainstMiniMessageInjection() {
        Plot p = plot();
        p.status = Plot.Status.OWNED;
        p.ownerName = "<red>Hacker";
        p.name = "<click:run_command:/op me>Нажми";
        p.description = "<hover:show_text:'x'>текст";
        Component component = Text.mm(String.join("\n", HologramText.lines(p, HologramTextTest::money)));

        // теги из пользовательского текста не должны превращаться в события и цвета
        component.iterable(ComponentIteratorType.DEPTH_FIRST).forEach(c -> {
            assertNull(c.clickEvent(), "найдено click-событие");
            assertNull(c.hoverEvent(), "найдено hover-событие");
        });
        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        assertTrue(plain.contains("<click:run_command:/op me>Нажми"), plain);
        assertTrue(plain.contains("<red>Hacker"), plain);
    }
}
