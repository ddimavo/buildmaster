package ru.industryreborn.items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Request;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.Module;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.util.Text;

/**
 * Заявки игроков на рецепты станков. Управляющий или работник мастерской составляет рецепт (станок, время, до 4 входов и результат из существующих предметов:
 * обычных, плагина и Nexo), администратор проверяет и одобряет или отклоняет с причиной. Одобренный рецепт записывается в recipes/_player.yml,
 * сразу изучается мастерской-автором, остальным нужен чертёж (его продаёт магазин администрации по назначенной цене или выдаёт администратор).
 */
public final class RecipeRequests {

    /** Предмет и количество: ключ «IRON_INGOT», «ir:steel», «nexo:steel». */
    public record Part(String key, int amount) { }

    /** Рецепт, как его составил игрок. */
    public record Spec(String station, double seconds, List<Part> inputs, Part output) {

        /** Текст для хранения в заявке: «станок|секунды|ключ*кол;ключ*кол|ключ*кол». */
        public String serialize() {
            StringBuilder in = new StringBuilder();
            for (Part p : inputs) {
                in.append(in.length() == 0 ? "" : ";").append(p.key()).append('*').append(p.amount());
            }
            return station + "|" + seconds + "|" + in + "|" + output.key() + "*" + output.amount();
        }

