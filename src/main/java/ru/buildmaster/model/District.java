package ru.buildmaster.model;

import java.util.LinkedHashSet;
import java.util.Set;

/** Район: именованная группа участков (для отчётов по потреблению и массового отключения). */
public final class District {

    public final String name;
    public final Set<String> plotIds = new LinkedHashSet<>();

    public District(String name) {
        this.name = name;
    }
}
