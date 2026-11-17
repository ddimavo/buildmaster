package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.industryreborn.model.Category;

class OwnershipLimitsTest {

    private static Map<Category, Integer> owned(Object... pairs) {
        Map<Category, Integer> m = new EnumMap<>(Category.class);
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((Category) pairs[i], (Integer) pairs[i + 1]);
        }
        return m;
    }

    @Test
    void allowsWhileUnderLimits() {
        var r = OwnershipLimits.check(2, owned(Category.SHOP, 1), Category.SHOP, 6, 2);
        assertTrue(r.allowed());
    }

    @Test
    void totalLimitReached() {
        var r = OwnershipLimits.check(6, owned(), Category.HOUSE, 6, 2);
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("общий"));
    }

    @Test
    void categoryLimitReached() {
        var r = OwnershipLimits.check(3, owned(Category.WORKSHOP, 1), Category.WORKSHOP, 6, 1);
        assertFalse(r.allowed());
        assertTrue(r.reason().contains("Мастерская"));
    }

    @Test
    void otherCategoryIsNotAffected() {
        var r = OwnershipLimits.check(3, owned(Category.WORKSHOP, 1), Category.SHOP, 6, 1);
        assertTrue(r.allowed());
    }

    @Test
    void zeroMeansUnlimited() {
        var r = OwnershipLimits.check(500, owned(Category.SHOP, 500), Category.SHOP, 0, 0);
        assertTrue(r.allowed());
    }
}
