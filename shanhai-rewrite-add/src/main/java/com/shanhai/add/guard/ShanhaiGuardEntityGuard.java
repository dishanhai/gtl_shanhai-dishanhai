package com.shanhai.add.guard;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.guard.ShanhaiGuardCallerTrace;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardEvents;
import com.shanhai.common.guard.ShanhaiGuardRemoval;

import java.lang.reflect.Method;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

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
 *
 * <h2>🆕 分诊 R4 的 {@code setRemoved} 半边（只记账）</h2>
 * 判出"拦下"之后会静默调一次本体的 {@code ShanhaiGuardTriageBridge.noteRemoval(player, reason)}，
 * 让 R4（{@code die} / {@code setRemoved} 触达且同 tick 无伤害事件）拿到 {@code setRemoved} 那一路的读数
 * （{@code die} 半边本体自己已接，见 {@code ShanhaiGuardValueLayer}）。
 * 🔴 只在真的拦下时记一次；只在 {@code return true} 之前；反射调用、异常全吞 ⇒ 不改变"拦不拦"。
 */
public final class ShanhaiGuardEntityGuard {

    private ShanhaiGuardEntityGuard() {}

    /** 日志闸门（同类合并 ＋ 每秒最多一行；与守卫其它路径同一套实现）。 */
    private static final ShanhaiGuardCore.LogGate LOG = new ShanhaiGuardCore.LogGate();

    /** 日志类别（渲染出来就是 {@code [SHANHAI-GUARD] 拦截 移除}）。 */
    public static final String KIND_REMOVAL = "移除";

    /** 🆕 本体分诊桥的类名（反射用，理由见下方 {@code noteRemoval} —— add 只 compileOnly 本体 jar）。 */
    private static final String TRIAGE_BRIDGE = "com.shanhai.common.guard.ShanhaiGuardTriageBridge";

    /** 缓存下来的 {@code noteRemoval(Player, String)}；{@code null} = 解析失败过（不再重试）。 */
    private static volatile Method noteRemovalMethod;

    /** 是否已经试过解析（true ⇒ 不再 {@code Class.forName}，WARN 也只打一行）。 */
    private static volatile boolean noteRemovalResolved;

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
            // 🆕 R4 的 setRemoved 半边：**只在真的拦下这一支**记账（分诊桥的静默入口，只记录不反制）
            noteRemoval(player, reasonName);
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

    /**
     * 🆕 <b>R4 的 {@code setRemoved} 半边</b>：把"这一下真的被我们拦掉了"记进本体分诊桥
     * （{@code ShanhaiGuardTriageBridge.noteRemoval(Player, String)}：{@code void}、内部全吞、只记账）。
     *
     * <p>🔴 <b>为什么用反射而不是硬 import</b>：本工程的 add→本体 依赖是
     * {@code build.gradle} 里的 {@code compileOnly files(bodyJar)}（<b>不是</b>
     * {@code implementation project(':shanhai-rewrite')}），而那个 jar 是<b>上一次</b>构建的产物 ——
     * {@code ShanhaiGuardTriageBridge} 是本轮新加的类，<b>现在 build/libs 的 jar 里还没有它</b>
     * ⇒ 硬 import 会让整个 add 编译不过（{@code cannot find symbol}），而这跟"拦不拦得住移除"无关。
     * 反射同时还顺手满足"类加载不到 ／ 签名变了 ⇒ 只 WARN 一行、绝不抛"。
     *
     * <p>调用点只在 {@link #refuseSetRemoved} 判出 {@code decision.yes} 之后 ⇒
     * <b>不会</b>在每一 tick 的轮询里无条件记账（否则 R4 读数全是噪声）。
     *
     * <p>🔴 异常<b>不外抛</b>：外层的 {@code catch (Throwable)} 是 {@code return false}（放行），
     * 所以记账抛出去就等于"把拦下的移除又放走了" —— 本方法自己吞掉一切。
     *
     * @param player     被拦下的玩家（{@code noteRemoval} 自带 {@code ServerPlayer} 闸门）
     * @param reasonName {@code RemovalReason#name()}（只进日志尾巴，不进判据）
     */
    private static void noteRemoval(ServerPlayer player, String reasonName) {
        try {
            Method m = noteRemovalMethod;
            if (!noteRemovalResolved) {
                try {
                    // false = 只加载、不初始化（与 ShanhaiAeGate#load 同一套写法）
                    final Class<?> bridge = Class.forName(TRIAGE_BRIDGE, false,
                            ShanhaiGuardEntityGuard.class.getClassLoader());
                    m = bridge.getMethod("noteRemoval", Player.class, String.class);
                } catch (Throwable t) {
                    m = null;
                    ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX
                            + "分诊桥不可用（R4 的移除读数会缺，拦截行为逐字不变）：" + t);
                }
                noteRemovalMethod = m;
                noteRemovalResolved = true;
            }
            if (m != null) {
                m.invoke(null, player, reasonName);
            }
        } catch (Throwable ignored) {
            // 记账是"附加动作"：它坏了绝不许改变"拦不拦"的结论
        }
    }
}
