package ru.industryreborn.plan;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.NotReadyException;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.annotation.TableProvider;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import com.djrapitops.plan.extension.icon.Icon;
import com.djrapitops.plan.extension.table.Table;
import java.util.UUID;

/**
 * Таблицы IndustryReborn на панели Plan - отдельное дополнение без показателей. Так сделано из-за ошибки раскладки панели Plan
 * («Invalid card width: 18»): если в одном дополнении есть и числовые показатели, и таблица с тремя и более столбцами, а графиков нет,
 * Plan считает ширину карточки больше 12 и не может её нарисовать. Таблицы без показателей занимают всю ширину и рисуются нормально.
 */
@PluginInfo(name = "IndustryReborn: таблицы", iconName = "table", iconFamily = Family.SOLID, color = Color.AMBER)
public class IndustryRebornTablesExtension implements DataExtension {

    private final PlanHook hook;

    public IndustryRebornTablesExtension(PlanHook hook) {
        this.hook = hook;
    }

    private PlanSnapshot s() {
        PlanSnapshot snap = hook.snapshot();
        if (snap == null) {
            throw new NotReadyException();
        }
        return snap;
    }

    private PlanSnapshot.PlayerView p(UUID id) {
        PlanSnapshot.PlayerView v = hook.player(id);
        if (v == null) {
            throw new NotReadyException();
        }
        return v;
    }

    @Override
    public CallEvents[] callExtensionMethodsOn() {
        return new CallEvents[] {CallEvents.PLAYER_JOIN, CallEvents.PLAYER_LEAVE, CallEvents.SERVER_EXTENSION_REGISTER, CallEvents.SERVER_PERIODICAL};
    }

    @TableProvider(tableColor = Color.AMBER, iconName = "city")
    public Table plotsByCategory() {
        Table.Factory table = Table.builder()
                .columnOne("Тип участка", Icon.called("building").of(Family.SOLID).build())
                .columnTwo("Занято", Icon.called("user-check").of(Family.SOLID).build())
                .columnThree("Всего", Icon.called("city").of(Family.SOLID).build());
        for (var m : s().stats().section("general").metrics()) {
            if (m.id().startsWith("cat.")) {
                String[] parts = m.text().split(" из ");
                table.addRow(m.label(), parts.length > 0 ? parts[0] : "", parts.length > 1 ? parts[1] : "");
            }
        }
        return table.build();
    }

    @TableProvider(tableColor = Color.AMBER, iconName = "trophy")
    public Table topRevenue() {
        Table.Factory table = Table.builder()
                .columnOne("Предприятие", Icon.called("shop").of(Family.SOLID).build())
                .columnTwo("Владелец", Icon.called("user").of(Family.SOLID).build())
                .columnThree("Выручка", Icon.called("coins").of(Family.SOLID).build());
        for (var t : s().stats().topRevenue()) {
            table.addRow(t.name(), t.owner(), t.text());
        }
        return table.build();
    }

    @TableProvider(tableColor = Color.GREEN, iconName = "vault")
    public Table treasuryOperations() {
        Table.Factory table = Table.builder()
                .columnOne("Время", Icon.called("clock").of(Family.SOLID).build())
                .columnTwo("Сумма", Icon.called("coins").of(Family.SOLID).build())
                .columnThree("Причина", Icon.called("comment").of(Family.SOLID).build());
        for (var row : s().treasury()) {
            table.addRow(row.time(), row.amount(), row.reason());
        }
        return table.build();
    }

    @TableProvider(tableColor = Color.AMBER, iconName = "city")
    public Table playerPlotsTable(UUID playerUUID) {
        Table.Factory table = Table.builder()
                .columnOne("Участок", Icon.called("building").of(Family.SOLID).build())
                .columnTwo("Тип", Icon.called("tag").of(Family.SOLID).build())
                .columnThree("Срок", Icon.called("calendar").of(Family.SOLID).build())
                .columnFour("Счёт", Icon.called("wallet").of(Family.SOLID).build());
        for (var row : p(playerUUID).plotRows()) {
            table.addRow(row.name(), row.type(), row.until(), row.account());
        }
        return table.build();
    }
}
