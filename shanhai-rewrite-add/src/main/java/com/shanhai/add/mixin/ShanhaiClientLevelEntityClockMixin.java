package com.shanhai.add.mixin;

import com.shanhai.add.client.time.ShanhaiClientClock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 客户端：<b>除玩家以外</b>的实体不再 tick。
 *
 * <p>为什么需要：服务端冻结后不再下发移动包，但客户端仍会 tick 自己的实体
 * （{@code ClientLevel#tickNonPassenger}：{@code setOldPosAndRot / ++tickCount / tick()}）⇒
 * 生物倒手、物品旋转、TickCount 驱动的动画都会继续，看起来"世界没停"。
 *
 * <p>🔴 <b>玩家豁免</b>：{@code Minecraft.player} 放行 —— 他是唯一必须保持运转的实体
 * （移动/视角/输入都在他自己那条链上）。多人下别的玩家也属于"世界"，照样冻结。
 */
@Mixin(ClientLevel.class)
public abstract class ShanhaiClientLevelEntityClockMixin {

    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"), require = 1, cancellable = true)
    private void shanhai$freezeEntityTick(Entity entity, CallbackInfo ci) {
        try {
            if (ShanhaiClientClock.freezeEntity(entity)) {
                // 🔴 2026-10-18（缺陷 ③「空中实体上下鬼畜」）：取消 tick 的同时必须把"上一 tick 位置"
            //    对齐到当前位置。因为 setOldPosAndRot() 就写在被取消的 tickNonPassenger 第一句
            //    （ClientLevel.java:268），不补这一句 ⇒ xo/yo/zo 永远停在旧值，而渲染插值 α 照常
            //    每 tick 从 0 扫到 1 ⇒ 渲染位置在旧/新之间来回锯齿（逻辑位置不动 = "不落但抖"）。
            //    这一步只动插值基准，绝不碰 partialTick（玩家相机/视角不受影响）。
                ShanhaiClientClock.pinFrozenEntityPose(entity);
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 判定坏了 ⇒ 照常 tick（宁可少冻一个实体，也不能让实体卡住）
        }
    }
}
