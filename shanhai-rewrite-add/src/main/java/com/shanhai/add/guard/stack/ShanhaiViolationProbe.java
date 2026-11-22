package com.shanhai.add.guard.stack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.guard.state.ShanhaiStateAuthority;
import com.shanhai.add.guard.state.ShanhaiStateRules;
import com.shanhai.add.hotfix.HotfixHealthClampStrike;
import com.shanhai.common.guard.ShanhaiGuardEvents;
import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 山海 · <b>「原版被写的那一刻」的旁听者</b>（认识 MC，但不改任何行为）。
 *
 * <h2>🔴 它为什么必须住在注入点，而不是轮询点（用户当场纠正过）</h2>
 * <pre>
 *   「你去抓所有代码」
 *   「昨天我说的那个主动检测并废掉敌方的代码，你到底能不能做」
 * </pre>
 * 轮询（tick 末发现"值被改了"）那一刻，改值的人<b>早就返回了</b>，栈上只剩
 * {@code ServerTickEvent → 我们的类} ⇒ 那天 46 条 {@code source=unknown}、{@code strike_caller none}。
 * 这里挂在<b>原版写入方法的最前面</b>（HEAD），栈上还留着调用者 ——
 * 这才是第三方 mod 的帧唯一能出现的位置。
 *
 * <h2>它做什么 / 不做什么</h2>
 * <table border="1">
 *   <tr><th>做</th><th>不做</th></tr>
 *   <tr><td>① 便宜地判"这一次值异常吗、目标是不是受保护玩家"
 *       ② 命中才抓一次栈（{@link ShanhaiViolationStack}，同一 {@code where|target} 1/s）
 *       ③ 打 {@code [SHANHAI-GUARD] violation_stack …} 一行
 *       ④ 把帧喂给 {@code HotfixTriageTap} 的 strike 通道</td>
 *       <td>❌ 不改值 ❌ 不取消 ❌ 不写回 ❌ 不在快路径上打日志
 *           （拦不拦、改不改仍由本体值层与 {@link ShanhaiStateAuthority} 决定）</td></tr>
 * </table>
 *
 * <h2>为什么阈值只用来"决定要不要抓栈"</h2>
 * {@code HEALTH_DROP_ALARM} 是<b>采样判据</b>，不是拦截判据：
 * 它只决定"这次写血要不要留一条栈"，拦不拦还是本体那套。所以它不影响任何玩家可见行为。
 * 3 颗心的依据：本体 {@code guardOne} 每 tick 把血补满 ⇒ 受保护玩家身上
 * <b>任何一次把血削掉 ≥3 颗心的写入</b>都值得留证据（正常挨打是 1~2 颗心且立刻被补回）。
 */
public final class ShanhaiViolationProbe {

    /** 与守卫同一套前缀（纯字面量，避免本类依赖 {@code ShanhaiStateLog} 的加载顺序）。 */
    public static final String PREFIX = "[SHANHAI-GUARD] ";

    /**
     * 「一次写血削掉这么多就算可疑」的采样阈值（3 颗心 = 6.0）。
     *
     * <p>⚠️ 只决定要不要抓栈，<b>不是</b>拦截判据（见类注释）。
     */
    public static final float HEALTH_DROP_ALARM = 6.0F;

    private static final Map<String, AtomicLong> BY_WHERE = new LinkedHashMap<>();

