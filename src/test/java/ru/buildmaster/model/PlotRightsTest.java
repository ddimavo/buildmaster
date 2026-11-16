package ru.buildmaster.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlotRightsTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private static Plot plot() {
        Plot p = new Plot("a", "w", new Box(0, 0, 0, 9, 9, 9));
        p.owner = OWNER;
        return p;
    }

    @Test
    void ownerHasEverything() {
        Plot p = plot();
        for (Plot.Perm perm : Plot.Perm.values()) {
            assertTrue(p.can(OWNER, perm));
        }
    }

    @Test
    void strangerHasNothingByDefault() {
        Plot p = plot();
        for (Plot.Perm perm : Plot.Perm.values()) {
            assertFalse(p.can(STRANGER, perm));
        }
        assertFalse(p.isMember(STRANGER));
        assertFalse(p.involves(STRANGER));
    }

    @Test
    void rightsAreGrantedIndependently() {
        Plot p = plot();
        p.grant(FRIEND, Plot.Perm.CONTAINERS);
        assertTrue(p.can(FRIEND, Plot.Perm.CONTAINERS));
        assertFalse(p.can(FRIEND, Plot.Perm.BUILD));
        assertFalse(p.can(FRIEND, Plot.Perm.STATIONS));
        assertFalse(p.isMember(FRIEND), "строить может только с правом BUILD");
        assertTrue(p.involves(FRIEND));
    }

    @Test
    void revokingLastRightRemovesTheGrant() {
        Plot p = plot();
        p.grant(FRIEND, Plot.Perm.STATIONS);
        p.revoke(FRIEND, Plot.Perm.STATIONS);
        assertFalse(p.grants.containsKey(FRIEND));
        assertFalse(p.involves(FRIEND));
    }

    @Test
    void grantAllAndRevokeAll() {
        Plot p = plot();
        p.grantAll(FRIEND);
        assertTrue(p.isMember(FRIEND));
        assertEquals(3, p.permsOf(FRIEND).size());
        assertTrue(p.revokeAll(FRIEND));
        assertFalse(p.revokeAll(FRIEND));
    }

    @Test
    void publicRightsApplyToEveryoneExceptBuild() {
        Plot p = plot();
        p.publicPerms.add(Plot.Perm.STATIONS);
        p.publicPerms.add(Plot.Perm.BUILD); // даже если попало в набор, строить посторонним нельзя
        assertTrue(p.can(STRANGER, Plot.Perm.STATIONS));
        assertFalse(p.can(STRANGER, Plot.Perm.CONTAINERS));
        assertFalse(p.can(STRANGER, Plot.Perm.BUILD));
    }

    @Test
    void clearRightsDropsGrantsAndPublicAccess() {
        Plot p = plot();
        p.grantAll(FRIEND);
        p.publicPerms.add(Plot.Perm.CONTAINERS);
        p.clearRights();
        assertTrue(p.grants.isEmpty());
        assertTrue(p.publicPerms.isEmpty());
    }

    @Test
    void permsOfIsACopy() {
        Plot p = plot();
        p.grant(FRIEND, Plot.Perm.BUILD);
        p.permsOf(FRIEND).add(Plot.Perm.STATIONS);
        assertFalse(p.can(FRIEND, Plot.Perm.STATIONS));
    }
}
