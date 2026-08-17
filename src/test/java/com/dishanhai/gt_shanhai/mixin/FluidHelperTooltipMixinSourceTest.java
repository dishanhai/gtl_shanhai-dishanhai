package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidHelperTooltipMixinSourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "FluidHelperTooltipMixin.java");

    @Test
    void injectsIntoTheJei15490ListTooltipSignature() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("import java.util.List;"));
        assertTrue(source.contains("addShanhaiFluidTooltip(List<Component> tooltip, FluidStack fluidStack, TooltipFlag tooltipFlag, CallbackInfo ci)"));
        assertTrue(source.contains("tooltip.add(ShanhaiTextAPI.inline(line));"));
        assertTrue(source.contains("tooltip.add(Component.literal(\"§7\" + line));"));
        assertFalse(source.contains("ITooltipBuilder"));
    }
}
