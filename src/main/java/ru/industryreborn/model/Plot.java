package ru.industryreborn.model;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Участок: область мира со зданием, которое можно купить или арендовать. */
public final class Plot {

    public enum Status { FREE, OWNED, RENTED }

    /** ADMIN - участок администрации; PLAYER - регион WorldGuard игрока, который он продаёт или сдаёт. */
    public enum Kind { ADMIN, PLAYER }

    /** Участки игроков становятся видны только после одобрения администратором. */
    public enum Approval { APPROVED, PENDING }

    /** FULL - здание полностью возвращается к исходному виду; ITEMS_ONLY - только забираются вещи игроков. */
    public enum RestoreMode { FULL, ITEMS_ONLY, RETURN }

    /** Состояние станка мастерской: назначенный рецепт, накопленное время партии и продукция, ещё не выложенная в сундук. */
    public static final class MachineState {
        public String recipe;
        public double progress;
        public int output;
    }

    /** Предложение услуги: управляющий включает его и назначает цену за единицу работы. */
    public static final class Offer {
        public boolean enabled;
        public double price;

        public Offer(boolean enabled, double price) {
            this.enabled = enabled;
            this.price = price;
        }
    }

    /** Права на участке. Владелец имеет все. */
    public enum Perm {
        BUILD("Строительство"), CONTAINERS("Контейнеры"), STATIONS("Станки");

        private final String title;

        Perm(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        public static Perm parse(String s) {
            if (s == null) {
                return null;
            }
            String v = s.trim().toLowerCase(java.util.Locale.ROOT);
            for (Perm p : values()) {
                if (p.name().toLowerCase(java.util.Locale.ROOT).equals(v) || p.title.toLowerCase(java.util.Locale.ROOT).equals(v)) {
                    return p;
                }
            }
            return null;
        }
    }

    /** Именованная часть здания (фасад, крыша, интерьер...), внутри участка. */

    public final String id;
    public final String world;
    public final Box box;

    /** Цена покупки; отрицательная - участок не продаётся. */
    public double price = -1;
    /** Аренда за сутки; отрицательная - участок не сдаётся. */
    public double rentPerDay = -1;

    /** Тип участка (дом, магазин...). */
    public Category category = Category.SHOP;
    /** Администратор, создавший участок: он остаётся высшим владельцем. */
    public UUID createdBy;
    /** Одобренные администратором название и описание (публикуются на голограмме). */
    public String name;
    public String description;
    /** Точка голограммы (центр). */
    public boolean hasHolo;
    public double holoX, holoY, holoZ;
    /** Сбрасывать здание при освобождении (решает владелец). */
    public boolean resetOnRelease = true;
    /** До какого момента длится отсрочка платежа; 0 - отсрочки нет. В это время участок заморожен. */
    public long graceUntil;

    public Status status = Status.FREE;
    public UUID owner;
    public String ownerName;
    public long rentedUntil;
    public boolean autoRenew;

