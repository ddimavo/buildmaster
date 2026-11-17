package ru.industryreborn.plan;

import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.ExtensionService;

/** Единственное место, где используются классы Plan при регистрации (загружается, только если Plan установлен). */
final class PlanRegistration {

    private PlanRegistration() { }

    static Object register(PlanHook hook) {
        DataExtension extension = new IndustryRebornExtension(hook);
        ExtensionService.getInstance().register(extension);
        return extension;
    }

    static void unregister(Object extension) {
        if (extension instanceof DataExtension data) {
            ExtensionService.getInstance().unregister(data);
        }
    }
}
