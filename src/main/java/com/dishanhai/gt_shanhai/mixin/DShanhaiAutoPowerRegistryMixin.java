package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.misc.DShanhaiWirelessPowerTerminalSavedData;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.forge.GTCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.LazyOptional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 全局机器自动登记——挂钩 GTCEu 所有机器（不限模组）的加载/卸载生命周期。
 * 只要机器暴露 IEnergyContainer 能量胶囊，加载进世界就自动登记进无线受电广播表，
 * 卸载时自动注销。取代手动放置专用"受电终端"方块的旧方案。
 */
@Mixin(value = MetaMachine.class, remap = false)
public class DShanhaiAutoPowerRegistryMixin {

    @Inject(method = "onLoad", at = @At("TAIL"))
    private void gtShanhai$autoRegister(CallbackInfo ci) {
        try {
            MetaMachine self = (MetaMachine) (Object) this;
            Level level = self.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) return;
            // 不能用 serverLevel.getBlockEntity(pos) 反查：此刻这台机器自己还没在
            // 区块的方块实体表里登记完，IMMEDIATE 创建策略会当场再建一个新实例，
            // 从而再次触发 clearRemoved()->onLoad()，无限递归导致 StackOverflowError。
            // 直接用 self 自己已持有的 holder 查能力，不经过 Level 的查找/创建路径。
            if (!(self.getHolder() instanceof BlockEntity be)) return;
            LazyOptional<IEnergyContainer> opt = be.getCapability(GTCapability.CAPABILITY_ENERGY_CONTAINER, null);
            if (!opt.isPresent()) return;
            DShanhaiWirelessPowerTerminalSavedData.get(serverLevel.getServer())
                    .register(serverLevel.dimension(), self.getPos());
        } catch (Exception ignored) {}
    }

    @Inject(method = "onUnload", at = @At("HEAD"))
    private void gtShanhai$autoUnregister(CallbackInfo ci) {
        try {
            MetaMachine self = (MetaMachine) (Object) this;
            Level level = self.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) return;
            DShanhaiWirelessPowerTerminalSavedData.get(serverLevel.getServer())
                    .unregister(serverLevel.dimension(), self.getPos());
        } catch (Exception ignored) {}
    }
}
