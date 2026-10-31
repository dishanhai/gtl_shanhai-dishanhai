package com.dishanhai.gt_shanhai.common.machine.primordial.module.core;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.recipe.PrimordialMyriadRecipeTypes;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrimordialMyriadConflictPauseTest {
    @Test void conflictPausesBeforeNativeInterruptionAndRetainsTheCandidateTier() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (Unsafe) field.get(null);
        Core core = (Core) unsafe.allocateInstance(Core.class);
        Logic logic = (Logic) unsafe.allocateInstance(Logic.class);
        core.logic = logic;
        logic.core = core;
        logic.working = true;
        GTRecipe recipe = new GTRecipe(null, new ResourceLocation("gt_shanhai", "retained_myriad_batch"),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), new CompoundTag(), 20, false);
        logic.seed(recipe);
        logic.restoreActiveRecipeTypeId(PrimordialMyriadRecipeTypes.TIER_2_ID);
        assertEquals(100, core.getCurrentOutputMultiplier());
        core.suppressed = true;
        logic.handleRecipeWorking();
        assertTrue(logic.waiting);
        assertEquals(7, logic.getProgress());
        assertSame(recipe, logic.getLastRecipe());
        assertEquals(100, core.getCandidateOutputMultiplier(), "暫停不可抹去候選階段，否則會反覆切換");
        assertEquals(1, core.getCurrentOutputMultiplier());
        assertNull(logic.getGTRecipe(), "衝突時不能進入搜尋與扣料");
        core.suppressed = false;
        assertEquals(100, core.getCurrentOutputMultiplier());
    }

    private static class Core extends PrimordialMyriadProliferationCore {
        Logic logic;
        boolean suppressed;
        Core() { super(null); }
        @Override public Logic getRecipeLogic() { return logic; }
        @Override public boolean isFormed() { return true; }
        @Override public boolean isHostConnected() { return true; }
        @Override public boolean isMyriadSuppressed() { return suppressed; }
        @Override public Component getMyriadConflictWarning() {
            return suppressed ? Component.literal("conflict") : null;
        }
        @Override public void invalidateHostOutputMultiplierCache() {}
    }
    private static class Logic extends PrimordialMyriadProliferationCoreLogic {
        Core core;
        boolean working, waiting;
        Logic() { super(null); }
        void seed(GTRecipe recipe) { lastRecipe = recipe; progress = 7; }
        @Override public SelectableRecipeTypeSetMachine getMachine() { return core; }
        @Override public boolean isWorking() { return working; }
        @Override public boolean isWaiting() { return waiting; }
        @Override public void setWaiting(Component reason) { waiting = true; working = false; }
    }
}
