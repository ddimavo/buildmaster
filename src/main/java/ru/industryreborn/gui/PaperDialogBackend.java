package ru.industryreborn.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.util.Text;

/** Окна Paper (Dialog API, Minecraft 1.21.6+). Этот класс загружается только на серверах, где эти классы есть. */
final class PaperDialogBackend implements DialogService.Backend {

    private final IndustryRebornPlugin plugin;

    PaperDialogBackend(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void showChoices(Player player, String title, List<String> bodyLines, List<DialogService.Choice> choices, Consumer<String> choose, Runnable cancel) {
        List<DialogBody> body = new ArrayList<>();
        for (String line : bodyLines) {
            body.add(DialogBody.plainMessage(Text.mm(line), 300));
        }
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(10)).build();
        List<ActionButton> buttons = new ArrayList<>();
        for (DialogService.Choice c : choices) {
            buttons.add(ActionButton.builder(Text.mm("<" + c.color() + ">" + c.label()))
                    .width(c.id().equals("ok") || c.id().equals("manual") ? 300 : 70)
                    .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(plugin, () -> choose.accept(c.id())), once))
                    .build());
        }
        ActionButton exit = ActionButton.builder(Text.mm("<red>Отмена"))
                .width(150)
                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(plugin, cancel), once))
                .build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Text.mm("<gold>" + title)).body(body).canCloseWithEscape(true).build())
                .type(DialogType.multiAction(buttons, exit, 4)));
        player.showDialog(dialog);
    }

    @Override
    public void show(Player player, DialogService.Spec spec, Consumer<Map<String, Object>> submit, Runnable cancel) {
        List<DialogBody> body = new ArrayList<>();
        for (String line : spec.body()) {
            body.add(DialogBody.plainMessage(Text.mm(line), 300));
        }
        List<DialogInput> inputs = new ArrayList<>();
        for (DialogService.Field f : spec.fields()) {
            Component label = Text.mm(f.label());
            switch (f.kind()) {
                case TEXT -> inputs.add(DialogInput.text(f.key(), label).initial(f.initial()).maxLength(Math.max(1, f.maxLength())).width(300).build());
                case NUMBER -> inputs.add(DialogInput.numberRange(f.key(), label, (float) f.min(), (float) f.max())
                        .initial((float) Double.parseDouble(f.initial())).step((float) f.step()).width(300).build());
                case BOOL -> inputs.add(DialogInput.bool(f.key(), label).initial(Boolean.parseBoolean(f.initial())).build());
                default -> { }
            }
        }
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(10)).build();
        ActionButton confirm = ActionButton.builder(Text.mm("<green>" + spec.confirm()))
                .width(140)
                .action(DialogAction.customClick((DialogResponseView view, net.kyori.adventure.audience.Audience audience) -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (DialogService.Field f : spec.fields()) {
                        switch (f.kind()) {
                            case TEXT -> values.put(f.key(), view.getText(f.key()) == null ? "" : view.getText(f.key()));
                            case NUMBER -> values.put(f.key(), view.getFloat(f.key()) == null ? DialogService.initial(f) : (Object) view.getFloat(f.key()).doubleValue());
                            case BOOL -> values.put(f.key(), view.getBoolean(f.key()) != null && view.getBoolean(f.key()));
                            default -> { }
                        }
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> submit.accept(values));
                }, once))
                .build();
        ActionButton no = ActionButton.builder(Text.mm("<red>" + spec.cancel()))
                .width(140)
                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(plugin, cancel), once))
                .build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Text.mm("<gold>" + spec.title())).body(body).inputs(inputs).canCloseWithEscape(true).build())
                .type(DialogType.confirmation(confirm, no)));
        player.showDialog(dialog);
    }
}
