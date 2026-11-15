package com.shanhai.add.mixin;

import com.shanhai.add.client.time.ShanhaiClientClock;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 客户端：<b>冻结期间收到的 teleport 必须立刻落位</b>（"猪倒着走"的最后一环）。
 *
 * <h2>1. 🔴 根因（2026-11-14 真机「猪还是没有倒着走」，原文位置）</h2>
 * 服务端那边写回位置 + 直发 {@code ClientboundTeleportEntityPacket} 都已经做了，但客户端是这么处理它的：
 * <pre>
 *   ClientPacketListener#handleTeleportEntity  （1.20.1 ClientPacketListener.java:528-544）
 *       if (!entity.isControlledByLocalInstance()) {
 *           entity.lerpTo(x, y, z, yRot, xRot, 3, true);          ← 只写插值目标
 *           entity.setOnGround(...);
 *       }
 *
 *   LivingEntity#lerpTo                        （1.20.1 LivingEntity.java:2759-2766）
 *       this.lerpX = x; this.lerpY = y; this.lerpZ = z;            ← 一个 setPos 都没有
 *       this.lerpSteps = 3;
 *
 *   LivingEntity#aiStep                        （1.20.1 LivingEntity.java:2505-2514）
 *       if (this.lerpSteps &gt; 0) { … this.setPos(d0, d2, d4); --this.lerpSteps; }   ← 真正落位在这里
 * </pre>
 * 而冻结期间 {@code ClientLevel#tickNonPassenger}（{@code ClientLevel.java:267-281}，
 * {@code setOldPosAndRot / ++tickCount / tick()}）被
 * {@code ShanhaiClientLevelEntityClockMixin} 整段取消 ⇒ <b>{@code aiStep} 永远不跑</b>
 * ⇒ 猪的逻辑位置和渲染位置都停在原地。<b>方块能动是因为它不走实体 tick。</b>
 *
 * <h2>2. 本 mixin 做什么</h2>
 * 冻结期间，把刚收到的 teleport <b>当场落位</b>（{@code absMoveTo} + {@code setOldPosAndRot}），
 * 等价于"插值立即完成"。这样服务端每 tick 写回的位置都会被看见 ⇒ 观感就是"猪沿原路倒着走"。
 *
 * <p>🔴 <b>只在冻结时生效</b>：正常游戏里一行都不碰（{@code lerpTo} 的三步插值是原版手感）。
 *
 * <p>🔴 <b>玩家永远豁免</b>：{@code Minecraft.player} / {@code LocalPlayer} 直接跳过 ——
 * 他的位置由他自己那条链决定（用户红线：玩家的时间不受影响）。
 *
 * <h2>3. 为什么挂在 {@code ClientPacketListener} 上而不是 {@code LivingEntity}</h2>
 * {@code LivingEntity} 是<b>双端共有</b>类，在它身上引用 {@code ShanhaiClientClock}
 * （里面有 {@code Minecraft} 等客户端类）会让专用服务端在类加载时就炸；
 * {@code ClientPacketListener} 是<b>仅客户端</b>类，塞进 mixin 配置的 {@code client} 段，
 * 服务端根本不会加载它。
 */
@Mixin(ClientPacketListener.class)
public abstract class ShanhaiClientTeleportClockMixin {

    @Inject(method = "handleTeleportEntity", at = @At("TAIL"), require = 1)
    private void shanhai$snapTeleportWhileFrozen(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        try {
            ShanhaiClientClock.snapFrozenTeleport(packet);
        } catch (Throwable ignored) {
            // 判定坏了 ⇒ 走原版插值（宁可少动一只实体，也不能让客户端卡住）
        }
    }
}
