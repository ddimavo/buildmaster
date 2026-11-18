package ru.industryreborn.items;

import org.bukkit.inventory.ItemStack;

/** Внешний поставщик предметов (Nexo). Реализация скрыта за интерфейсом, чтобы плагин работал и без Nexo. */
public interface ExternalItems {

    /** Поставщик установлен и его предметы загружены. */
    boolean available();

    /** Id предмета в поставщике либо null, если это не его предмет. */
    String idOf(ItemStack stack);

    /** Новый предмет по id либо null. */
    ItemStack build(String id);

    boolean exists(String id);

    /** Поставщика нет. */
    ExternalItems NONE = new ExternalItems() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String idOf(ItemStack stack) {
            return null;
        }

        @Override
        public ItemStack build(String id) {
            return null;
        }

        @Override
        public boolean exists(String id) {
            return false;
        }
    };
}
