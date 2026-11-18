package ru.industryreborn.plan;

import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.ExtensionService;
import java.util.List;

/** Единственное место, где используются классы Plan при регистрации (загружается, только если Plan установлен). */
final class PlanRegistration {

    private PlanRegistration() { }

    /** Регистрирует показатели и таблицы (два дополнения: см. {@link IndustryRebornTablesExtension}). */
    static Object register(PlanHook hook) {
        List<DataExtension> extensions = List.of(new IndustryRebornExtension(hook), new IndustryRebornTablesExtension(hook));
        for (DataExtension e : extensions) {
            ExtensionService.getInstance().register(e);
        }
        return extensions;
    }

    static void unregister(Object registered) {
        if (registered instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof DataExtension data) {
                    ExtensionService.getInstance().unregister(data);
                }
            }
        }
    }
}
