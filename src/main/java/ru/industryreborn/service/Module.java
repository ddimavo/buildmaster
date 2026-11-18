package ru.industryreborn.service;

import java.util.Locale;

/** Функции плагина, которые администратор может отключить целиком. */
public enum Module {
    ELECTRICITY("electricity", "Электричество и электросеть", "без него станки и отели работают без оплаты электричества и без подключения к станциям"),
    HOTELS("hotels", "Отели и номера", "аренда номеров, голограммы дверей, цены номеров"),
    JOBS("jobs", "Работа и зарплаты", "доска объявлений, вакансии, работники"),
    SERVICES("services", "Услуги и заказы мастерских", "переплавка, ремонт и другие услуги для игроков"),
    PRODUCTION("production", "Производство мастерских", "задания станкам, склад сырья, сундуки, улучшения"),
    ENCHANTING("enchanting", "Зачарования для станков", "заявки и работа стола зачарований (нужно производство)"),
    GOODS("goods", "Товары, витрины и связи", "склад товаров, продажа игрокам, связи между бизнесами"),
    PLAYER_PLOTS("player-plots", "Участки игроков", "регионы WorldGuard игроков: продажа и аренда"),
    TREASURY_PAYOUTS("treasury-payouts", "Субсидии и призы из казны", "выплаты из казны с лимитами и отчётом"),
    AUCTION("auction", "Аукцион казны", "выставление просроченных вещей на аукцион zAuctionHouse"),
    HTML_MENUS("html-menus", "HTML-меню (MC GUI)", "страницы вместо меню-сундуков"),
    AUTO_BALANCE("auto-balance", "Автокорректировка экономики", "автоматический расчёт цен, налогов и коэффициентов"),
    WEBHOOKS("webhooks", "Вебхуки", "статистика в Discord, XenForo, Invision"),
    PLAN("plan", "Интеграция с Plan", "показатели на панели Plan"),
    AUTO_BUILD("auto-build", "Автостроительство", "здание из шаблона ставится на пустой участок при создании"),
    HOLO_SHOP("holo-shop", "Торговля через голограммы", "голограммы-витрины с листанием и покупкой"),
    PLOT_AUCTION("plot-auction", "Аукцион участков", "продажа участков с торгами, ставками и голограммой лота");

    private final String key;
    private final String title;
    private final String description;

    Module(String key, String title, String description) {
        this.key = key;
        this.title = title;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public static Module parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (Module m : values()) {
            if (m.key.equals(v)) {
                return m;
            }
        }
        return null;
    }
}