    /** Права, выданные управляющим отдельным игрокам. */
    public final Map<UUID, EnumSet<Perm>> grants = new HashMap<>();
    /** Права, открытые для всех посетителей (BUILD публичным быть не может). */
    public final EnumSet<Perm> publicPerms = EnumSet.noneOf(Perm.class);
    /** Денежный счёт предприятия; в мастерских с него списывается электричество. */
    public final PowerAccount account = new PowerAccount();
    /** Электростанция: мощность в V в сутки и текущий множитель тарифа при перегрузке (1.0 - нагрузки нет). */
    public double plantCapacity;
    public double plantMultiplier = 1.0;
    /** Предприятие подключено ровно к одной электростанции (id участка-станции); null - не подключено. */
    public String plantId;
    /** Почему предприятие не подключено (причина отключения администратором и т.п.). */
    public String gridReason;
    /** К какой станции было подключено до отключения - для быстрого восстановления. */
    public String lastPlantId;
    /** Следующее начисление налога на имущество (мс, 0 - не назначено) и неоплаченный налог. */
    public long nextTaxAt;
    public double taxDebt;
    /** Участок игрока: регион WorldGuard, арендодатель, одобрение и условия объявления. */
    public Kind kind = Kind.ADMIN;
    public String regionId;
    public UUID landlord;
    public String landlordName;
    public Approval approval = Approval.APPROVED;
    /** Арендодатель сохраняет доступ на время аренды (условие объявления, арендатор соглашается, снимая участок). */
    public boolean sharedAccess;
    /** Совладельцы региона: пока все не дали согласие, участок нельзя выставить на продажу или в аренду. */
    public final Set<UUID> requiredConsents = new HashSet<>();
    public final Set<UUID> consents = new HashSet<>();
    /** Участники региона WorldGuard на момент привязки: восстанавливаются после аренды. */
    public final Set<UUID> regionMembers = new HashSet<>();
    /** Номер отеля: id отеля, в который вложен участок (null - обычный участок). */
    public String parentId;
    /** Блоки дверей номера (x, y, z): открываются только гостем, его доверенными и управляющим отеля. */
    public final java.util.List<int[]> doors = new java.util.ArrayList<>();
    /** Класс номера отеля (только для номеров): по умолчанию эконом. */
    public RoomClass roomClass = RoomClass.ECONOMY;
    /** Сколько номер принёс отелю за всё время (после налога на аренду); для статистики по классам. */
    public double roomRevenue;
    /** Отель: максимальная цена номера за сутки, которую может назначить управляющий (0 - только администратор). */
    public double maxRoomRate;
    /** Уровень улучшений мастерской (0 - базовый) и производственные линии. */
    public int level;
    /** Какую из голограмм участка двигают: общая, услуги и товары, панель управляющего. */
    public enum HoloKind {
        MAIN("Общая голограмма"), SERVICES("Голограмма услуг и товаров"), MANAGER("Панель управляющего"), LOG("Голограмма последних операций банка");

        private final String title;

        HoloKind(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        public static HoloKind parse(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.toLowerCase(java.util.Locale.ROOT)) {
                case "main", "общая", "основная" -> MAIN;
                case "services", "service", "услуги", "товары" -> SERVICES;
                case "manager", "панель", "управляющий" -> MANAGER;
                case "log", "операции", "журнал" -> LOG;
                default -> null;
            };
        }
    }

    /** Положение голограммы услуг и панели управляющего {x, y, z}; null - автоматически рядом с общей. */
    public double[] servicesPos;
    public double[] managerPos;
    /** Положение голограммы последних операций банка. */
    public double[] logPos;
    /** Товары на продажу (витрина): материал -> количество, цена за штуку. */
    public final Map<org.bukkit.Material, Integer> goods = new LinkedHashMap<>();
    public final Map<org.bukkit.Material, Double> prices = new LinkedHashMap<>();
    /** Мастерская привязана к магазину или складу: продукция уходит туда автоматически. */
    public String linkedTo;
    /** У магазина или склада: мастерские, ждущие подтверждения связи. */
    public final java.util.Set<String> linkRequests = new java.util.LinkedHashSet<>();
    /** Мастерская складывает продукцию на свою витрину (если нет связи). */
    public boolean storefront;

    /** Одобренные администратором зачарования мастерской: идентификаторы рецептов «enchant:ПРЕДМЕТ|ключ|уровень». */
    public final java.util.Set<String> approvedEnchants = new java.util.LinkedHashSet<>();

    /** Станки мастерской с заданием: позиция "x,y,z" -> состояние. */
    public final Map<String, MachineState> machineStates = new LinkedHashMap<>();
    /** Склад сырья мастерской: материал -> количество (общий для всех станков). */
    public final Map<org.bukkit.Material, Integer> stock = new LinkedHashMap<>();
    /** Сундуки-приёмники сырья и сундуки продукции: позиции "x,y,z". */
    public final java.util.List<String> rawChests = new java.util.ArrayList<>();
    public final java.util.List<String> productChests = new java.util.ArrayList<>();
    /** Данные старых производственных линий (до версии 1.17): переносятся на склад при загрузке и очищаются. */
    public final Map<String, int[]> legacyLines = new LinkedHashMap<>();
    /** Услуги мастерской: ключ предложения -> цена и включено ли оно. */
    public final Map<String, Offer> offers = new LinkedHashMap<>();
    /** Индивидуальная скидка на электричество (0-100 %), назначается администратором. */
    public double discountPercent;
    /** Выручка предприятия за всё время: оплата номеров, продажи магазина, оплаченные заказы услуг. */
    public double revenue;

    public boolean hasSpawn;
    public double spawnX, spawnY, spawnZ;
    public float spawnYaw, spawnPitch;