    /**
     * 🆕 2026-11-20：{@code where|target} ⇒ <b>最近一次由「原版写入点」抓到的那个外部帧</b>
     * （{@code 类#方法:行}）。
     *
     * <h2>它解决的是"这次偏差是谁写的"这一问</h2>
     * 第二层（{@code ShanhaiStateAuthority}）在<b>轮询点</b>发现血/死亡态被写坏并纠正时，
     * 那一刻写者<b>早就返回了</b> —— 栈上只剩我们自己的帧（这一点
     * {@code ShanhaiStateLog} 的类注释已经写死：轮询点<b>不假装</b>能抓到写者）。
     * 真正的写者只在<b>写入点</b>抓得到：{@code setHealth} 的 HEAD（{@link #guardSetHealthHead}）、
     * 同步血量字段直写的 HEAD（{@link #onHealthDataWrite}）、{@code die} 的 HEAD（{@link #dieHead}）、
     * {@code setRemoved} 的 HEAD（{@link #setRemovedHead}）。
     * ⇒ 那些抓点在成功抓到栈时往这里记一笔，纠正日志再把这一笔带出来。
     *
     * <p>⚠️ 抓栈那一层本身已经是"同一 {@code where|target} 1/s"（{@link ShanhaiViolationStack}）
     * ⇒ 这张表天然限流，不会膨胀。
     */
    private static final Map<String, String> LAST_WRITER = new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code guard_motion_skip} 的限流时间戳（1 行 / 5 秒）。 */
    private static volatile long GUARD_SKIP_LAST_MS;

    private ShanhaiViolationProbe() {}

    // ═══════════════════════════════ 原版写入点（由 mixin 在 HEAD 调） ═══════════════════════════════

    /**
     * {@code LivingEntity#setHealth(float)} 的 HEAD。
     *
     * <p>判据（缺一不可，按<b>从便宜到贵</b>的顺序）：
     * <ol>
     *   <li>被写的是玩家（{@code instanceof ServerPlayer}，最便宜）；</li>
     *   <li>这次值是"异常"：{@code newValue <= 0}（要命）或 {@code newValue < 当前值 - }{@link #HEALTH_DROP_ALARM}；</li>
     *   <li>目标是受保护玩家（要遍历物品栏，最贵 ⇒ 放最后）；</li>
     *   <li>同一 {@code health|玩家} 1 秒内只抓一次（在 {@link ShanhaiViolationStack} 里）。</li>
     * </ol>
     *
     * @param self      {@code (Object) this}（mixin 类体不许引 MC 成员）
     * @param requested 原入参（此刻还没写进 {@code health}）
     */
    public static void guardSetHealthHead(Object self, float requested) {
        try {
            if (!(self instanceof ServerPlayer sp)) {
                return;                       // 生物死亡也走 setHealth(0)，这一步先把它们全部挡掉
            }
            final float current = ((LivingEntity) self).getHealth();
            final boolean killing = requested <= 0.0F;
            final boolean bigDrop = requested < current - HEALTH_DROP_ALARM;
            if (!killing && !bigDrop) {
                return;
            }
            if (!ShanhaiStateAuthority.protectedPlayer(sp)) {
                return;
            }
            capture("health", sp, "requested=" + ShanhaiStateLogNum.num(requested)
                    + " current=" + ShanhaiStateLogNum.num(current)
                    + " killing=" + killing + " big_drop=" + bigDrop);
        } catch (Throwable ignored) {
            // 旁听失败只意味着"这一次没有栈"，绝不许影响写血本身
        }
    }

    /**
     * {@code ServerPlayer#die(DamageSource)} / {@code LivingEntity#die(DamageSource)} 的 HEAD。
     *
     * <p>判据：目标是受保护玩家（判死这件事本身没有"值"可看）。
     *
     * @param self {@code (Object) this}
     * @param site 哪一个注入点（{@code ServerPlayer#die} / {@code LivingEntity#die}）
     */
    public static void dieHead(Object self, String site) {
        try {
            if (!(self instanceof ServerPlayer sp)) {
                return;
            }
            if (!ShanhaiStateAuthority.protectedPlayer(sp)) {
                return;
            }
            capture("die", sp, "site=" + site);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /**
     * {@code Entity#setRemoved(RemovalReason)} 的 HEAD。
     *
     * <p>判据：目标是受保护玩家（换维度/区块卸载/退服这些"正常离开"由本体值层放行，
     * 这里只负责<b>留下证据</b>，不参与放行判定 —— 所以即使最后放行了，这一条栈也是有用的）。
     *
     * @param self {@code (Object) this}
     * @param site 注入点说明
     */
    public static void setRemovedHead(Object self, String site) {
        try {
            if (!(self instanceof ServerPlayer sp)) {
                return;
            }
            if (!ShanhaiStateAuthority.protectedPlayer(sp)) {
                return;
            }
            capture("remove", sp, "site=" + site);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ═══════════════════════════════ 判据侧的现场（由 Authority 判过之后调） ═══════════════════════════════

    /**
     * 有人在写玩家的动量，而且<b>轮询接下来一定会还原它</b>（判据已在
     * {@link ShanhaiStateAuthority#noteMotionWrite} 里走完）。
     *
     * @param sp   玩家
     * @param x/y/z 这次要写进去的值
     */
    public static void onMotionWrite(ServerPlayer sp, double x, double y, double z) {
        capture("motion", sp, "write=" + ShanhaiStateLogNum.vec(x, y, z));
    }

    /** 有人在写玩家的位置，判据同上（{@link ShanhaiStateAuthority#notePosWrite}）。 */
    public static void onPosWrite(ServerPlayer sp, double x, double y, double z) {
        capture("pos", sp, "write=" + ShanhaiStateLogNum.vec(x, y, z));
    }

    /**
     * <b>写入口把这一次动量写入丢掉了</b>（{@code motion_brake} 判成 {@code foreign_big}）。
     *
     * <p>为什么必须在这里抓：被丢掉的写入<b>不会</b>走到 {@code setDeltaMovement} 的 RETURN
     * ⇒ {@link #onMotionWrite} 那条路<b>一次都不会被叫到</b>（原方法已经被取消）。
     * 而这一帧的栈恰恰是回到"<b>是它在推</b>"的唯一证据（{@code first_external=…}）。
     *
     * <p>成本：{@code StackWalker} 只在<b>真丢弃</b>时走一趟，并且同一个 {@code where|target} 1/s 限流
     * （{@link ShanhaiViolationStack#capture}）⇒ 连着被推几秒也只留几条，不会拖 tick。
     *
     * @param sp    玩家
     * @param dvx/dvy/dvz 这一次写入的 Δv（新值 − 旧值 = 原本要被加上的那一把）
     * @param horiz 水平幅度 {@code hypot(dvx, dvz)}
     * @param vert  垂直幅度（带符号）
     */
    public static void onMotionBrakeDrop(ServerPlayer sp, double dvx, double dvy, double dvz,
                                         double horiz, double vert) {
        try {
            capture("motion_brake", sp, "dv=" + ShanhaiStateLogNum.vec(dvx, dvy, dvz)
                    + " horiz=" + ShanhaiStateLogNum.num(horiz)
                    + " vert=" + ShanhaiStateLogNum.num(vert)
                    + " note=dropped_at_write_head");
        } catch (Throwable ignored) {
            // 抓不到栈不影响"这一次写入已经被丢掉"这个事实
        }
    }

    /**
     * {@code SynchedEntityData#set(Accessor,Object)} 的 HEAD（**写同步血量字段本身**那一刻）。
     *
     * <h2>🆕 2026-11-20：这就是触发源第 ② 类 {@code health} 的另一条腿</h2>
     * {@code LivingEntity#setHealth} 只是通往同步字段的<b>一条</b>路；对手完全可以
     * <pre>
     *   entity.getEntityData().set(DATA_HEALTH_ID, 0.0f);   ← 一次 setHealth 都不调
     * </pre>
     * 这条写法<b>绕开</b> {@link #guardSetHealthHead}（那是挂在 {@code setHealth} 上的）。
     * 而 1.20.1 里同步字段的写入全世界只有 {@code SynchedEntityData#set} 那一个出口
     * （javap ＋ 源码双证，见 {@code ShanhaiHealthWriteClampMixin} 类注释）⇒ 挂这里就够。
     *
     * <p>判据只用来决定"要不要留一条栈"，<b>不影响</b>拦不拦（拦截由值层做）：
     * <ol>
     *   <li>{@code value} 是 {@code Float} 且<b>会读成死的</b>（{@code <=0 / NaN / ±∞}）—— 最便宜的闸门；</li>
     *   <li>这条同步数据的宿主是 {@code ServerPlayer}；</li>
     *   <li>宿主是受保护玩家；</li>
     *   <li>同一 {@code health|玩家} 1 秒内只抓一次（在 {@link ShanhaiViolationStack} 里）。</li>
     * </ol>
     *
     * @param synchedData {@code (Object) this}（那个 {@code SynchedEntityData}）
     * @param value       要写进去的值（此刻还没写进 {@code DataItem}）
     */
    public static void onHealthDataWrite(Object synchedData, Object value) {
        try {
            if (!(value instanceof Float f)) {
                return;                       // 不是 Float ⇒ 与血量无关
            }
            if (f.floatValue() > 0.0F && !Float.isInfinite(f.floatValue())) {
                return;                       // 正常值（受伤/回血）⇒ 不留证据，最便宜的一道闸门
            }
            final Object owner = ownerOfSyncedData(synchedData);
            if (!(owner instanceof ServerPlayer sp)) {
                return;                       // 生物照常会死；客户端 LocalPlayer 也不在这里抓
            }
            if (!ShanhaiStateAuthority.protectedPlayer(sp)) {
                return;
            }
            capture("health", sp, "src=data value=" + ShanhaiStateLogNum.num(f.floatValue())
                    + " note=direct_synched_data_write");
        } catch (Throwable ignored) {
            // 旁听失败只意味着"这一次没有栈"，绝不许影响写血量
        }
    }

    /**
     * 🆕 2026-11-21（P2 ②）：<b>{@code set(…Z)V} 那条腿钳住了</b> ⇒ 同一拍抓一次栈喂给溯源。
     *
     * <h2>为什么现在必须喂（现场读数）</h2>
     * <pre>
     *   现场：health_clamp 命中 284 次，而 where=health 的违规源 <b>0 条</b>
     *   ⇒ 值层把血钳回 20 之后<b>没有把现场交给溯源</b> ⇒ 整条链"看见对手"这一步从没发生
     * </pre>
     *
     * <h2>判据（调用方已经保证，这里只复核最后一道）</h2>
     * <pre>
     *   宿主必须是 Player（生物 / 载具照常会死，我们不记）      ⇒ 否则 return
     *   宿主必须受保护（{@link ShanhaiGuardEvents#isGuarded}，与写侧钳制同一份）⇒ 否则 return
     *   值必须"会读成死的"（{@code <=0 / NaN / ±∞}）         ⇒ 否则 return（正常受伤/回血不记）
     * </pre>
     *
     * <p>限流（同 tick 一条 ＋ 同类 200 tick 一条）在 {@link HotfixHealthClampStrike} 里 ——
     * 这里只在"真的钳住了"的那一拍调一次。
     *
     * @param synchedData {@code (Object) this}（那个 {@code SynchedEntityData}）
     * @param value       这次被钳掉的原值（{@code <=0 / NaN / ±∞}）
     */
    public static void onHealthClampDataWrite(Object synchedData, float value) {
        try {
            final Object owner = ownerOfSyncedData(synchedData);
            if (!(owner instanceof LivingEntity le) || !(owner instanceof Player player)) {
                return;                                   // 只保护玩家（生物照常会死）
            }
            if (!ShanhaiGuardEvents.isGuarded(player)) {
                return;                                   // 不受保护 ⇒ 原样放行，也不留证据
            }
            HotfixHealthClampStrike.onClampHit(true, value,
                    ShanhaiGuardValueLayer.readHealthField(le), le.getClass(), playerNameOf(player));
        } catch (Throwable ignored) {
            // 喂证据失败只意味着这一条没喂出去，绝不许影响写血
        }
    }

    /**
     * 🆕 2026-11-21（P2 ②）：<b>元数据同步把坏值灌进字段之后</b>（{@code assignValues} 的 RETURN，
     * 在我们的修复动作<b>之前</b>）⇒ 同一拍抓一次栈喂给溯源。
     *
     * <p>这条腿是现场的主力（{@code src=sync_data} 的 284 次就是它）：
     * {@code ClientPacketListener#handleSetEntityData → SynchedEntityData#assignValues}
     * 那条路不经过 {@code set(…Z)V} ⇒ {@link #onHealthDataWrite} 一次都命中不了。
     *
     * <p>判据与写侧钳制逐字同一套（宿主是受保护玩家 ＋ 读数已经会读成死的）；
     * 值取 {@code getHealth()}（此刻它就是刚灌进字段的那个坏值）。
     *
     * @param synchedData {@code (Object) this}（那个 {@code SynchedEntityData}）
     */
    public static void onHealthClampAssign(Object synchedData) {
        try {
            final Object owner = ownerOfSyncedData(synchedData);
            if (!(owner instanceof LivingEntity le) || !(owner instanceof Player player)) {
                return;
            }
            final float read = le.getHealth();              // 刚灌进字段的那个值（我们还没修）
            if (read > 0.0F && !Float.isInfinite(read)) {
                // 🔴 快路（顺序与值层的钳制逐字一样）：先比读数，再付"受保护判定"的钱。
                //   本方法挂在【每一包实体元数据】的 RETURN 上，正常值必须只花
                //   一次反射字段读 ＋ 一次 getHealth ＋ 一次 float 比较。
                return;                                   // 正常值 ⇒ 这一拍不会有钳制
            }
            if (!ShanhaiGuardEvents.isGuarded(player)) {
                return;                                   // 不受保护 ⇒ 原样放行，也不留证据
            }
            HotfixHealthClampStrike.onClampHit(true, read,
                    ShanhaiGuardValueLayer.readHealthField(le), le.getClass(), playerNameOf(player));
        } catch (Throwable ignored) {
            // 同上：喂证据失败不许影响"把同步数据灌进字段"这件原版行为
        }
    }

    /** 玩家名（只进日志/tap 配对；拿不到 ⇒ {@code ?}，本方法自己不许抛）。 */
    private static String playerNameOf(Player player) {
        try {
            return player.getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** {@code SynchedEntityData.entity}（{@code private final}；反射一次就缓存）。 */
    private static java.lang.reflect.Field SYNCED_DATA_ENTITY;

    /** 反射试过一次没有（失败也只试一次，不每次重试）。 */
    private static volatile boolean SYNCED_DATA_ENTITY_TRIED;

    /**
     * 这条同步数据属于谁。
     *
     * <p>两个名字都试：开发环境 {@code entity} ／ 生产 SRG {@code f_135344_}
     * （与本体 {@code ShanhaiGuardValueLayer} 的取法逐字同款，那边已经在生产跑过）。
     * 取不到 ⇒ {@code null} ⇒ 调用方<b>原样放行</b>（绝不因为"读不到"就瞎抓）。
     */
    private static Object ownerOfSyncedData(Object synchedData) {
        if (synchedData == null) {
            return null;
        }
        try {
            if (!SYNCED_DATA_ENTITY_TRIED) {
                synchronized (ShanhaiViolationProbe.class) {
                    if (!SYNCED_DATA_ENTITY_TRIED) {
                        SYNCED_DATA_ENTITY_TRIED = true;
                        Class<?> c = synchedData.getClass();
                        while (c != null && SYNCED_DATA_ENTITY == null) {
                            final String[] names = {"entity", "f_135344_"};
                            for (int i = 0; i < names.length; i++) {
                                try {
                                    final java.lang.reflect.Field fd = c.getDeclaredField(names[i]);
                                    fd.setAccessible(true);
                                    SYNCED_DATA_ENTITY = fd;
                                    break;
                                } catch (Throwable ignored) {
                                    // 试下一个名字
                                }
                            }
                            if (SYNCED_DATA_ENTITY == null) {
                                c = c.getSuperclass();
                            }
                        }
                    }
                }
            }
            final java.lang.reflect.Field fd = SYNCED_DATA_ENTITY;
            return fd == null ? null : fd.get(synchedData);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 伤害事件<b>被取消</b>的那一拍（{@code LivingAttackEvent} / {@code LivingHurtEvent}）。
     *
     * <p>🆕 2026-11-20：{@code where} 从 {@code attack} 改名为四类规范名 {@link #WHERE_HURT}。
     * 这不是新抓点，只是口径统一（旧名走 {@code HotfixTrigger} 的别名表也认）。
     */
    public static void onAttackCancelled(ServerPlayer sp, String site) {
        try {
            if (!ShanhaiStateAuthority.protectedPlayer(sp)) {
                return;
            }
            capture(WHERE_HURT, sp, "site=" + site + " cancelled=true");
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /**
     * 🆕 2026-11-20：<b>伤害事件本身</b>（不管有没有被取消）。
     *
     * <p>为什么这一条值得单开：{@code LivingHurtEvent} 是在 {@code LivingEntity#hurt} <b>里面</b>发的，
     * 所以此刻栈上还留着"<b>是谁调了 hurt</b>"—— 那正是"对手在打我们玩家"的那一帧。
     * 而"伤害被取消"只是其中一小部分情形（大多数攻击并没有被取消，只是伤害被削）。
     *
     * <p>成本：{@code StackWalker} 只在<b>受保护玩家挨打</b>时走一趟，并且同一 {@code hurt|玩家} 1/s 限流。
     *
     * @param sp     挨打的玩家
     * @param site   {@code LivingHurtEvent}
     * @param amount 这一下的伤害值（只进日志）
     */
    public static void onHurt(ServerPlayer sp, String site, float amount) {
        try {
            if (!ShanhaiStateAuthority.protectedPlayer(sp)) {
                return;
            }
            capture(WHERE_HURT, sp, "site=" + site + " amount=" + ShanhaiStateLogNum.num(amount));
        } catch (Throwable ignored) {
            // 抓不到栈不影响任何判定
        }
    }

    /** 四类规范名的本类快捷常量（与 {@link com.shanhai.add.hotfix.HotfixTrigger#HURT} 逐字一致）。 */
    public static final String WHERE_HURT = "hurt";

    // ═══════════════════════ 🆕 2026-11-20：第二层纠正日志用的「写者」回溯 ═══════════════════════

    /**
     * <b>「这个值最近一次是被谁写的」</b>—— 由<b>写入点</b>抓到的那个外部帧
     * （{@code 类#方法:行}），取不到 ⇒ {@code none_write_point}。
     *
     * <p>🔴 语义边界（不许含糊）：这个串<b>不是</b>在纠正那一刻抓的
     * —— 纠正发生在轮询点，那儿的栈里只有我们自己。它是"<b>同一个 {@code where|target}
     * 上一回在写入点抓到的那一帧</b>"。所以日志里必须同时写
     * {@code writer_age_ms=…}：太长就说明"这次偏差没有对应的写入点证据"，
     * 老实写 {@code none_write_point} 比编一个名字强。
     *
     * @param where  {@code health|die|remove|motion|motion_brake|pos|hurt}
     * @param sp     玩家
     * @return {@code 类#方法:行} ／ {@code none_write_point}
     */
    public static String lastWriter(String where, ServerPlayer sp) {
        if (where == null || sp == null) {
            return NO_WRITER;
        }
        try {
            final String v = LAST_WRITER.get(where + '|' + nameOf(sp));
            return v == null || v.isEmpty() ? NO_WRITER : v;
        } catch (Throwable ignored) {
            return NO_WRITER;
        }
    }

    /** 取不到写入点证据时的写法（<b>不编名字</b>：与 {@code ShanhaiStateLog} 的 {@code none_poll} 同一口径）。 */
    public static final String NO_WRITER = "none_write_point";

    /**
     * 🆕 2026-11-20：第二层纠正血/死亡态时，<b>顺手留一条写入点栈</b>（若这一刻有的话）。
     *
     * <p>为什么还要主动抓一次：{@link #lastWriter} 只能"回溯"，而有些写入点
     * （例如原版自己的 {@code setHealth}）会走 {@link #guardSetHealthHead} 留栈 ——
     * 但如果纠正发生在<b>同一 tick 内</b>且那一栈被 1/s 限流挡了，我们就只剩回溯值。
     * 这里<b>不做新的抓栈</b>（那会污染 {@code LAST_WRITER}，把"我们自己的帧"当成写者），
     * 只把结论如实返回。
     *
     * @return {@link #NO_WRITER} 或 {@code 类#方法:行}
     */
    public static String writerForCorrection(String where, ServerPlayer sp) {
        return lastWriter(where, sp);
    }

    // ═══════════════════════════════ 内部 ═══════════════════════════════

    /**
     * 抓 ＋ 打 ＋ 喂（<b>唯一的落地口径</b>）。
     *
     * @param where  {@code health|die|remove|motion|motion_brake|pos|attack}
     *               （{@code motion_brake} 是写入口"写入即丢"那一路，见
     *               {@link #onMotionBrakeDrop}）
     * @param sp     玩家
     * @param detail 附加证据（{@code key=value}，接在行尾）
     */
    private static void capture(String where, ServerPlayer sp, String detail) {
        // 🔴 2026-11-14：回放自己写世界时的"动量证据"没有任何信息量（每秒一条、target 还是玩家），
        //    会把真正的"外部改写"证据淹掉 ⇒ 只在【回放写临界区 + motion/motion_brake】豁免，
        //    health / die / attack / remove 一律照抓（判据核 = ShanhaiReplayGuardSkip，可离线断言）。
        try {
            if (com.shanhai.add.time.replay.ShanhaiReplayGuardSkip.skip(where,
                    com.shanhai.add.time.replay.ShanhaiReplayGate.inReplayWrite())) {
                final long n = com.shanhai.add.time.replay.ShanhaiReplayGuardSkip.note(where);
                final long now = System.currentTimeMillis();
                if (now - GUARD_SKIP_LAST_MS >= 5_000L) {
                    GUARD_SKIP_LAST_MS = now;
                    ShanhaiMod.LOGGER.info("[SHANHAI-GUARD] {}",
                            com.shanhai.add.time.replay.ShanhaiReplayGuardSkip.line(where, n));
                }
                return;
            }
        } catch (Throwable ignored) {
            // 豁免判定坏了 ⇒ 照旧抓（宁可多一条噪声，也不能漏证据）
        }
        final String player = nameOf(sp);
        final ShanhaiViolationStack.Hit hit = ShanhaiViolationStack.capture(where, player);
        if (hit == null) {
            return;                                   // 1/s 限流挡下（计数在 ShanhaiViolationStack 里）
        }
        count(where);
        // 🆕 2026-11-20：把「真正写这个值的那个外部帧」记下来给第二层的纠正日志用。
        //    只有抓点（写入点）会走到这里 ⇒ 记进来的一定是【写者】的帧，不是轮询点的我们。
        LAST_WRITER.put(where + '|' + player, hit.firstExternal);
        final StringBuilder line = new StringBuilder(ShanhaiViolationStack.line(hit));
        if (detail != null && !detail.isEmpty()) {
            line.append(' ').append(detail);
        }
        // 🆕 2026-11-08（用户口径）：凡打玩家朝向，**一律先规范化到 (-180,180]** 再进日志
        //   （不规范化就会打出 yaw=-993.45 那种越界值，读的人会以为是在看另一个玩家）。
        line.append(" yaw=").append(ShanhaiStateRules.yawText(sp.getYRot()))
                .append(" pitch=").append(ShanhaiStateRules.yawText(sp.getXRot()));
        try {
            ShanhaiMod.LOGGER.info("{}", line.toString());
        } catch (Throwable ignored) {
            // 日志出口坏了不许影响任何东西
        }
        ShanhaiViolationStack.feed(hit);              // → HotfixTriageTap 的 strike 通道
    }

    private static void count(String where) {
        synchronized (BY_WHERE) {
            AtomicLong n = BY_WHERE.get(where);
            if (n == null) {
                n = new AtomicLong();
                BY_WHERE.put(where, n);
            }
            n.incrementAndGet();
        }
    }

    private static String nameOf(ServerPlayer sp) {
        try {
            return sp.getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 按 {@code where} 分开的读数（命令 / 报告用）。 */
    public static String statusLine() {
        final StringBuilder sb = new StringBuilder(160);
        sb.append("violation_probe");
        synchronized (BY_WHERE) {
            for (Map.Entry<String, AtomicLong> e : BY_WHERE.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue().get());
            }
        }
        sb.append(' ').append(ShanhaiViolationStack.statusLine());
        return sb.toString();
    }

    /** 某个 {@code where} 抓到过几个现场（读数）。 */
    public static long countOf(String where) {
        synchronized (BY_WHERE) {
            final AtomicLong n = BY_WHERE.get(where);
            return n == null ? 0L : n.get();
        }
    }

    /**
     * 数值/三元组的日志写法（<b>纯字面量实现</b>：本类的 mixin 调用方在早期就会触碰本类，
     * 所以这里不引 {@code ShanhaiStateLog}，避免多一条加载依赖；口径与它逐字一致：三位小数、点号）。
     */
    static final class ShanhaiStateLogNum {

        private ShanhaiStateLogNum() {}

        static String num(double d) {
            if (Double.isNaN(d)) {
                return "NaN";
            }
            if (Double.isInfinite(d)) {
                return d > 0 ? "Infinity" : "-Infinity";
            }
            return String.format(java.util.Locale.ROOT, "%.3f", d);
        }

        static String vec(double x, double y, double z) {
            return "(" + num(x) + ", " + num(y) + ", " + num(z) + ")";
        }
    }
}
