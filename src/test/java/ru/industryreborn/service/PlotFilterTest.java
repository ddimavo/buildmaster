package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;

class PlotFilterTest {

    private static final UUID ME = UUID.randomUUID();

    private static Plot plot(String id, Category c, double price, double rent, int size) {
        Plot p = new Plot(id, "w", new Box(0, 0, 0, size - 1, 0, 0));
        p.category = c;
        p.price = price;
        p.rentPerDay = rent;
        return p;
    }

    private static List<String> ids(List<Plot> plots) {
        return plots.stream().map(p -> p.id).toList();
    }

    private static List<Plot> sample() {
        List<Plot> all = new ArrayList<>();
        all.add(plot("b", Category.SHOP, 500, 50, 10));
        all.add(plot("a", Category.HOUSE, 100, -1, 30));
        all.add(plot("c", Category.SHOP, -1, 20, 20));
        Plot taken = plot("d", Category.SHOP, 10, 10, 5);
        taken.status = Plot.Status.RENTED;
        taken.owner = ME;
        all.add(taken);
        Plot pending = plot("e", Category.SHOP, 1, 1, 1);
        pending.restorePending = true;
        all.add(pending);
        return all;
    }

    @Test
    void freeModeHidesOccupiedAndRestoringPlots() {
        PlotFilter f = new PlotFilter(PlotFilter.Mode.FREE);
        assertEquals(List.of("a", "b", "c"), ids(f.apply(sample(), ME)));
    }

    @Test
    void mineModeShowsOnlyMembership() {
        PlotFilter f = new PlotFilter(PlotFilter.Mode.MINE);
        assertEquals(List.of("d"), ids(f.apply(sample(), ME)));
        assertTrue(f.apply(sample(), UUID.randomUUID()).isEmpty());
    }

    @Test
    void filtersByCategory() {
        PlotFilter f = new PlotFilter(PlotFilter.Mode.FREE);
        f.category = Category.SHOP;
        assertEquals(List.of("b", "c"), ids(f.apply(sample(), ME)));
    }

    @Test
    void sortsByPriceWithUnsetPricesLast() {
        PlotFilter f = new PlotFilter(PlotFilter.Mode.FREE);
        f.sort = PlotFilter.Sort.PRICE;
        assertEquals(List.of("a", "b", "c"), ids(f.apply(sample(), ME)));
        f.descending = true;
        assertEquals(List.of("c", "b", "a"), ids(f.apply(sample(), ME)));
    }

    @Test
    void sortsByRentAndSize() {
        PlotFilter f = new PlotFilter(PlotFilter.Mode.FREE);
        f.sort = PlotFilter.Sort.RENT;
        assertEquals(List.of("c", "b", "a"), ids(f.apply(sample(), ME)));
        f.sort = PlotFilter.Sort.SIZE;
        assertEquals(List.of("b", "c", "a"), ids(f.apply(sample(), ME)));
    }

    @Test
    void searchMatchesIdAndNameIgnoringCase() {
        List<Plot> all = sample();
        all.get(0).name = "Кузница Гефеста";
        PlotFilter f = new PlotFilter(PlotFilter.Mode.FREE);
        f.search = "кузн";
        assertEquals(List.of("b"), ids(f.apply(all, ME)));
        f.search = "A";
        assertEquals(List.of("a"), ids(f.apply(all, ME)));
        f.search = "нет такого";
        assertTrue(f.apply(all, ME).isEmpty());
    }

    @Test
    void categoryCycleVisitsAllThenResets() {
        PlotFilter f = new PlotFilter(PlotFilter.Mode.FREE);
        int steps = 0;
        do {
            f.category = f.nextCategory();
            steps++;
        } while (f.category != null && steps < 20);
        assertEquals(Category.values().length + 1, steps);
        assertNull(f.category);
    }

    @Test
    void copyIsIndependent() {
        PlotFilter f = new PlotFilter(PlotFilter.Mode.FREE);
        f.search = "x";
        PlotFilter c = f.copy();
        c.search = "y";
        c.category = Category.HOUSE;
        assertEquals("x", f.search);
        assertNull(f.category);
    }
}
