package com.dishanhai.gt_shanhai.common.machine.part;

import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/** 山海：只识别枢纽方块，不在结构谓词热路径初始化机器注册表。 */
public final class ShanhaiHubStructure {
    private static final ResourceLocation HUB = new ResourceLocation("gt_shanhai", "maintenance_hatch");

    private ShanhaiHubStructure() {}

    public static boolean isHub(MultiblockState state) {
        return state != null && state.getBlockState() != null
                && HUB.equals(ForgeRegistries.BLOCKS.getKey(state.getBlockState().getBlock()));
    }
}