        /** Разбор текста заявки; null - повреждён. */
        public static Spec parse(String text) {
            try {
                String[] a = text.split("\\|", -1);
                if (a.length != 4) {
                    return null;
                }
                List<Part> inputs = new ArrayList<>();
                for (String p : a[2].split(";")) {
                    inputs.add(part(p));
                }
                return new Spec(a[0], Double.parseDouble(a[1]), inputs, part(a[3]));
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static Part part(String s) {
            int star = s.lastIndexOf('*');
            return new Part(s.substring(0, star), Integer.parseInt(s.substring(star + 1)));
        }
    }

    private final IndustryRebornPlugin plugin;

    public RecipeRequests(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ настройки

    public boolean enabled() {
        return plugin.customItems().enabled() && plugin.getConfig().getBoolean("recipe-requests.enabled", true)
                && plugin.modules().enabled(Module.PRODUCTION);
    }

    private int maxPending() {
        return Math.max(1, plugin.getConfig().getInt("recipe-requests.max-pending-per-plot", 3));
    }

    private int maxRecipes() {
        return Math.max(1, plugin.getConfig().getInt("recipe-requests.max-recipes-per-plot", 10));
    }

    private double minSeconds() {
        return Math.max(0.1, plugin.getConfig().getDouble("recipe-requests.min-seconds", 1));
    }

    private double maxSeconds() {
        return Math.max(minSeconds(), plugin.getConfig().getDouble("recipe-requests.max-seconds", 600));
    }

    public double defaultBlueprintPrice() {
        return Math.max(0, plugin.getConfig().getDouble("recipe-requests.default-blueprint-price", 0));
    }

    public static List<String> stations() {
        return List.of("furnace", "blast", "smoker", "craft");
    }

    // ------------------------------------------------------------ описание

    /** Одна часть рецепта для людей: «3 × Железный слиток». */
    public String partText(Part p) {
        var ref = ItemRef.parse(p.key());
        return p.amount() + "×" + (ref == null ? p.key() : plugin.customItems().displayName(ref));
    }

    /** Рецепт для людей: «Плавка в печи, 30 с: 3×iron ingot + 1×coal → 2×Стальной слиток». */
    public String describe(Spec spec) {
        List<String> in = new ArrayList<>();
        spec.inputs().forEach(p -> in.add(partText(p)));
        return RecipeBook.familyTitle(spec.station()) + ", " + spec.seconds() + " с: " + String.join(" + ", in) + " → " + partText(spec.output());
    }

    // ------------------------------------------------------------ проверки

    /** Может ли игрок подавать заявки за мастерскую: управляющий и работники. */
    public boolean mayRequest(Player player, Plot plot) {
        return plugin.blueprints().mayLearn(player, plot);
    }

    /** Заявки мастерской, ждущие решения. */
    public List<Request> pending(String plotId) {
        List<Request> list = new ArrayList<>();
        for (Request r : plugin.moderation().pending()) {
            if (r.type == Request.Type.RECIPE && r.plotId.equalsIgnoreCase(plotId)) {
                list.add(r);
            }
        }
        return list;
    }

    /** Сколько рецептов игроков создано мастерской. */
    public int authored(String plotId) {
        int n = 0;
        for (RecipeBook.Recipe r : plugin.recipes().customRecipes()) {
            if (plotId.equalsIgnoreCase(plugin.recipes().author(r.id()))) {
                n++;
            }
        }
        return n;
    }

    private static String sameSet(Spec s) {
        Set<String> in = new java.util.TreeSet<>();
        s.inputs().forEach(p -> in.add(p.key() + "*" + p.amount()));
        return s.station() + "|" + in + "|" + s.output().key() + "*" + s.output().amount();
    }

    /** Проверка рецепта. Возвращает ошибку или null. Лимиты заявок не проверяются (см. {@link #submit}). */
    public String validate(Spec spec) {
        if (!stations().contains(spec.station())) {
            return "Станок: " + String.join(", ", stations()) + ".";
        }
        if (Double.isNaN(spec.seconds()) || spec.seconds() < minSeconds() || spec.seconds() > maxSeconds()) {
            return "Время партии: от " + minSeconds() + " до " + maxSeconds() + " с.";
        }
        if (spec.inputs().isEmpty() || spec.inputs().size() > 4) {
            return "Нужно от 1 до 4 входов.";
        }
        Set<String> keys = new HashSet<>();
        for (Part p : spec.inputs()) {
            String error = checkPart(p, "вход");
            if (error != null) {
                return error;
            }
            if (!keys.add(p.key())) {
                return "Предмет «" + p.key() + "» указан во входах дважды: сложите количества.";
            }
        }
        String error = checkPart(spec.output(), "результат");
        if (error != null) {
            return error;
        }
        if (keys.contains(spec.output().key())) {
            return "Результат не может быть и входом рецепта.";
        }
        for (RecipeBook.Recipe r : plugin.recipes().customRecipes()) {
            if (specOf(r) != null && sameSet(specOf(r)).equals(sameSet(spec))) {
                return "Такой рецепт уже есть: «" + r.id().substring(7) + "».";
            }
        }
        return null;
    }

    private String checkPart(Part p, String what) {
        if (p.key().contains("|") || p.key().contains(";") || p.key().contains("*")) {
            return "Недопустимые знаки в названии предмета (" + what + ").";
        }
        var ref = ItemRef.parse(p.key());
        if (ref == null || !plugin.customItems().known(ref)) {
            return "Неизвестный предмет «" + p.key() + "» (" + what + ").";
        }
        if (p.amount() < 1 || p.amount() > 64) {
            return "Количество от 1 до 64 (" + what + " «" + p.key() + "»).";
        }
        return null;
    }

    /** Рецепт каталога в виде Spec (для сравнения на дубликаты): вариантные входы и несколько вариантов не сопоставляются. */
    private Spec specOf(RecipeBook.Recipe r) {
        List<Part> in = new ArrayList<>();
        for (RecipeBook.Ingredient ing : r.inputs()) {
            if (ing.options().size() + ing.customs().size() != 1) {
                return null;
            }
            in.add(new Part(ing.options().isEmpty() ? ing.customs().get(0) : ing.options().get(0).name(), ing.count()));
        }
        return new Spec(r.family(), r.seconds(), in, new Part(r.outputKey(), r.outputAmount()));
    }

    // ------------------------------------------------------------ подача

    /**
     * Подаёт заявку (администратору рецепт создаётся сразу). Возвращает ошибку, null - заявка подана, пустую строку - рецепт создан сразу.
     */
    public String submit(Player player, Plot plot, Spec spec) {
        if (!enabled()) {
            return "Заявки на рецепты сейчас отключены.";
        }
        if (plot == null || plot.category != Category.WORKSHOP || plot.isFree()) {
            return "Рецепты заводит мастерская: укажите её или встаньте в неё.";
        }
        boolean admin = player.hasPermission("industryreborn.admin");
        if (!mayRequest(player, plot) && !admin) {
            return "Заявку подаёт управляющий или работник мастерской.";
        }
        if (plugin.service().isLocked(plot)) {
            return "Мастерская сейчас обрабатывается, попробуйте позже.";
        }
        String error = validate(spec);
        if (error != null) {
            return error;
        }
        if (authored(plot.id) >= maxRecipes()) {
            return "У мастерской уже " + maxRecipes() + " своих рецептов (предел).";
        }
        if (admin) {
            String[] err = new String[1];
            if (create(plot, spec, defaultBlueprintPrice(), player.getName(), err) == null) {
                return err[0];
            }
            return "";
        }
        if (pending(plot.id).size() >= maxPending()) {
            return "Заявок на рассмотрении уже " + maxPending() + ": дождитесь решения или отмените лишние (/district recipe cancel).";
        }
        for (Request r : pending(plot.id)) {
            if (r.text.equals(spec.serialize())) {
                return "Такая заявка уже ждёт решения администратора.";
            }
        }
        plugin.moderation().submitRecipe(player, plot, spec);
        return null;
    }

    /** Создаёт рецепт и отдаёт его мастерской-автору. Возвращает id или null и ошибку в error[0]. */
    public String create(Plot plot, Spec spec, double blueprintPrice, String by, String[] error) {
        String slug = plot.id + "_" + spec.output().key().replace(':', '_');
        List<String[]> inputs = new ArrayList<>();
        spec.inputs().forEach(p -> inputs.add(new String[] {p.key(), String.valueOf(p.amount())}));
        String id = plugin.recipes().addPlayerRecipe(slug, plot.id, spec.station(), spec.seconds(), inputs,
                new String[] {spec.output().key(), String.valueOf(spec.output().amount())}, blueprintPrice, error);
        if (id == null) {
            return null;
        }
        plot.learnedRecipes.add(id);
        plugin.plots().save();
        plugin.journal().add(plot.id, null, by, Journal.Action.FACTORY, "Новый рецепт «" + id.substring(7) + "»: " + describe(spec));
        return id;
    }

    // ------------------------------------------------------------ решение

    /**
     * Исполняет одобренную заявку. Возвращает причину, по которой заявка устарела (рецепт не создан), либо null - рецепт создан и изучен мастерской.
     */
    public String approve(Request r, String admin, double blueprintPrice) {
        Plot plot = plugin.plots().get(r.plotId);
        Spec spec = Spec.parse(r.text);
        if (plot == null || plot.category != Category.WORKSHOP || plot.isFree()) {
            return "мастерская больше недоступна";
        }
        boolean stillTheirs = plot.isOwner(r.requester)
                || plugin.jobs().employeesOf(plot.id).stream().anyMatch(e -> e.player.equals(r.requester));
        if (!stillTheirs) {
            return "автор заявки больше не работает в мастерской";
        }
        if (spec == null) {
            return "заявка повреждена";
        }
        String error = validate(spec);
        if (error != null) {
            return error;
        }
        if (authored(plot.id) >= maxRecipes()) {
            return "у мастерской уже предельное число своих рецептов";
        }
        String[] err = new String[1];
        String id = create(plot, spec, blueprintPrice, admin, err);
        return id == null ? err[0] : null;
    }

    /** Рецепт, созданный по этой заявке (последний рецепт мастерской с таким же составом), либо null. */
    public String createdBy(Request r) {
        Spec spec = Spec.parse(r.text);
        if (spec == null) {
            return null;
        }
        for (RecipeBook.Recipe rec : plugin.recipes().customRecipes()) {
            if (r.plotId.equalsIgnoreCase(plugin.recipes().author(rec.id())) && specOf(rec) != null
                    && sameSet(specOf(rec)).equals(sameSet(spec))) {
                return rec.id();
            }
        }
        return null;
    }

    /** Разбор аргумента вида «IRON_INGOT*3», «ir:steel*2», «COAL» в часть рецепта. */
    public static Part parsePart(String token) {
        int star = token.lastIndexOf('*');
        String key = star < 0 ? token : token.substring(0, star);
        int amount = 1;
        if (star >= 0) {
            try {
                amount = Integer.parseInt(token.substring(star + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        var ref = ItemRef.parse(key);
        return ref == null ? null : new Part(ref.key(), amount);
    }

    public static String normalizeStation(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "печь", "furnace" -> "furnace";
            case "плавильная", "blast", "доменная" -> "blast";
            case "коптильня", "smoker" -> "smoker";
            case "наковальня", "craft", "сборка" -> "craft";
            default -> s.toLowerCase(Locale.ROOT);
        };
    }
}
