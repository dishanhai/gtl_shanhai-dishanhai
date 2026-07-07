package com.dishanhai.gt_shanhai.integration.jade.provider;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * 写入 -114514L 魔数，GTCEu ParallelProvider 客户端渲染为彩虹"无限"
 * 已由 ParallelProviderOverrideMixin 统一覆盖，此文件作冗余补充
 */
public class InfiniteParallelProvider implements IServerDataProvider<BlockAccessor> {

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "infinite_parallel");

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            if (accessor == null || accessor.getBlockEntity() == null) return;
            var be = accessor.getBlockEntity();
            if (!(be instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
            var machine = mbe.getMetaMachine();
            if (machine instanceof com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine
                    || machine instanceof com.dishanhai.gt_shanhai.common.machine.spacetime.SpacetimeWaveMatrixMachine
                    || machine instanceof com.dishanhai.gt_shanhai.common.machine.stripping.WorldLineStrippingOscillationGenerator) {
                data.putLong("parallel", -114514L);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
