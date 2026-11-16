package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Журнал событий по участкам. Любое действие администратора записывается с причиной,
 * игрок может просмотреть записи о себе и спустя время.
 */
public final class Journal {

    public enum Action {
        CREATED("Участок создан"),
        BOUGHT("Покупка"),
        RENTED("Аренда"),
        EXTENDED("Продление аренды"),
        RELEASED("Отказ от участка"),
        EXPIRED("Окончание аренды"),
        GRACE_STARTED("Отсрочка платежа"),
        EVICTED("Выселение"),
        SEIZED("Изъятие участка"),
        RESET("Сброс здания"),
        NAME_APPROVED("Название одобрено"),
        NAME_REJECTED("Название отклонено"),
        DESC_APPROVED("Описание одобрено"),
        DESC_REJECTED("Описание отклонено"),
        STORAGE_ARRESTED("Вещи арестованы"),
        STORAGE_RELEASED("Арест вещей снят"),
        STORAGE_DELETED("Арестованные вещи удалены"),
        STORAGE_AUCTION("Вещи переданы на аукцион"),
        TREASURY("Операция с казной"),
        ACCOUNT_DEPOSIT("Пополнение счёта"),
        ACCOUNT_WITHDRAW("Снятие со счёта"),
        ACCOUNT_REFUND("Возврат остатка счёта"),
        ACCOUNT_PENALTY("Штраф при изъятии"),
        ACCOUNT_GIFT("Подарок на счёт"),
        TARIFF("Изменение тарифа"),
        RIGHTS("Изменение прав");

        private final String title;

        Action(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public record Entry(long time, String plotId, UUID subject, String actor, Action action, String reason) {

        String toLine() {
            return time + "\t" + esc(plotId) + "\t" + (subject == null ? "-" : subject) + "\t" + esc(actor)
                    + "\t" + action.name() + "\t" + esc(reason);
        }

        static Entry fromLine(String line) {
            String[] p = line.split("\t", -1);
            if (p.length != 6) {
                throw new IllegalArgumentException("Неверная строка журнала: " + line);
            }
            return new Entry(Long.parseLong(p[0]), unesc(p[1]), p[2].equals("-") ? null : UUID.fromString(p[2]),
                    unesc(p[3]), Action.valueOf(p[4]), unesc(p[5]));
        }
    }

    private static final int MAX_IN_MEMORY = 20_000;

    private final File file;
    private final List<Entry> entries = new ArrayList<>();

    public Journal(File file) {
        this.file = file;
    }

    public void load() throws IOException {
        entries.clear();
        if (!file.exists()) {
            return;
        }
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                entries.add(Entry.fromLine(line));
            } catch (RuntimeException ignored) {
                // повреждённая строка пропускается
            }
        }
        while (entries.size() > MAX_IN_MEMORY) {
            entries.remove(0);
        }
    }

    /** Добавляет запись и дописывает её в файл. */
    public Entry add(String plotId, UUID subject, String actor, Action action, String reason) {
        Entry entry = new Entry(System.currentTimeMillis(), plotId, subject, actor, action,
                reason == null ? "" : reason);
        entries.add(entry);
        if (entries.size() > MAX_IN_MEMORY) {
            entries.remove(0);
        }
        try {
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), entry.toLine() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать журнал", e);
        }
        return entry;
    }

    /** Записи о игроке, новые первыми. */
    public List<Entry> forPlayer(UUID player) {
        List<Entry> result = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (player.equals(entries.get(i).subject())) {
                result.add(entries.get(i));
            }
        }
        return result;
    }

    public List<Entry> forPlot(String plotId) {
        List<Entry> result = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).plotId().equalsIgnoreCase(plotId)) {
                result.add(entries.get(i));
            }
        }
        return result;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
