package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;

/** Фильтрация, поиск и сортировка списка участков для меню. */
public final class PlotFilter {

    public enum Mode { FREE, MINE }

    public enum Sort {
        ID("номеру"), PRICE("цене покупки"), RENT("цене аренды"), SIZE("размеру");

        private final String title;

        Sort(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public final Mode mode;
    public Category category;
    public Sort sort = Sort.ID;
    public boolean descending;
    public String search = "";

    public PlotFilter(Mode mode) {
        this.mode = mode;
    }

    public PlotFilter copy() {
        PlotFilter f = new PlotFilter(mode);
        f.category = category;
        f.sort = sort;
        f.descending = descending;
        f.search = search;
        return f;
    }

    /** Следующая категория в цикле: все -> дом -> магазин -> ... -> все. */
    public Category nextCategory() {
        Category[] all = Category.values();
        if (category == null) {
            return all[0];
        }
        return category.ordinal() + 1 >= all.length ? null : all[category.ordinal() + 1];
    }

    public List<Plot> apply(Collection<Plot> plots, UUID viewer) {
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Plot> result = new ArrayList<>();
        for (Plot p : plots) {
            boolean match = mode == Mode.FREE ? p.isFree() && !p.restorePending && !p.category.isAdminOnly() : p.involves(viewer);
            if (!match || (category != null && p.category != category)) {
                continue;
            }
            if (!needle.isEmpty() && !p.id.toLowerCase(Locale.ROOT).contains(needle)
                    && !p.displayName().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            result.add(p);
        }
        Comparator<Plot> cmp = switch (sort) {
            case ID -> Comparator.comparing(p -> p.id.toLowerCase(Locale.ROOT));
            // неустановленная цена (-1) уходит в конец при сортировке по возрастанию
            case PRICE -> Comparator.comparingDouble(p -> p.price < 0 ? Double.MAX_VALUE : p.price);
            case RENT -> Comparator.comparingDouble(p -> p.rentPerDay < 0 ? Double.MAX_VALUE : p.rentPerDay);
            case SIZE -> Comparator.comparingLong(p -> p.box.volume());
        };
        result.sort(descending ? cmp.reversed() : cmp);
        return result;
    }
}
