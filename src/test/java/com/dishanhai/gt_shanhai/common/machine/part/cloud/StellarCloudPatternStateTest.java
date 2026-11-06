package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：重載保留已付款樣板的有效定義，不依宿主區塊的載入順序重新生成。 */
class StellarCloudPatternStateTest {
    @Test void restoresThePaidTenfoldDefinitionAndRouteExactly() {
        UUID generation = UUID.randomUUID(); CompoundTag paid = new CompoundTag();
        paid.putInt("output", 10); paid.putString("recipe", "gt_shanhai:recipe_a"); paid.putUUID("route", generation);
        var restored = StellarCloudPatternState.load(new StellarCloudPatternState(generation, 10010, paid).save());
        assertEquals(generation, restored.generation()); assertEquals(10010, restored.fingerprint());
        assertEquals(10, restored.definition().getInt("output")); assertEquals(paid, restored.definition());
    }
    @Test void cannotMutateTheStoredExecutionThroughCallerNbt() {
        CompoundTag paid = new CompoundTag(); paid.putInt("output", 10);
        var state = new StellarCloudPatternState(UUID.randomUUID(), 10, paid);
        paid.putInt("output", 1); state.definition().putInt("output", 99);
        assertEquals(10, state.definition().getInt("output"));
    }
    @Test void missingRouteOrDefinitionCannotBecomeAReplacementTask() {
        assertThrows(IllegalArgumentException.class, () -> StellarCloudPatternState.load(new CompoundTag()));
        CompoundTag missing = new CompoundTag(); missing.putUUID("generation", UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> StellarCloudPatternState.load(missing));
    }
}