    /** Здание нужно привести к исходному виду (сохраняется, чтобы пережить перезапуск). */
    public boolean restorePending;
    /** Кому вернуть предметы, не принадлежащие никому конкретно (из исходных контейнеров и т.п.). */
    public UUID restoreFor;
    public RestoreMode restoreMode = RestoreMode.FULL;
    /** Вещи арестовываются (изъятие участка) - до решения администратора. */
    public boolean restoreArrest;
    /** После освобождения здание остаётся на участке и становится его новым исходным видом. */
    public boolean restoreKeep;
    /** На участке стоит готовое здание (поставлено автостроительством, заказано или оставлено прежним управляющим). */
    public boolean hasBuilding;
    public String restoreReason;
    public String restoreBatch;

    /** Идёт операция над блоками (сброс, замена, снимок) - участок временно заблокирован. */
    public transient boolean busy;
    public transient boolean warned;
    /** Неудачные попытки сброса подряд (после трёх ждём перезапуска). */
    public transient int failures;
    /** 0 - без предупреждений, 1 - предупреждали за 24 ч, 2 - за 6 ч, 3 - обесточено. */
    public transient int powerWarnLevel;

    public Plot(String id, String world, Box box) {
        this.id = id;
        this.world = world;
        this.box = box;
    }

    public boolean isFrozen() {
        return graceUntil > System.currentTimeMillis();
    }

    public String displayName() {
        return name != null && !name.isBlank() ? name : category.title() + " " + id;
    }

    public boolean isPlayerPlot() {
        return kind == Kind.PLAYER;
    }

    public boolean isLandlord(UUID uuid) {
        return kind == Kind.PLAYER && landlord != null && landlord.equals(uuid);
    }

    /** Все совладельцы региона согласились на продажу и аренду. */
    public boolean consentsComplete() {
        return consents.containsAll(requiredConsents);
    }

    /** Арендодатель свободного (или сданного с общим доступом) участка игрока. */
    private boolean isLandlordWithAccess(UUID uuid) {
        return kind == Kind.PLAYER && landlord != null && landlord.equals(uuid) && (status == Status.FREE || sharedAccess);
    }

    public boolean isFree() {
        return status == Status.FREE;
    }

    public boolean isOwner(UUID uuid) {
        return owner != null && owner.equals(uuid);
    }

    /** Есть ли у игрока право (владелец - всегда; публичные права действуют для всех). */
    public boolean can(UUID uuid, Perm perm) {
        if (isOwner(uuid) || isLandlordWithAccess(uuid)) {
            return true;
        }
        EnumSet<Perm> set = grants.get(uuid);
        if (set != null && set.contains(perm)) {
            return true;
        }
        return perm != Perm.BUILD && publicPerms.contains(perm);
    }

    /** Может строить: владелец и игроки с правом BUILD. */
    public boolean isMember(UUID uuid) {
        return can(uuid, Perm.BUILD);
    }

    /** Связан с участком: владелец или имеет хотя бы одно персональное право. */
    public boolean involves(UUID uuid) {
        return isOwner(uuid) || grants.containsKey(uuid) || (kind == Kind.PLAYER && uuid.equals(landlord));
    }

    public void grant(UUID uuid, Perm... perms) {
        if (perms.length == 0) {
            return;
        }
        EnumSet<Perm> set = grants.computeIfAbsent(uuid, k -> EnumSet.noneOf(Perm.class));
        set.addAll(java.util.Arrays.asList(perms));
    }

    public void grantAll(UUID uuid) {
        grant(uuid, Perm.values());
    }

    public void revoke(UUID uuid, Perm perm) {
        EnumSet<Perm> set = grants.get(uuid);
        if (set != null) {
            set.remove(perm);
            if (set.isEmpty()) {
                grants.remove(uuid);
            }
        }
    }

    public boolean revokeAll(UUID uuid) {
        return grants.remove(uuid) != null;
    }

    public EnumSet<Perm> permsOf(UUID uuid) {
        EnumSet<Perm> set = grants.get(uuid);
        return set == null ? EnumSet.noneOf(Perm.class) : EnumSet.copyOf(set);
    }

    /** Сбрасывает все выданные права (при смене управляющего). */
    public void clearRights() {
        grants.clear();
        publicPerms.clear();
    }
}
