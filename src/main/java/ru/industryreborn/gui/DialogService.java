package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.util.Text;

/**
 * Окна ввода (диалоги Paper) вместо ввода в чат: текст, число с ползунком, переключатель, кнопки «подтвердить» и «отмена».
 * Если диалогов нет (старый сервер, выключено в config.yml: gui.dialogs) - прежний ввод в чат для первого поля.
 */
public final class DialogService {

    public enum Kind { TEXT, NUMBER, BOOL }

    /** Поле окна. Для числа min/max/step задают ползунок; для текста max - длину. */
    public record Field(String key, Kind kind, String label, String initial, double min, double max, double step, int maxLength) {

        public static Field text(String key, String label, String initial, int maxLength) {
            return new Field(key, Kind.TEXT, label, initial == null ? "" : initial, 0, 0, 0, maxLength);
        }

        public static Field number(String key, String label, double initial, double min, double max, double step) {
            return new Field(key, Kind.NUMBER, label, String.valueOf(initial), min, max, step, 0);
        }

        public static Field bool(String key, String label, boolean initial) {
            return new Field(key, Kind.BOOL, label, String.valueOf(initial), 0, 0, 0, 0);
        }
    }

    /** Содержимое окна: заголовок, строки текста (MiniMessage), поля, подписи кнопок. */
    public record Spec(String title, List<String> body, List<Field> fields, String confirm, String cancel) {

        public Spec(String title, List<String> body, List<Field> fields) {
            this(title, body, fields, "Подтвердить", "Отмена");
        }
    }

    /** Кнопка окна выбора: id возвращается при нажатии, color - цвет подписи (MiniMessage-имя). */
    public record Choice(String id, String label, String color) { }

    /**
     * Окно-степпер: число меняется кнопками, при каждом нажатии окно показывается заново с пересчитанным текстом
     * (поля окна работают на клиенте, поэтому «живой» пересчёт возможен только так).
     *
     * @param body    строки окна для текущего значения (цена, баланс после операции...)
     * @param valid   можно ли подтвердить это значение (нет - кнопки «подтвердить» не будет)
     * @param format  подпись значения на кнопках («7 сут.», «500$»)
     */
    public record Stepper(String title, double min, double max, double initial, double small, double large, List<Double> presets,
                          java.util.function.DoubleFunction<List<String>> body, java.util.function.DoublePredicate valid,
                          java.util.function.DoubleFunction<String> format, String confirm) { }

    /** Показ окна игроку; тесты подменяют. submit получает значения полей: String, Double или Boolean. */
    public interface Backend {

        boolean available();

        void show(Player player, Spec spec, Consumer<Map<String, Object>> submit, Runnable cancel);

        /** Окно с набором кнопок (без полей); choose получает id нажатой кнопки, cancel - выход. */
        void showChoices(Player player, String title, List<String> body, List<Choice> choices, Consumer<String> choose, Runnable cancel);
    }

    private final IndustryRebornPlugin plugin;
    private Backend backend;
    private boolean probed;

    public DialogService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    /** Только для тестов. */
    public void setBackend(Backend backend) {
        this.backend = backend;
        this.probed = true;
    }

    private Backend backend() {
        if (!probed) {
            probed = true;
            try {
                Class.forName("io.papermc.paper.dialog.Dialog");
                backend = new PaperDialogBackend(plugin);
            } catch (ClassNotFoundException | LinkageError e) {
                backend = null;
            }
        }
        return backend;
    }

    public boolean available() {
        String mode = plugin.getConfig().getString("gui.dialogs", "auto").toLowerCase(Locale.ROOT);
        Backend b = backend();
        return !mode.equals("off") && b != null && b.available();
    }

    // ------------------------------------------------------------ общий показ

    public void show(Player player, Spec spec, Consumer<Map<String, Object>> submit, Runnable cancel) {
        if (available()) {
            player.closeInventory();
            try {
                backend().show(player, spec, submit, cancel == null ? () -> { } : cancel);
                return;
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().warning("Окно ввода не открылось, ввод в чате: " + e);
            }
        }
        fallback(player, spec, submit, cancel);
    }

