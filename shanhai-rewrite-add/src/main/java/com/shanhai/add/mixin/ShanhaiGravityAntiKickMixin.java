package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【防踢】{@code ServerGamePacketListenerImpl}（服务端移动校验 / 反作弊）。
 *
 * <h2>要解决的问题</h2>
 * 原版每 tick 都在 {@code handleMovePlayer} 里算一个"这个客户端是不是在悬空"的布尔
 * （47.3.0 sources 第 928 行）：
 * <pre>
 *   this.clientIsFloating = d7 >= -0.03125D &amp;&amp; !flag1 &amp;&amp; … &amp;&amp; this.noBlocksAround(this.player);
 *   // 第 266-271 行：clientIsFloating 连续 80 tick（4 秒）⇒ disconnect(multiplayer.disconnect.flying)
 * </pre>
 * {@code d7 >= -0.03125D} 的意思是"这一 tick 没有往下掉够多"。G ≤ 0 时玩家本来就悬着/往上走，
 * 这个条件<b>必然成立</b> ⇒ 空旷处 4 秒必定被踢（"Flying is not enabled"）。骑船时还有第二条同类判据
 * （{@code handleMoveVehicle} 第 456 行的 {@code clientVehicleIsFloating}）。
 *
 * <h2>选的做法：只把这一个<b>比较阈值</b>推成 +∞</h2>
 * <pre>
 *   G = 1  ⇒ 原样返回 -0.03125D（原版判据逐字不变，反作弊强度一点不降）
 *   G ≠ 1  ⇒ 返回 +∞ ⇒ {@code d7 >= +∞} 永远 false ⇒ {@code clientIsFloating / clientVehicleIsFloating}
 *            永远是 false ⇒ <b>永不踢</b>
 * </pre>
 *
 * <h2>为什么不用"给玩家 {@code abilities.mayfly}"那条路</h2>
 * <ul>
 *   <li>{@code mayfly} 只出现在玩家那条判据里（第 928 行），<b>盖不住</b>第 456 行骑船那条
 *       （那条看的是 {@code entity.isNoGravity()} 与 {@code noBlocksAround}）⇒ 骑船时照样被踢。</li>
 *   <li>{@code abilities} 是<b>会写进玩家 NBT / 会下发客户端</b>的状态：改了要负责还原，
 *       玩家中途退出、换维度、死亡复活都得上心；忘了还原就是"玩家从此能飞"（真·作弊）。
 *       改一个<b>纯判据常量</b>没有这个尾巴 —— 恢复 G=1 时行为逐字回原版。</li>
 *   <li>{@code server.isFlightAllowed()} 一个字都不动（那是服务端级飞行开关）。</li>
 * </ul>
 *
 * <h2>为什么两条方法写在同一个注入里</h2>
 * 两个方法用的是<b>同一个字面量</b> {@code -0.03125D}（全文件只出现这两次，已 grep 核过），
 * 于是 {@code method = {"handleMovePlayer", "handleMoveVehicle"}} + {@code require = 2} 一次盖完；
 * 分成两个类反而是同一份逻辑抄两遍。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ShanhaiGravityAntiKickMixin {

    @ModifyConstant(
            method = { "handleMovePlayer", "handleMoveVehicle" },
            constant = @Constant(doubleValue = -0.03125D),
            require = 2)
    private static double shanhai$floatingThreshold(double vanilla) {
        return ShanhaiGravity.floatingThreshold(vanilla);
    }
}
