package com.shanhai.common.guard;

import com.shanhai.ShanhaiMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * 山海 · <b>① 拦实体移除（玩家删不掉）的执行体</b>（mixin 只转发，判据全在
 * {@link ShanhaiGuardRemoval#refuseRemoval}）。
 *
 * <h2>为什么是 {@code Entity#setRemoved(RemovalReason)} 的 HEAD</h2>
 * <pre>
 *   javap -p -c net.minecraft.world.entity.Entity   ←实测（不是推测）
 *     public final void discard();
 *        0: aload_0
 *        1: getstatic  Entity$RemovalReason.DISCARDED
 *        4: invokevirtual Entity.remove:(LEntity$RemovalReason;)V     ← remove 是【可被模组重写】的
 *     public void remove(RemovalReason);
 *        0: aload_0
 *        1: aload_1
 *        2: invokevirtual Entity.setRemoved:(LEntity$RemovalReason;)V  ← 真正的落点
 *        5: aload_0
 *        6: invokevirtual Entity.invalidateCaps:()V
 *     public final void setRemoved(RemovalReason);                        ← 【final】改不掉
 *        0..9  : 只在 removalReason==null 时写入
 *       12..23 : shouldDestroy() ⇒ stopRiding()
 *       26..35 : 乘客一起 onRemove
 *       40..45 : levelCallback.onRemove(reason)     ← 从世界的实体账里摘掉的就是这一步
 * </pre>
 * ⇒ <b>拦在 {@code setRemoved} 上</b>：模组就算把 {@code remove()} 整个重写成别的样子也绕不过去；
 * 反过来只拦 {@code remove()} 会被一行 {@code @Override} 破防。
 *
 * <h2>🔴 白名单（不放行会卡死）</h2>
 * 见 {@link ShanhaiGuardRemoval#isNormalLeave}：换维度 ／ 区块卸载 ／ 玩家确实已断开 ／ 服务器正在关闭。
 * <p>⚠️ 口径修正：需求里写的是 {@code server.isStopping()}，但 1.20.1（Forge 47.4.16）的
 * {@code MinecraftServer} <b>没有这个方法</b>（javap 只有 {@code isRunning()} ／ {@code isStopped()} ／
 * {@code stopServer()}）⇒ 这里用 {@code !isRunning() || isStopped()}。
 *
 * <h2>兜底（复用既有逻辑，不写第二份）</h2>
 * 万一还是被摘掉了，{@code ShanhaiGuardEvents#guardOne} 每 tick 的「被移除检测 ＋ 救回」
 * （{@code revive()} → {@code ServerLevel#addFreshEntity}）会把玩家实体挂回去 —— 本类不重复实现。
 */
public final class ShanhaiGuardEntityGuard {

    private ShanhaiGuardEntityGuard() {}

    /** 日志闸门（同类合并 ＋ 每秒最多一行；与守卫其它路径同一套实现）。 */
    private static final ShanhaiGuardCore.LogGate LOG = new ShanhaiGuardCore.LogGate();

    /** 日志类别（渲染出来就是 {@code [SHANHAI-GUARD] 拦截 移除}）。 */
    public static final String KIND_REMOVAL = "移除";

    /**
     * 🔴 <b>这一拍 {@code setRemoved(reason)} 要不要拦下</b>。
     *
     * @param self   mixin 里传 {@code (Object) this}
     * @param reason mixin 里传 {@code (Object) reason}（形参类型故意用 {@code Object}：
     *               mixin 体内不碰 Minecraft 成员，见 {@code ShanhaiSetHealthGuardMixin} 类注释）
     * @return true ⇒ 调用方必须 {@code ci.cancel()}（不移除）
     */
    public static boolean refuseSetRemoved(Object self, Object reason) {
        // 🔴 最便宜的一道前置：不是服务端玩家就立刻走人 ——
        //    世界上每一只掉落物/箭矢/生物的移除都会经过本方法，绝不能在这里扫背包。
        if (!(self instanceof ServerPlayer player)) {
            return false;
        }
        try {
            final String reasonName = (reason instanceof Entity.RemovalReason rr) ? rr.name() : null;
            final boolean disconnected = player.hasDisconnected() || player.connection == null;
            final MinecraftServer server = player.getServer();
            final boolean stopping = server != null && (!server.isRunning() || server.isStopped());
            // 🔴 "玩家已经死了"这一条只为放行 {@code PlayerList#respawn}（它用 DISCARDED 摘旧实体）。
            //    用死亡标志/死亡计时器判，**不用** getHealth()——血量读数可能被人加偏移。
            final boolean playerDead = player.isDeadOrDying() || player.deathTime > 0;
            final ShanhaiGuardCore.Decision decision = ShanhaiGuardRemoval.refuseRemoval(
                    true, ShanhaiGuardEvents.isGuarded(player), reasonName, disconnected, stopping, playerDead);
            if (!decision.yes) {
                return false;
            }
            final String line = LOG.admit(KIND_REMOVAL, System.currentTimeMillis());
            if (line != null) {
                ShanhaiMod.LOGGER.warn(line + " ｜ " + decision.reason
                        + " ／ player=" + player.getName().getString()
                        + " ／ 兜底=ServerTickEvent 的 revive()+addFreshEntity 那一路（既有逻辑）");
            }
            // 🆕 A 件·取证：**谁**在每秒几十次地调 setRemoved —— 打外部 mod 调用帧（每局最多 3 次）
            //    🔴 只在"受保护玩家被拦下"这一条路上打（普通实体的移除在方法第一行就 return false 了）
            noteRemovalCaller(player, reasonName);
            return true;
        } catch (Throwable t) {
            // 我们坏了也绝不许改变原版移除行为（不 cancel）
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX
                    + "setRemoved 判据抛异常（原样放行）：" + t);
            return false;
        }
    }

    /**
     * 🆕 <b>A 件·取证</b>：把这一拍的<b>外部 mod 调用帧</b>打出来。
     *
     * <p>规格点名的形状（一行一条）：
     * <pre>
     *   [SHANHAI-GUARD] removal_caller#1 at=com.x.Boss#tick:12 frames=com.x.Boss#tick:12 | com.x.Boss$1#run:4
     * </pre>
     * <b>每局最多 3 次</b>（{@link ShanhaiGuardCallerTrace#MAX_HITS_PER_LABEL}，两个标签各自记账；
     * 换世界时由 {@code ShanhaiPurge#tickSuppression} 清零）。
     *
     * <p>🔴 三点约束：
     * <ol>
     *   <li><b>只在拦下来的时候打</b>（本方法只从 {@code decision.yes} 之后的那一行调用）；
     *       普通实体压根走不到这里（{@code refuseSetRemoved} 第一行就把非玩家挡掉了）。</li>
     *   <li><b>额度先判再抓栈</b>：{@code capture} 内部先读计数，只有前 3 次才付
     *       {@code new Throwable().getStackTrace()} 的钱（这条路上每秒 14~30 次是常态）。</li>
     *   <li><b>取证坏了不许影响拦截</b>：本方法自己 try/catch 吞掉异常，
     *       外层的 {@code return true}（= 继续拦）<b>不在</b>这个 try 里面。</li>
     * </ol>
     *
     * @param player     被拦下的受保护玩家（只进这一行的尾巴，便于和上面那行日志对上）
     * @param reasonName {@code RemovalReason#name()}（同上）
     */
    private static void noteRemovalCaller(ServerPlayer player, String reasonName) {
        try {
            final String trace = ShanhaiGuardCallerTrace.capture(ShanhaiGuardCallerTrace.LABEL_REMOVAL);
            if (trace == null) {
                return;   // 额度用完（或抓栈时抛异常）⇒ 一行都不打
            }
            ShanhaiMod.LOGGER.warn(trace + " ｜ reason=" + reasonName
                    + " ／ player=" + player.getName().getString());
        } catch (Throwable ignored) {
            // 取证是"附加动作"：它坏了绝不许改变"拦不拦"的结论
        }
    }
}
