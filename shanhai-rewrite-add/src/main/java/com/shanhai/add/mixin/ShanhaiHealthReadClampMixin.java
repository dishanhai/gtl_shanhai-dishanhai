package com.shanhai.add.mixin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.guard.state.ShanhaiStateLog;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardEvents;
import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · 【创始现实修改模块】<b>读取侧钳制</b>：受保护玩家读到的血量 {@code <=0 / NaN / ±∞}
 * ⇒ 这一次读取的返回值改写成 {@code getMaxHealth()}（<b>字段本身一个字节都不写</b>）。
 *
 * <h2>🔴 为什么是"读侧"，而不是再堵一个写入路径</h2>
 * <pre>
 *   写入路径堵不完：{@code setHealth} ／ 直写同步字段 ／ 各自的 helper ／ 网络包 ／ 事件总线上的热补丁
 *   —— 每一条都要单独挂一次、而且同一份类上打多个方法补丁会互相撤销（实机已踩过）。
 *   而【所有】读血的人（原版的判死 ／ 界面 ／ HUD ／ 第三方）最终都只经过同一个出口：
 *       LivingEntity#getHealth() →  public float m_21223_()
 *   ⇒ 只在这一个出口上做钳制，才与"谁写进去的"无关。
 * </pre>
 *
 * <h2>🔴 目标方法是 javap 查证的，不是猜的</h2>
 * <pre>
 *   javap -p -s -classpath &lt;forge-1.20.1-47.4.16-srg.jar&gt; net.minecraft.world.entity.LivingEntity
 *     public float m_21223_();
 *       descriptor: ()F
 *     public void m_21153_(float);          ← setHealth（既有那条线挂的就是它）
 *       descriptor: (F)V
 *     public final float m_21233_();        ← getMaxHealth（本类钳到哪）
 *       descriptor: ()F
 *
 *   同一份构建期 TSRG（build/mixinrefmap/obfuscation-tsrg-dev-to-srg.tsrg）第 121223 / 121232 行：
 *     getHealth     ()F  m_21223_
 *     getMaxHealth  ()F  m_21233_
 *
 *   方法体（javap -c）唯一出口、无分支：
 *       0: aload_0
 *       1: getfield  f_19804_   // LivingEntity.entityData
 *       4: getstatic f_20961_   // DATA_HEALTH_ID 那个 EntityDataAccessor
 *       7: invokevirtual m_135370_ // SynchedEntityData.get(accessor)
 *      10: checkcast java/lang/Float
 *      13: invokevirtual java/lang/Float.floatValue:()F
 *      16: freturn
 *   ⇒ 全长 17 字节、**只有一条 freturn** ⇒ {@code @At("RETURN")} 覆盖全部返回路径，
 *     不存在"漏掉某个 return 分支"这种形态。
 *
 *   javap 逐类查过：{@code Player} / {@code ServerPlayer} / {@code Mob} <b>都没有</b>自己声明 m_21223_
 *   （继承同一个方法）⇒ 挂在 {@code LivingEntity} 上<b>就覆盖了真实玩家</b>，不必逐个类挂。
 * </pre>
 *
 * <h2>🔴 语义（严格按需求，负向路径写死在 {@link Core#clampRead}）</h2>
 * <ol>
 *   <li>只对 {@code instanceof ServerPlayer} 且 {@link ShanhaiGuardEvents#isGuarded} 为真的玩家生效；</li>
 *   <li>读到的值 {@code <=0 / NaN / ±∞} ⇒ 返回 {@code getMaxHealth()}（<b>不递归调用 getHealth()</b>）；</li>
 *   <li>正常值 ⇒ <b>什么都不做</b>（不调用 {@code setReturnValue} ⇒ 原值逐位放行，不做任何"修正"）；</li>
 *   <li>全程 {@code try/catch(Throwable)}；任何异常 ⇒ 什么都不做（不设返回值）；</li>
 *   <li>🔴 非 {@code ServerPlayer} ／ 未受保护玩家 ／ 任何其它实体（怪物 / 载具 / 掉落物）
 *       ⇒ <b>逐字不变</b>（连受保护判定都不会调 —— 见 {@link Core#clampRead} 前三行的快路）。</li>
 * </ol>
 *
 * <h2>🔴 诚实边界（这一段是这条修法的全部代价，别当成免责声明划过）</h2>
 * <pre>
 *   ① 读侧钳制改的是「<b>世界看到的血量</b>」，<b>不改字段本身</b>：
 *      存档里仍可能存着 -∞（下次进世界又读到）⇒ 必须配合"进世界 / 每 tick 把字段也写正"那条线。
 *   ② 若第三方【直接读同步字段】（{@code getEntityData().get(DATA_HEALTH_ID)}）来决定下一步行为，
 *      读侧钳制对<b>它</b>无效 —— 但原版的判死（{@code isDeadOrDying()} 内部就是 {@code getHealth() <= 0}）／
 *      界面 ／ HUD 走的都是 {@code getHealth()} ⇒ 至少"玩家不会死、界面不会关不掉"这一条能保住。
 *   ③ 本类不改 {@code isDeadOrDying()} / {@code deathTime} / 伤害计算，一个字都不碰。
 *   ④ 与本类同一拍里别人的 RETURN 注入：谁后跑谁说了算 ⇒ 见下面「与已有注入的关系」。
 * </pre>
 *
 * <h2>🔴 与已有注入的关系（查过的东西写在报告里）</h2>
 * <pre>
 *   ✔ 本工程两个模块（本体 / 扩展）里【没有任何】注入挂在 getHealth 上：
 *       · 源码 grep {@code getHealth()F} / {@code m_21223_} 命中 0
 *       · 两份 refmap（shanhai.refmap.json / shanhai_add.refmap.json）里 m_21223_ 命中 0
 *   ⚠ 第三方可能在 RETURN 上加偏移（本工程既有取证记录里出现过这种形态）。
 *      即使如此本类也<b>不是</b>"两个 RETURN 注入语义互斥"：
 *      本类只在【读到的值已经会读成死】时才设返回值，正常值一个字节都不动 ⇒ 与"加偏移"那一路不抢正常路径；
 *      只有在值已经坏掉的那一拍，顺序才决定结果：
 *        · 它的回调在后 ⇒ 我们的 max 再被加一个有限偏移 ⇒ 仍为正 ⇒ 人活着；
 *        · 它的回调在前 ⇒ 我们钳的是它改写后的值 ⇒ 仍为死值就钳成 max。
 *      唯一守不住的是"它在 RETURN 上无条件覆盖返回值"，那种形态任何读侧钳制都守不住（如实写在报告里）。
 * </pre>
 *
 * <h2>🔴 为什么 {@code m_21223_} ＋ {@code remap = false} ＋ {@code require = 0}</h2>
 * <pre>
 *   · 用 SRG 名 + remap=false：生产环境（运行时就是 SRG）按字面命中，不依赖 refmap（本工程既有做法，
 *     见 {@code ShanhaiTickDeathGuardMixin} 的 m_6153_ / {@code ShanhaiHealthPacketClampMixin} 的 m_9240_）；
 *     ⚠ 代价：dev-run（官方映射，方法就叫 getHealth）里<b>不会命中</b>。
 *   · require=0（fail-soft，与 shanhai_add.mixin.json 四份配置 required:false 同口径）：
 *     命中不了的现象是【没有效果也不崩】+ Mixin 自己的 WARN。这一条必须能被判读 ⇒
 *     下次日志里若看到 {@code field=tickDeath … read_clamped=false hp_field=-Infinity}，
 *     那就是"字段已经死了、但读侧没有钳住"＝本类没挂上（或抛异常），不必再猜。
 * </pre>
 *
 * <h2>🔴 为什么本类体内零 Minecraft 成员引用，而判定逻辑放在同文件的 {@link Core}</h2>
 * 本工程铁律（{@code ShanhaiSetBlockWatchMixin} 类注释 §2 的构建产物实测）：mixin 类体内的成员引用
 * 在 refmap 里没有条目 ⇒ 生产环境拿开发名去 SRG 命名的方法上找 ⇒ 找不到。
 * ⇒ 本类只做三件事：形参 {@code CallbackInfoReturnable<Float>}、{@code (Object) this}、一次
 * {@link Core#clampRead} 调用；{@code getMaxHealth()} / {@code level()} / {@code isGuarded()} 全在
 * {@link Core}（<b>普通类，会被 reobf</b>）里做。
 * 为什么 {@link Core} 不单独开一个文件：这一轮的改动面被限定在"新 mixin 类 + mixin.json + 日志那一行"，
 * 不往本体（{@code com.shanhai.common.guard}）里加第 4 个文件。
 */
@Mixin(LivingEntity.class)
public abstract class ShanhaiHealthReadClampMixin {

    /**
     * {@code LivingEntity#getHealth()}（SRG：{@code m_21223_()}）的唯一出口。
     *
     * @param cir 原版返回值回调；只在 {@link Core#clampRead} 明确要求改写时才碰它
     */
    @Inject(
            method = "m_21223_",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 0)
    private void shanhai$clampHealthRead(CallbackInfoReturnable<Float> cir) {
        try {
            // (Object) this ⇒ 本类体内零 MC 成员引用（理由见类注释）。
            final Float clamped = Core.clampRead((Object) this, cir.getReturnValueF());
            if (clamped != null) {
                cir.setReturnValue(clamped);
            }
            // clamped == null ⇒ 一个字节都不动：原版读数逐位放行
        } catch (Throwable ignored) {
            // 第二道保险：我们坏了，也绝不许改变"读血量"这件原版行为（不设返回值）
        }
    }

    /**
     * 判定与钳制的执行体（<b>普通类，会被 reobf</b>；mixin 类体不引 MC 成员）。
     *
     * <h2>成本（为什么敢挂在这么热的读接口上）</h2>
     * <pre>
     *   非 ServerPlayer（怪物 / 载具 / 掉落物 / 客户端本地玩家）  ⇒ 一次类型检查就返回；
     *   ServerPlayer 但读数是正常值                            ⇒ 一次 {@link ShanhaiGuardValueLayer#needsHealthClamp} 就返回；
     *   只有【受保护玩家 且 读数已经会读成死】这一种形态才会走到物品栏判定与日志（日志另有 1/s 限流）。
     * </pre>
     *
     * <h2>负向路径（逐条写"不做什么"）</h2>
     * <pre>
     *   self 不是 ServerPlayer        ⇒ null（不设返回值；怪物/载具/掉落物/别人 一个字节都不变）
     *   读数正常（>0 且有限）          ⇒ null（不设返回值；绝不做"向上修正"或"取整"这类事）
     *   重入（本线程正在钳制中）        ⇒ null（防 isGuarded / getMaxHealth 内部再读血造成递归）
     *   isGuarded == false           ⇒ null（不受保护 ⇒ 不许阻断正常死亡）
     *   getMaxHealth() 不可用         ⇒ null（硬写 NaN / ∞ 比什么都不做更糟，与本工程既有纪律一致）
     *   本方法抛任何 Throwable        ⇒ null（我们坏掉时 100% 退化成原版）
     * </pre>
     */
    public static final class Core {

        private Core() {}

        /** 没有"上一拍"这个概念的初始值。 */
        private static final long NO_TICK = Long.MIN_VALUE;

        /** 本线程是否正在钳制中（重入闸门）。 */
        private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> Boolean.FALSE);

        /** 累计钳了几次读取（进日志；也是"这条修法到底有没有在跑"的读数）。 */
        private static final AtomicLong CLAMPED_READS = new AtomicLong();

        /** 最近一次钳制发生在谁身上、哪一拍（{@code UUID → gameTime}；与 tick 一起判，避免"别人的钳制算到这个玩家头上"）。 */
        private static final Map<UUID, Long> LAST_CLAMP_TICK_BY_PLAYER = new ConcurrentHashMap<>();

        /** 钳制日志的 1/s 限流（毫秒时间戳）。 */
        private static long lastLogMillis = 0L;

        /** 被限流压掉的行数（下一次真正落盘的那一行带 {@code suppressed=}）。 */
        private static final AtomicLong LOG_SUPPRESSED = new AtomicLong();

        /**
         * 🔴 <b>判定 ＋ 钳制</b>（由 {@link ShanhaiHealthReadClampMixin} 转发进来）。
         *
         * @param self 原版 {@code this}（{@code Object}：mixin 类体不引 MC 成员）
         * @param read 原版 {@code getHealth()} 这次真正返回的值（{@code RETURN} 处）
         * @return {@code null} = <b>什么都不改</b>（原值逐位放行）；非 null = 这一次读取要用的值
         */
        public static Float clampRead(Object self, float read) {
            try {
                // ① 非玩家（怪物 / 载具 / 掉落物 / 客户端本地玩家）⇒ 一次类型检查就退出去：
                //    连受保护判定都不调，绝不给它们增加任何行为。
                if (!(self instanceof ServerPlayer sp)) {
                    return null;
                }
                // ② 正常值 ⇒ 逐位放行（判据复用全工程唯一那一份，不另写一套）
                if (!ShanhaiGuardValueLayer.needsHealthClamp(read)) {
                    return null;
                }
                // ③ 重入闸门：isGuarded / getMaxHealth / 日志 内部若再读血，那一层什么都不做（不递归钳制）
                if (INSIDE.get().booleanValue()) {
                    return null;
                }
                INSIDE.set(Boolean.TRUE);
                try {
                    // ④ 唯一受保护入口（与伤害/死亡/死亡界面/写入钳制同一份判据）
                    if (!ShanhaiGuardEvents.isGuarded(sp)) {
                        return null;
                    }
                    // ⑤ 钳到哪：getMaxHealth()（🔴 绝不调用 getHealth() —— 会自己递归进本方法）
                    final float max = sp.getMaxHealth();
                    if (!usableMax(max)) {
                        return null;        // 上限不可用 ⇒ 一步都不做（写 NaN 比 -∞ 更糟）
                    }
                    final long tick = gameTime(sp);
                    CLAMPED_READS.incrementAndGet();
                    rememberTick(sp, tick);
                    noteClamp(sp, read, max, tick);
                    return Float.valueOf(max);
                } finally {
                    INSIDE.set(Boolean.FALSE);
                }
            } catch (Throwable ignored) {
                return null;                    // 我们坏了 ⇒ 原版读数逐位不变
            }
        }

        /**
         * 🔴 <b>给「拦截 状态被改写 ｜ field=tickDeath」那一行补的两个字段</b>（用户点名要的）。
         *
         * <p>目的：下次一眼看出"字段是 -∞、但读取被钳成了 20"。
         * <pre>
         *   read_clamped=true   这一拍里、这个玩家的读取被钳过（＝读侧保护在跑，而且真的命中了）
         *   read_clamped=false  这一拍没钳过（⚠ 若同时 hp_field=-Infinity ⇒ 读侧没钳住 ⇒ 本类没挂上/抛异常）
         *   hp_field=&lt;数&gt;     同步血量字段真值（{@code DATA_HEALTH_ID}，不是 getHealth()）
         *   hp_field=unknown    读不到（反射未就绪 / 读取抛异常；⚠ 字段真值本来就是 NaN 时也落到这里）
         * </pre>
         *
         * @param self 原版 {@code this}（这里恒为 {@code ServerPlayer}）
         * @return 以<b>空格开头</b>的字段串，可直接拼进日志行尾；任何异常 ⇒ 两个字段都写 {@code unknown}
         */
        public static String fieldsForTickDeathLog(Object self) {
            try {
                if (!(self instanceof ServerPlayer sp)) {
                    return " read_clamped=unknown hp_field=unknown";
                }
                final long now = gameTime(sp);
                final Long last = LAST_CLAMP_TICK_BY_PLAYER.get(sp.getUUID());
                final boolean clamped = now != NO_TICK && last != null && last.longValue() == now;
                final float field = ShanhaiGuardValueLayer.readHealthField(sp);
                return " read_clamped=" + clamped
                        + " hp_field=" + (Float.isNaN(field) ? "unknown" : ShanhaiStateLog.num(field));
            } catch (Throwable ignored) {
                return " read_clamped=unknown hp_field=unknown";
            }
        }

        // ═══════════════════════════════ 内部小工具 ═══════════════════════════════

        /** 上限能不能拿来当返回值：{@code >0} 且有限（NaN 也会在这里被挡掉）。 */
        private static boolean usableMax(float max) {
            return max > 0.0F && !Float.isInfinite(max);
        }

        /** 这个实体所在 level 的 gameTime（读不到 ⇒ {@link #NO_TICK}，绝不当 0 用）。 */
        private static long gameTime(ServerPlayer sp) {
            try {
                final Level level = sp.level();
                return level == null ? NO_TICK : level.getGameTime();
            } catch (Throwable ignored) {
                return NO_TICK;
            }
        }

        /** 记下"这个玩家的读取在这一拍被钳过"（{@link #fieldsForTickDeathLog} 用；表有界，防泄漏）。 */
        private static void rememberTick(ServerPlayer sp, long tick) {
            if (tick == NO_TICK) {
                return;                             // 读不到 gameTime ⇒ 不记（宁可报 false，也不编一个 tick）
            }
            try {
                final Map<UUID, Long> map = LAST_CLAMP_TICK_BY_PLAYER;
                if (map.size() > 64) {
                    map.clear();                    // 玩家数远小于 64；溢出就整个丢掉（诊断用，丢了不影响钳制）
                }
                map.put(sp.getUUID(), Long.valueOf(tick));
            } catch (Throwable ignored) {
                // 诊断状态写不进去 ⇒ 只是 read_clamped 少一次 true，钳制本身不受影响
            }
        }

        /**
         * 落一行诊断（<b>全局限流 1 行/秒</b>，被压掉的次数累计进下一行 {@code suppressed=}）。
         *
         * <p>行形态（可 grep）：
         * {@code [SHANHAI-GUARD] 读取侧钳制 read_clamp player=… from=-Infinity to=20.000 field=-Infinity tick=…}
         */
        private static void noteClamp(ServerPlayer sp, float read, float max, long tick) {
            final long now = System.currentTimeMillis();
            final long suppressed;
            synchronized (Core.class) {
                if (lastLogMillis != 0L && now - lastLogMillis < 1000L) {
                    LOG_SUPPRESSED.incrementAndGet();
                    return;
                }
                lastLogMillis = now;
                suppressed = LOG_SUPPRESSED.getAndSet(0L);
            }
            try {
                final float field = ShanhaiGuardValueLayer.readHealthField(sp);
                ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX
                        + "读取侧钳制 read_clamp player=" + playerName(sp)
                        + " from=" + ShanhaiStateLog.num(read)
                        + " to=" + ShanhaiStateLog.num(max)
                        + " field=" + (Float.isNaN(field) ? "unknown" : ShanhaiStateLog.num(field))
                        + " tick=" + tick
                        + " read_clamped_total=" + CLAMPED_READS.get()
                        + (suppressed > 0L ? " suppressed=" + suppressed : ""));
            } catch (Throwable ignored) {
                // 诊断行坏了不许影响读数
            }
        }

        /** 玩家名（只进日志；拿不到 ⇒ {@code ?}，诊断行自己不许再抛）。 */
        private static String playerName(ServerPlayer sp) {
            try {
                return sp.getName().getString();
            } catch (Throwable ignored) {
                return "?";
            }
        }
    }
}