    /** Запасной вариант: первое поле вводится в чат, остальные берут начальные значения. */
    private void fallback(Player player, Spec spec, Consumer<Map<String, Object>> submit, Runnable cancel) {
        Field first = spec.fields().isEmpty() ? null : spec.fields().get(0);
        if (first == null) {
            submit.accept(new LinkedHashMap<>());
            return;
        }
        String hint = plain(spec.title()) + ": " + plain(first.label())
                + (first.kind() == Kind.NUMBER ? " (число от " + fmt(first.min()) + " до " + fmt(first.max()) + ")" : first.kind() == Kind.BOOL ? " (да или нет)" : "");
        plugin.chatPrompt().askChat(player, hint, text -> {
            if (text.equalsIgnoreCase("отмена")) {
                if (cancel != null) {
                    cancel.run();
                }
                return;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (Field f : spec.fields()) {
                values.put(f.key(), initial(f));
            }
            switch (first.kind()) {
                case TEXT -> values.put(first.key(), text);
                case NUMBER -> {
                    try {
                        double v = Double.parseDouble(text.replace(',', '.'));
                        values.put(first.key(), Math.max(first.min(), Math.min(first.max(), v)));
                    } catch (NumberFormatException e) {
                        Text.err(player, "Нужно число.");
                        if (cancel != null) {
                            cancel.run();
                        }
                        return;
                    }
                }
                case BOOL -> values.put(first.key(), text.equalsIgnoreCase("да") || text.equalsIgnoreCase("вкл") || text.equalsIgnoreCase("true"));
                default -> { }
            }
            submit.accept(values);
        });
    }

    static Object initial(Field f) {
        return switch (f.kind()) {
            case TEXT -> f.initial();
            case NUMBER -> {
                try {
                    yield Double.parseDouble(f.initial());
                } catch (NumberFormatException e) {
                    yield f.min();
                }
            }
            case BOOL -> Boolean.parseBoolean(f.initial());
        };
    }

    private static String plain(String mini) {
        return mini == null ? "" : mini.replaceAll("<[^>]+>", "");
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------ удобные формы

    /** Окно с одним текстовым полем. «Отмена» в окне или в чате ничего не вызывает. */
    public void text(Player player, String title, String label, String initial, int maxLength, Consumer<String> onSubmit) {
        text(player, title, List.of(), label, initial, maxLength, onSubmit, null);
    }

    public void text(Player player, String title, List<String> body, String label, String initial, int maxLength, Consumer<String> onSubmit, Runnable onCancel) {
        show(player, new Spec(title, body, List.of(Field.text("value", label, initial, maxLength))),
                values -> onSubmit.accept(String.valueOf(values.get("value")).trim()), onCancel);
    }

    /** Окно с числом (ползунок). */
    public void number(Player player, String title, List<String> body, String label, double initial, double min, double max, double step,
                       Consumer<Double> onSubmit, Runnable onCancel) {
        show(player, new Spec(title, body, List.of(Field.number("value", label, initial, min, max, step))),
                values -> onSubmit.accept(((Number) values.get("value")).doubleValue()), onCancel);
    }

    /** Окно с суммой: поле ввода (не ползунок), число с точкой или запятой. */
    public void amount(Player player, String title, List<String> body, String label, Consumer<Double> onSubmit, Runnable onCancel) {
        show(player, new Spec(title, body, List.of(Field.text("value", label, "", 12))), values -> {
            try {
                double v = Double.parseDouble(String.valueOf(values.get("value")).trim().replace(',', '.'));
                if (v <= 0 || Double.isNaN(v) || Double.isInfinite(v)) {
                    throw new NumberFormatException();
                }
                onSubmit.accept(v);
            } catch (NumberFormatException e) {
                Text.err(player, "Нужно число больше нуля.");
                if (onCancel != null) {
                    onCancel.run();
                }
            }
        }, onCancel);
    }

    // ------------------------------------------------------------ степпер

    /**
     * Число кнопками с живым пересчётом. onManual - необязательная кнопка «Ввести число…» (например, окно с полем ввода).
     * Без окон Paper число вводится в чат.
     */
    public void stepper(Player player, Stepper s, Consumer<Double> onSubmit, Runnable onManual, Runnable onCancel) {
        step(player, s, Math.max(s.min(), Math.min(s.max(), s.initial())), onSubmit, onManual, onCancel);
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100) / 100.0);
    }

    private void step(Player player, Stepper s, double value, Consumer<Double> onSubmit, Runnable onManual, Runnable onCancel) {
        if (!available()) {
            fallback(player, new Spec(s.title(), s.body().apply(value), List.of(Field.number("value", "Значение", value, s.min(), s.max(), s.small()))),
                    values -> {
                        double v = ((Number) values.get("value")).doubleValue();
                        if (s.valid().test(v)) {
                            onSubmit.accept(v);
                        } else {
                            Text.err(player, "Это значение нельзя подтвердить.");
                            if (onCancel != null) {
                                onCancel.run();
                            }
                        }
                    }, onCancel);
            return;
        }
        List<Choice> choices = new ArrayList<>();
        double[] deltas = Math.abs(s.large() - s.small()) < 1e-9 ? new double[] {-s.small(), s.small()}
                : new double[] {-s.large(), -s.small(), s.small(), s.large()};
        for (double d : deltas) {
            if (d == 0 || value + d < s.min() - 1e-9 || value + d > s.max() + 1e-9) {
                continue;
            }
            choices.add(new Choice("d:" + num(d), (d > 0 ? "+" : "−") + s.format().apply(Math.abs(d)), d > 0 ? "green" : "red"));
        }
        for (double p : s.presets()) {
            if (p >= s.min() - 1e-9 && p <= s.max() + 1e-9 && Math.abs(p - value) > 1e-9) {
                choices.add(new Choice("p:" + num(p), s.format().apply(p), "aqua"));
            }
        }
        if (onManual != null) {
            choices.add(new Choice("manual", "Ввести число…", "gray"));
        }
        if (s.valid().test(value)) {
            choices.add(new Choice("ok", s.confirm() + " · " + s.format().apply(value), "green"));
        }
        player.closeInventory();
        try {
            backend().showChoices(player, s.title(), s.body().apply(value), choices, id -> {
                if (id.equals("ok")) {
                    onSubmit.accept(value);
                } else if (id.equals("manual")) {
                    onManual.run();
                } else if (id.startsWith("d:")) {
                    step(player, s, Math.max(s.min(), Math.min(s.max(), value + Double.parseDouble(id.substring(2)))), onSubmit, onManual, onCancel);
                } else if (id.startsWith("p:")) {
                    step(player, s, Double.parseDouble(id.substring(2)), onSubmit, onManual, onCancel);
                }
            }, onCancel == null ? () -> { } : onCancel);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Окно ввода не открылось, ввод в чате: " + e);
            fallback(player, new Spec(s.title(), s.body().apply(value), List.of(Field.number("value", "Значение", value, s.min(), s.max(), s.small()))),
                    values -> onSubmit.accept(((Number) values.get("value")).doubleValue()), onCancel);
        }
    }

    /** Список полей окна для тестов и расширений. */
    public static List<Field> fields(Field... fields) {
        return new ArrayList<>(List.of(fields));
    }
}
