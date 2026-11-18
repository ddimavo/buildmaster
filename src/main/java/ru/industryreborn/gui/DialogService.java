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

    /** Показ окна игроку; тесты подменяют. submit получает значения полей: String, Double или Boolean. */
    public interface Backend {

        boolean available();

        void show(Player player, Spec spec, Consumer<Map<String, Object>> submit, Runnable cancel);
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

    /** Список полей окна для тестов и расширений. */
    public static List<Field> fields(Field... fields) {
        return new ArrayList<>(List.of(fields));
    }
}
