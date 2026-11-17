package ru.industryreborn.model;

/** Станки: блоки, для которых действуют лимиты по типам участков и (для платных) электричество. */
public enum Station {
    CRAFTING("верстак", "crafting", null),
    FURNACE("печь", "furnace", "smelter"),
    ANVIL("наковальня", "anvil", "anvil"),
    ALCHEMY("варочная стойка", "other", "alchemy"),
    ENCHANTING("стол зачарований", "other", "enchanting"),
    OTHER("станок", "other", null);

    private final String title;
    private final String limitKey;
    private final String tariffKey;

    Station(String title, String limitKey, String tariffKey) {
        this.title = title;
        this.limitKey = limitKey;
        this.tariffKey = tariffKey;
    }

    public String title() {
        return title;
    }

    /** Ключ в station-limits.<тип участка>.<ключ>. Печи всех видов делят один лимит, прочие станки - тоже. */
    public String limitKey() {
        return limitKey;
    }

    /** Ключ коэффициента тарифа; null - станок не тарифицируется. */
    public String tariffKey() {
        return tariffKey;
    }

    /** Определяет станок по имени материала; null - это не станок. */
    public static Station fromName(String materialName) {
        return switch (materialName) {
            case "CRAFTING_TABLE" -> CRAFTING;
            case "FURNACE", "BLAST_FURNACE", "SMOKER" -> FURNACE;
            case "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL" -> ANVIL;
            case "BREWING_STAND" -> ALCHEMY;
            case "ENCHANTING_TABLE" -> ENCHANTING;
            case "GRINDSTONE", "SMITHING_TABLE", "LOOM", "STONECUTTER", "CARTOGRAPHY_TABLE" -> OTHER;
            default -> null;
        };
    }
}
