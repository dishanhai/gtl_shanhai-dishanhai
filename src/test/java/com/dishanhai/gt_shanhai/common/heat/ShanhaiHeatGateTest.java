package com.dishanhai.gt_shanhai.common.heat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShanhaiHeatGateTest {

    @Test
    void oneMaintenanceHatchCanProvideCleanroomAndGravity() {
        ShanhaiHeatGate.SlotContent slot = new ShanhaiHeatGate.SlotContent(
                1, 0, 0, ShanhaiHeatGate.CLEANROOM_LAW, true, false, Set.of());

        ShanhaiHeatGate.Outcome outcome = ShanhaiHeatGate.evaluate(
                List.of(
                        ShanhaiHeatGate.Requirement.cleanroom(ShanhaiHeatGate.CLEANROOM_STERILE),
                        ShanhaiHeatGate.Requirement.gravity()),
                List.of(slot));

        assertTrue(outcome.allowed);
        assertEquals(2, outcome.satisfied.size());
    }

    @Test
    void heatSourcesNeedAFullStackButFragmentsNeedOneItem() {
        ShanhaiHeatGate.SlotContent partialCoil = new ShanhaiHeatGate.SlotContent(
                63, 1800, 0, 0, false, false, Set.of());
        ShanhaiHeatGate.Outcome partial = ShanhaiHeatGate.evaluate(
                List.of(ShanhaiHeatGate.Requirement.heatTemp(1200)),
                List.of(partialCoil));

        assertFalse(partial.allowed);
        assertEquals(ShanhaiHeatGate.Deny.NOT_FULL, partial.deny);

        ShanhaiHeatGate.SlotContent creationFragment = new ShanhaiHeatGate.SlotContent(
                1, 0, 0, 0, false, false, Set.of("kubejs:create"));
        ShanhaiHeatGate.Outcome fragment = ShanhaiHeatGate.evaluate(
                List.of(ShanhaiHeatGate.Requirement.dimension("kubejs:create")),
                List.of(creationFragment));

        assertTrue(fragment.allowed);
        assertEquals("dishanhai:world_fragments_creation",
                ShanhaiHeatGate.fragmentForDimension("kubejs:create"));
        assertEquals("kubejs:create",
                ShanhaiHeatGate.dimensionOfFragment("dishanhai:world_fragments_creation"));
    }

    @Test
    void normalModeEnforcesPhysicalRequirementsButIgnoresResearch() {
        assertTrue(ShanhaiHeatGate.isRequirementEnforced(
                ShanhaiHeatGate.Kind.DIMENSION, false));
        assertTrue(ShanhaiHeatGate.isRequirementEnforced(
                ShanhaiHeatGate.Kind.HEAT_TEMP, false));
        assertTrue(ShanhaiHeatGate.isRequirementEnforced(
                ShanhaiHeatGate.Kind.SC_TIER, false));
        assertTrue(ShanhaiHeatGate.isRequirementEnforced(
                ShanhaiHeatGate.Kind.GRAVITY, false));
        assertTrue(ShanhaiHeatGate.isRequirementIgnored(
                ShanhaiHeatGate.Kind.RESEARCH, false));
    }

    @Test
    void unrestrictedModeKeepsOnlyGravityRequirement() {
        assertTrue(ShanhaiHeatGate.isRequirementEnforced(
                ShanhaiHeatGate.Kind.GRAVITY, true));
        assertTrue(ShanhaiHeatGate.isRequirementIgnored(
                ShanhaiHeatGate.Kind.CLEANROOM, true));
        assertTrue(ShanhaiHeatGate.isRequirementIgnored(
                ShanhaiHeatGate.Kind.DIMENSION, true));
        assertTrue(ShanhaiHeatGate.isRequirementIgnored(
                ShanhaiHeatGate.Kind.HEAT_TEMP, true));
        assertTrue(ShanhaiHeatGate.isRequirementIgnored(
                ShanhaiHeatGate.Kind.SC_TIER, true));
        assertTrue(ShanhaiHeatGate.isRequirementIgnored(
                ShanhaiHeatGate.Kind.RESEARCH, true));
    }
}
