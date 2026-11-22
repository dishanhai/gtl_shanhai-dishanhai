package com.shanhai.add.mixin;

import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 山海重构 · 【创始现实修改模块】<b>出站血量包钳制</b>：受保护玩家的
 * {@code ClientboundSetHealthPacket} 里那个 health 再兜一道（≤0 / NaN / ±∞ ⇒ 1.0）。
 *
 * <h2>为什么还要这一道（写入点已经钳过一次了）</h2>
 * 写入点钳制（{@link ShanhaiHealthWriteClampMixin}）保证字段里不可能是 {@code <=0}；
 * 但这条<b>出站</b>判据是给三种情况兜底的：
 * <ol>
 *   <li>字段在<b>我们挂上钩之前</b>就已经是好几个 tick 的 {@code 0}（热改/载入顺序）；</li>
 *   <li>别人不是写字段，而是<b>把 getHealth() 的返回值做了手脚</b>（这条路上包里的数会被改掉，
 *       而字段是真值 —— 本类钳的是<b>进包的那个数</b>，所以照样成立）；</li>
 *   <li>我们自己的复位动作与对手写 0 撞在同一拍 ⇒ 包可能正好抓到中间态。</li>
 * </ol>
 * ⇒ 客户端因此<b>再也不可能</b>收到 {@code health<=0} ⇒ 原版那条
 * 「{@code setScreen(null)} 时先改写成 {@code new DeathScreen}`」的分支不会被触发
 * ⇒ 20/s 的死亡界面自锁环从<b>源头</b>消失（{@code ShanhaiGuardClientEvents} 那三道留作第二道）。
 *
 * <h2>注入点是 javap 查证的，不是猜的</h2>
 * <pre>
 *   javap -p -c &lt;forge-1.20.1-47.3.0_mapped_official_1.20.1-recomp.jar&gt; net.minecraft.server.level.ServerPlayer
 *     … public void tick(); 里健康同步那一段：
 *       86: invokevirtual #803  // Method net/minecraft/server/level/ServerPlayer.getHealth:()F   ← 判断条件
 *      139: new           #818  // class net/minecraft/network/protocol/game/ClientboundSetHealthPacket
 *      144: invokevirtual #803  // Method getHealth:()F                                          ← 包里的数
 *      161: invokespecial #820  // Method ClientboundSetHealthPacket."&lt;init&gt;":(FIF)V
 *   ⇒ {@code <init>(FIF)V} 在 {@code tick()} 里<b>只出现一次</b> ⇒ 用 {@code @ModifyArg(index = 0)}
 *     精确改"进包的那个 float"，不用把整个方法体搬走、也不动任何控制流。
 * </pre>
 *
 * <h2>判据同样是"零 mod 名"</h2>
 * ① 这个数会不会把人写成死的（{@code <=0 / NaN / ±∞}）；② 这个玩家是不是受保护玩家。
 * 一条与具体 mod 有关的信息都没有。
 */
@Mixin(ServerPlayer.class)
public abstract class ShanhaiHealthPacketClampMixin {

    /**
     * 改 {@code new ClientboundSetHealthPacket(this.getHealth(), …)} 的第一个参数（health）。
     *
     * @param health 原值（即将被塞进包里的那个数）
     * @return 受保护玩家 ＋ 这个数会"读成死" ⇒ {@code 1.0}；其余<b>逐字原样</b>
     */
    @ModifyArg(
            // 🔴 2026-11-20 实机冒烟（第一次构建）报：Critical injection failure:
            //   Argument modifier … failed injection check, (0/1) succeeded. Scanned 1 target(s).
            //   ⇒ 方法过滤命中了 1 个，但 @At 的 INVOKE 没在里面找到。
            //   改成【SRG 名 + remap=false】：`ServerPlayer#tick()` 在 1.20.1 运行时就是 `m_9240_`；
            //   javap（…server-1.20.1-…-srg.jar）实证该 `ClientboundSetHealthPacket."<init>":(FIF)V`
            //   在 `m_9240_()` 里只出现一次（偏移 139 new / 161 invokespecial）。
            // ⚠️ require 1 → 0：这一道是【第二道保险】（第一道是 SynchedEntityData#set 的写入钳制），
            //   它不该有能力让整个服务端起不来；命中不了时 Mixin 会自己打一条 WARN，可判读。
            method = "m_9240_",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/game/ClientboundSetHealthPacket;<init>(FIF)V"),
            index = 0,
            remap = false,
            require = 0)
    private float shanhai$clampOutboundHealth(float health) {
        try {
            return ShanhaiGuardValueLayer.clampPacketHealth((Object) this, health);
        } catch (Throwable ignored) {
            return health;
        }
    }
}
