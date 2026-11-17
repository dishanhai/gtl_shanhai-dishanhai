package com.shanhai.add.mixin;

import com.shanhai.add.guard.state.ShanhaiStateAuthority;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海 · <b>「玩家自己的操作」那个可观测标记的置位/清位点</b>（用户点名要的地基）。
 *
 * <h2>🔴 为什么必须是这一条（用户原话）</h2>
 * <pre>
 *   「反击退的话，你为什么要把 tick 作为判据，我说了把是否为玩家自己的操作作为判据啊」
 * </pre>
 * 1.20.1 服务端处理"玩家自己的移动"只走这一个方法：
 * {@code ServerGamePacketListenerImpl#handleMovePlayer(ServerboundMovePlayerPacket)}。
 * 玩家的<b>走路 / 冲刺 / 下落 / 起跳</b>全都在它里面发生（起跳是它调的，见下面 javap 证据）。
 * ⇒ 在它 HEAD 置位、在它<b>每一条 return 之前</b>清位，就有了"这一段是玩家自己干的"这个可观测事实。
 *
 * <h2>🔴 javap 取证（不是推测）</h2>
 * <pre>
 *   javap -p -c net.minecraft.server.network.ServerGamePacketListenerImpl
 *     public void handleMovePlayer(ServerboundMovePlayerPacket);
 *       282: invokevirtual  // ServerPlayer.absMoveTo:(DDDFF)V      ← 位置由客户端包推
 *       748: invokevirtual  // ServerPlayer.jumpFromGround:()V       ← 🔴「0.42」就是这一行产生的
 *       780: invokevirtual  // ServerPlayer.move:(MoverType;Vec3)V   ← 走/冲刺/被撞都在这里结算
 *       ⇒ 玩家自己起跳的 0.42 产生在**本方法内部** ⇒ 它属于"玩家自己的操作"，不是外部写入
 *   TSRG 名（refmap 用）：handleMovePlayer → m_7185_
 *     （从 build\mixinrefmap\obfuscation-tsrg-dev-to-srg.tsrg 逐行核过：
 *      {@code handleMovePlayer (Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V m_7185_}）
 * </pre>
 *
 * <h2>为什么清位用 {@code @At("RETURN")} 而不是 {@code @At("TAIL")}</h2>
 * 这个方法有<b>多条 return</b>（断开、载具、睡觉、等传送确认、被踢…）。
 * {@code TAIL} 只覆盖最末尾那一条 ⇒ 早退的分支会把标记留在栈上。
 * {@code RETURN} 会在<b>每一条 return 之前</b>都插一次，正好是"成对"的语义。
 * 另外用 javap 核过 Mixin 0.8.5 的校验口径（{@code InjectionInfo} 字节码）：
 * {@code expect}（默认 1）是<b>下界</b>—— 命中数 ≥ expect 就算通过，
 * 命中更多不会被判"太多"⇒ 多 return 的方法上写 {@code @At("RETURN")} 是安全的。
 * <p>代价：清位钩子会被插进每一处 return（这是一次静态调用，不在热循环里，可接受）。
 *
 * <h2>异常路径</h2>
 * 方法体里抛异常时 {@code RETURN} 也不执行 ⇒ 标记会留着。
 * 这一条由 {@code ShanhaiSelfInput.MARK_TTL_NANOS}（200 ms）兜底，并计入 {@code stale_ignored} 读数。
 *
 * <h2>为什么本类体内零 Minecraft 成员引用（本工程铁律）</h2>
 * mixin 类体内的成员引用在生成的 refmap 里<b>没有条目</b>（见 {@code ShanhaiSetHealthGuardMixin} 类注释）。
 * 所以这里只做两件事：类型对得上的形参、{@code (Object) this}。
 * 取 {@code .player} 这件事在普通类 {@code ShanhaiStateAuthority} 里做（它会被 reobf）。
 *
 * <p>⚠️ 目标类 {@code ServerGamePacketListenerImpl} 是<b>原版</b>类（{@code net.minecraft.*}）——
 * 按用户口径「不要写那种静态的 mixin（指第三方 mod 的）」这是允许的，但必须在报告里点名。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ShanhaiServerGamePacketListenerSelfInputMixin {

    /**
     * 原版开始处理玩家自己的移动包：置位 {@code shanhaiSelfInput = self_input}。
     *
     * @param packet 原版形参（只占位，一个成员都不读）
     * @param ci     原版回调（本类不改控制流）
     */
    @Inject(
            method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
            at = @At("HEAD"),
            require = 1)
    private void shanhai$selfInputHead(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        try {
            // 只在服务端有效（这个类本身就只在服务端存在）
            ShanhaiStateAuthority.onSelfInputHead((Object) this);
        } catch (Throwable ignored) {
            // 置位失败 ⇒ 这一包被当"外部"（多判，不会放宽保护）⇒ 可以接受
        }
    }

    /**
     * 原版处理完玩家自己的移动包（<b>每一条 return 之前</b>）：
     * 把<b>这一包自己造成的</b>动量变化与位置位移记成<b>认可值</b>，然后清位。
     *
     * <p>⚠️ 不是"无条件采纳现在的值"：只有"出门值 ≠ 进门值"才采纳动量
     * （否则"猫推我 → 下一拍包先到"会把那次击退收编成玩家自己的操作，永远还不回去）；
     * 位置同理，只在"这一包声称的位移人腿解释得通"时才把锚点挪过去。
     * 判据全在 {@code ShanhaiStateRules}／{@code ShanhaiStateAuthority}，本类只做转发。
     *
     * @param packet 原版形参（只占位）
     * @param ci     原版回调
     */
    @Inject(
            method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
            at = @At("RETURN"),
            require = 1)
    private void shanhai$selfInputReturn(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        // 本方法内部自己 try/finally：**清位不许被任何异常跳过**
        ShanhaiStateAuthority.onSelfInputTail((Object) this);
    }
}
