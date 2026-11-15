package com.dishanhai.gt_shanhai.common.machine.part;

import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/** 山海：按方块 ID 识别兼容仓，适用于结构预览和实际方块。 */
public final class ShanhaiHubStructure {
    private ShanhaiHubStructure() {}

    public static boolean isHub(MultiblockState state) {
        return "maintenance_hatch".equals(getShanhaiBlockPath(state));
    }

    public static boolean borrowsMaterialSlot(MultiblockState state) {
        String path = getShanhaiBlockPath(state);
        return path != null && (path.equals("maintenance_hatch")
                || path.equals("cosmic_clean_gravity_maintenance_hatch")
                || path.equals("recipe_thread_hatch")
                || path.equals("overclock_hatch") || path.endsWith("_overclock_hatch"));
    }

    public static boolean isRecipeThreadHatch(MultiblockState state) {
        return "recipe_thread_hatch".equals(getShanhaiBlockPath(state));
    }

    private static String getShanhaiBlockPath(MultiblockState state) {
        if (state == null || state.getBlockState() == null) return null;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlockState().getBlock());
        return id != null && id.getNamespace().equals("gt_shanhai") ? id.getPath() : null;
    }
}
