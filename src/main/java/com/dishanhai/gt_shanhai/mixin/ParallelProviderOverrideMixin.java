package com.dishanhai.gt_shanhai.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.integration.jade.provider.ParallelProvider;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

@Mixin(value = ParallelProvider.class, priority = 500)
public class ParallelProviderOverrideMixin {

    private static final long SHANHAI_INFINITE = -114515L;

    /** 服务端：强制注入线程数据 + 用我们的魔数标记无限并行/线程 */
    @Inject(method = "appendServerData(Lnet/minecraft/nbt/CompoundTag;Lsnownee/jade/api/BlockAccessor;)V",
            at = @At("RETURN"), remap = false)
    private void gtShanhai$serverData(CompoundTag data, BlockAccessor accessor, CallbackInfo ci) {
        if (data == null) return;
        try {
            if (accessor == null || accessor.getBlockEntity() == null) return;
            var be = accessor.getBlockEntity();
            if (!(be instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
            var machine = mbe.getMetaMachine();

            if (machine instanceof com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase module) {
                data.putLong("threads", Math.max(1L, module.getAdditionalThread()));
                return;
            }

            if (machine instanceof IMultiController controller && controller.isFormed()) {
                for (IMultiPart part : controller.getParts()) {
                    if (part instanceof com.gtladd.gtladditions.api.machine.feature.IThreadModifierPart tp) {
                        int t = tp.getThreadCount();
                        if (t > 1) data.putLong("threads", t >= Integer.MAX_VALUE ? SHANHAI_INFINITE : t);
                    }
                }
            }

            long p = data.getLong("parallel");
            if (p >= Integer.MAX_VALUE || p < 0) data.putLong("parallel", SHANHAI_INFINITE);
            long t = data.getLong("threads");
            if (t >= Integer.MAX_VALUE || t < 0) data.putLong("threads", SHANHAI_INFINITE);
        } catch (Exception ignored) {}
    }

    /** 客户端：检测魔数，用原版格式 + 我们的渐变"无限" */
    @Inject(method = "appendTooltip(Lsnownee/jade/api/ITooltip;Lsnownee/jade/api/BlockAccessor;Lsnownee/jade/api/config/IPluginConfig;)V",
            at = @At("RETURN"), remap = false)
    private void gtShanhai$infiniteDisplay(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config, CallbackInfo ci) {
        if (accessor == null || accessor.getServerData() == null) return;
        try {
            CompoundTag data = accessor.getServerData();
            boolean hasP = data.getLong("parallel") == SHANHAI_INFINITE;
            boolean hasT = data.getLong("threads") == SHANHAI_INFINITE;

            if (hasP) {
                // 原版 "同时处理至多%d个配方" 用 %d 不支持传 Component
                tooltip.add(Component.literal("")
                        .append(Component.literal("同时处理至多"))
                        .append(DShanhaiTextUtil.createUltimateRainbow("无限"))
                        .append(Component.literal("个配方")));
            }
            if (hasT) {
                // "Has %s threads" 用 %s 支持传 Component
                tooltip.add(Component.translatable("gtladditions.multiblock.threads",
                        DShanhaiTextUtil.createUltimateRainbow("无限")));
            }
        } catch (Exception ignored) {}
    }
}
