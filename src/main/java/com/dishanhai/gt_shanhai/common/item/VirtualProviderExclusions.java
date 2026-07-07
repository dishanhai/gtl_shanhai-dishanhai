package com.dishanhai.gt_shanhai.common.item;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public final class VirtualProviderExclusions {

    private static final Set<ResourceLocation> PATTERN_BUFFER_IDS = Set.of(
            new ResourceLocation("gtceu", "me_mini_pattern_buffer"),
            new ResourceLocation("gtceu", "me_extend_pattern_buffer"),
            new ResourceLocation("gtceu", "me_stocking_pattern_buffer"),
            new ResourceLocation("gtceu", "me_final_pattern_buffer"),
            new ResourceLocation("gtceu", "me_wildcard_pattern_buffer"),
            new ResourceLocation("gtladditions", "me_super_pattern_buffer"),
            new ResourceLocation("gtceu", "me_pattern_buffer_proxy"),
            new ResourceLocation("gtladditions", "me_super_pattern_buffer_proxy"));

    public static boolean isExcludedPatternBuffer(Object machine) {
        if (!(machine instanceof MetaMachine metaMachine) || metaMachine.getDefinition() == null) {
            return false;
        }
        return PATTERN_BUFFER_IDS.contains(metaMachine.getDefinition().getId());
    }

    private VirtualProviderExclusions() {}
}
