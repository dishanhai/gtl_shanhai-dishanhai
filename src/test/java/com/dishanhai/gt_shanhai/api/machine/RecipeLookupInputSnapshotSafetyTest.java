package com.dishanhai.gt_shanhai.api.machine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RecipeLookupInputSnapshotSafetyTest {

    private static final Path LOGIC_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "machine", "SelectableRecipeTypeSetRecipeLogic.java");
    private static final Path ITEM_MIXIN_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "MEPatternBufferItemRecipeTypeFilterMixin.java");
    private static final Path FLUID_MIXIN_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "MEPatternBufferFluidRecipeTypeFilterMixin.java");

    @Test
    void uncachedPatternSlotsCannotBeReusedAcrossRecipeTypesWithoutARevision() throws IOException {
        String logic = Files.readString(LOGIC_SOURCE);
        String itemMixin = Files.readString(ITEM_MIXIN_SOURCE);
        String fluidMixin = Files.readString(FLUID_MIXIN_SOURCE);

        assertFalse(logic.contains("RecipeLookupInputSnapshotScope.begin()"),
                "配方匹配会在同一轮改变样板槽 cached 状态，不能复用整轮旧快照");
        assertFalse(itemMixin.contains("reuseRecipeLookupInputSnapshot"),
                "物品样板快照必须随 getActiveAndUnCachedSlots 的变化重新生成");
        assertFalse(fluidMixin.contains("reuseRecipeLookupInputSnapshot"),
                "流体样板快照必须随 getActiveAndUnCachedSlots 的变化重新生成");
    }
}
