package com.dishanhai.gt_shanhai.mixin;

import appeng.core.sync.BasePacket;
import appeng.core.sync.packets.PatternAccessTerminalPacket;
import appeng.helpers.InventoryAction;
import appeng.menu.implementations.PatternAccessTermMenu;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternManagementMenu;
import com.dishanhai.gt_shanhai.network.ShanhaiPatternTerminalUpdateChunkPacket;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 样板访问终端（含山海无线样板管理终端等全部子类）增量同步节流。
 * <p>
 * AE2 的 {@code sendIncrementalUpdate} 在终端开启期间每 tick 对每个追踪容器的
 * 每个样板槽做 {@code ItemStack.matches} 深度 NBT 比对（编码样板 NBT 很大），
 * spark 实测约占服务端线程 0.64%。样板库存绝大多数 tick 毫无变化，全速 diff 纯属浪费。
 * <p>
 * 节流为每 {@value #THROTTLE_INTERVAL} tick 比对一次；玩家在终端内操作
 * （{@code doAction}）后 {@value #INTERACTION_BURST_TICKS} tick 内恢复每 tick
 * 全速，保证手动放取样板的 UI 反馈不受影响。自动化驱动的样板变化最多延迟
 * {@value #THROTTLE_INTERVAL} tick 才可见，无感。结构变化走 {@code sendFullUpdate}，
 * 不经此路径，不受节流影响。
 */
@Mixin(value = PatternAccessTermMenu.class, remap = false)
public abstract class PatternAccessTermMenuIncrementalThrottleMixin {

    @Unique
    private static final int THROTTLE_INTERVAL = 4;
    @Unique
    private static final int INTERACTION_BURST_TICKS = 60;

    @Unique
    private long gtShanhai$incrementalTick;
    @Unique
    private long gtShanhai$burstUntilTick;

    @Inject(method = "sendIncrementalUpdate", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$throttleIncrementalDiff(CallbackInfo ci) {
        long tick = ++gtShanhai$incrementalTick;
        if (tick <= gtShanhai$burstUntilTick) return;
        if (tick % THROTTLE_INTERVAL != 0L) {
            ci.cancel();
        }
    }

    @Inject(method = "doAction", at = @At("HEAD"))
    private void gtShanhai$fullSpeedAfterInteraction(ServerPlayer player, InventoryAction action, int slot, long id,
                                                     CallbackInfo ci) {
        gtShanhai$burstUntilTick = gtShanhai$incrementalTick + INTERACTION_BURST_TICKS;
    }

    @WrapOperation(method = {"sendFullUpdate", "sendIncrementalUpdate"}, at = @At(value = "INVOKE",
            target = "Lappeng/menu/implementations/PatternAccessTermMenu;sendPacketToClient(Lappeng/core/sync/BasePacket;)V"))
    private void gtShanhai$sendPatternUpdate(PatternAccessTermMenu menu, BasePacket packet, Operation<Void> original) {
        if (!(menu instanceof ShanhaiPatternManagementMenu) || !(packet instanceof PatternAccessTerminalPacket)) {
            original.call(menu, packet);
            return;
        }
        FriendlyByteBuf payload = ((ShanhaiAe2PacketPayloadAccessor) packet).gtShanhai$getPayload();
        if (payload.readableBytes() <= ShanhaiPatternTerminalUpdateChunkPacket.MAX_NATIVE_BYTES) {
            original.call(menu, packet);
            return;
        }
        try {
            if (menu.getPlayer() instanceof ServerPlayer player && player.containerMenu == menu) {
                ShanhaiPatternTerminalUpdateChunkPacket.send(player, menu.containerId, payload);
            }
        } finally {
            payload.release();
        }
    }
}
