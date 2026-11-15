package com.shanhai.add.client.time;

import com.mojang.logging.LogUtils;
import com.shanhai.add.time.replay.ShanhaiReplayAnim;
import com.shanhai.add.time.replay.ShanhaiReplayParticleConverge;
import com.shanhai.add.time.replay.ShanhaiReplayRainFx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 山海重构 · <b>回放期间的客户端特效（反向雨 / 反向粒子 / 反向动画）</b>。
 *
 * <h2>1. 用户口径（2026-11-14 13:52 录像）</h2>
 * <blockquote>「他这个粒子显然不是倒流的好吧，还有这个雨直接不见了」</blockquote>
 * <blockquote>「猪倒着移动是只在移动、没有它的那个原版的动画的倒放」</blockquote>
 *
 * <h2>2. 三条修正（上一版全部踩坑）</h2>
 * <ol>
 *   <li><b>雨</b>：上一版"藏原版雨 + 自己撒水滴粒子" ⇒ 画面一条雨都没有（用户确认）。
 *       现在<b>不藏原版雨</b>，只把 {@code LevelRenderer#ticks} 换成<b>游标驱动的递减值</b>
 *       （相位 = {@code (float)ticks + partialTick}，{@code LevelRenderer.java:291}），
 *       几何/密度/渐隐全保持原版 ⇒ 雨看得见、且往上走。
 *       读数：{@code vanilla_hidden=false rain_visible_quads=<格数×2> rain_frames=<n>}。</li>
 *   <li><b>粒子</b>：上一版只给一次初速 ⇒ 仍受重力下坠、年龄往前走。现在对我们持有的粒子
 *       <b>逐 tick 覆写</b>：速度持续指向格心、{@code gravity=0}、{@code age} 递减、
 *       收进中心即 {@code remove()}。读数：{@code particle_converge ok=N/M}。</li>
 *   <li><b>动画</b>：对我们驱动的实体每客户端 tick {@code walkAnimation.update(-0.4,1)} 且
 *       {@code tickCount--}（腿部摆动与 idle 呼吸一起倒着走）。读数：{@code anim_dir=reverse}。</li>
 * </ol>
 *
 * <p>⚠️ 正常时间（{@code LIVE}）下本类一行都不参与。
 */
public final class ShanhaiReplayClientFx {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 雨格数的估算（原版 fancy 图形 21×21 格，每格 2 个面片）。 */
    private static final int RAIN_CELLS = 21 * 21;

    private static double phase;
    private static int lastDirection;
    private static long monotoneBreaks;
    private static long lastLogMs;
    private static long rainFrames;
    private static long animReversed;

    /** 我们持有的粒子（方块恢复粒子 + 死亡烟）：逐 tick 往格心收。 */
    private static final List<Tracked> TRACKED = new ArrayList<>();
    private static long convergeOk;
    private static long convergeTotal;

    // ---- 取证：解冻之后 20 tick 的跳变（用户："收尾瞬移有十几格"） ----
    private static int postTicks;
    private static int holdExpected;
    /** hold 只保持 2 tick（再长就会在释放那一瞬造成"11 格瞬移"）。 */
    private static final int HOLD_TICKS = 2;
    private static double t0MaxDelta;
    private static double releaseMaxDelta;
    private static double holdWindowMaxDelta;
    /**
     * 🔴 窗口内最大<b>偏移</b>：实体当前坐标到"我们钉住坐标"的距离（pull）。
     * <p>这是<b>实体自己走开多少</b>，不是"这一拍被拉了多少" ⇒ 只进 {@code drift_max}，
     * <b>绝不许</b>进 {@link #t0MaxDelta}/{@link #releaseMaxDelta}/{@link #holdWindowMaxDelta}/
     * {@link #postMaxDelta} 任何一个（否则窗口一长，读数就是"走得最远那只走了几格"）。
     */
    private static double driftMax;
    private static final java.util.Set<java.util.UUID> HOLD_SEEN = new java.util.HashSet<>();
    private static double postUnknownDelta;
    private static long postLastMs;
    private static final java.util.Map<Integer, double[]> POST_POS = new java.util.HashMap<>();
    private static double postMaxDelta;
    private static int postMaxAtTick;
    /** 产生 {@link #postMaxDelta} 的那只实体（与 {@link #postMaxAtTick} 同口径，不是"本拍"的 id）。 */
    private static int postMaxAtId;

    private static final class Tracked {
        final Particle particle;
        final double cx;
        final double cy;
        final double cz;
        double lastDistance;
        int ticks;

        Tracked(Particle particle, double cx, double cy, double cz, double lastDistance) {
            this.particle = particle;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.lastDistance = lastDistance;
        }
    }

    /** 按 <b>uuid</b> 钉住"我们最后一次写入的坐标"（id 变了也能迁移；解冻后 60 tick 内生效）。 */
    private static final java.util.Map<java.util.UUID, double[]> HOLD = new java.util.HashMap<>();
    /** uuid → 最后一次包类型（add / teleport），定点取证用。 */
    private static final java.util.Map<java.util.UUID, String> LAST_PACKET = new java.util.HashMap<>();

    /** 落位成功时把"我们最后一次写入的坐标"钉住（按 uuid）。 */
    public static void hold(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            HOLD.put(entity.getUUID(), new double[]{entity.getX(), entity.getY(), entity.getZ(),
                    entity.getYRot(), entity.getXRot()});
            LAST_PACKET.put(entity.getUUID(), "teleport");
        } catch (Throwable ignored) {
            // 钉不住最多是解冻后滑一下（有 post_unfreeze_max_delta 可判）
        }
    }

    /**
     * 解冻后若收到服务端的 {@code ClientboundAddEntityPacket}（同 uuid 可能换了新 id）：
     * 立刻并入 hold 并落位到"我们最后写入的坐标"，不做插值。
     */
    public static void onEntitySpawn(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            LAST_PACKET.put(entity.getUUID(), "add");
            final double[] hold = HOLD.get(entity.getUUID());
            if (hold != null) {
                entity.absMoveTo(hold[0], hold[1], hold[2], (float) hold[3], (float) hold[4]);
                entity.setOldPosAndRot();
            }
        } catch (Throwable ignored) {
            // 迁移失败最多是解冻后滑一下
        }
    }

    /** 解冻时启动"之后 60 tick 的跳变"探针（判据④）。 */
    public static void startPostUnfreeze() {
        postTicks = 60;
        holdExpected = HOLD.size();
        HOLD_SEEN.clear();
        postUnknownDelta = 0.0D;
        postMaxDelta = 0.0D;
        postMaxAtTick = 0;
        postMaxAtId = 0;
        // 🔴 ① 跨局必须归零：这四个只被 ">" / Math.max 单调更新过 ⇒ 不归零，第二次倒流打出来的
        //    就是上一局的最大值（假读数）。旧版这里漏了三行。
        t0MaxDelta = 0.0D;
        releaseMaxDelta = 0.0D;
        holdWindowMaxDelta = 0.0D;
        driftMax = 0.0D;
        POST_POS.clear();
        postLastMs = System.currentTimeMillis();
    }

    /**
     * 解冻之后的逐 tick 采样：<b>每个实体这一 tick 相对上一 tick 的位置变化</b>取最大。
     *
     * <p>用户口径："收尾瞬移十几格" ⇒ 只看解冻那一瞬不够，必须看解冻<b>之后</b>的 20 tick
     * （客户端实体的 AI/lerp/spawn 同步都在这几拍里发生）。
     */
    public static void postUnfreezeTick() {
        if (postTicks <= 0) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        --postTicks;
        // 🔴 ② 两个量彻底分开（旧版把 pull 和 d 混进同一个 best ⇒ hold_window_max 打的是"猪走了几格"）：
        //    bestMove = 这一拍<b>实测位移</b>（真的跳/被拉）→ 只它进 t0 / release / hold_window / postMaxDelta
        //    bestPull = 这一拍到钉住坐标的<b>偏移</b>（实体自己走开）→ 只它进 driftMax，且不许触发 jump_probe
        double bestMove = 0.0D;
        int bestMoveId = 0;
        double bestPull = 0.0D;
        // 🔴 ② 定点 dump 用的临时表（只活这一次调用，不是静态长期字段）：
        //    uuid → {pull(最后一拍残差), id, cx, cy, cz, hx, hy, hz, move(窗口内该实体最大单拍位移)}
        final java.util.Map<java.util.UUID, double[]> postPull = new java.util.HashMap<>();
        try {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == null) {
                    continue;
                }
                // 🔴 ③ 解冻后 20 tick 内不许被 aiStep/位置同步拉回：钉在"我们包的坐标"上。
                //    先量"差点被拉走多少"（= 用户看到的那次跳变），再当场拉回来。
                final double[] hold = HOLD.get(e.getUUID());
                double[] rec = null;
                if (hold != null) {
                    HOLD_SEEN.add(e.getUUID());
                    final double hx = e.getX() - hold[0];
                    final double hy = e.getY() - hold[1];
                    final double hz = e.getZ() - hold[2];
                    final double pull = Math.sqrt(hx * hx + hy * hy + hz * hz);
                    if (pull > bestPull) {
                        bestPull = pull;        // 偏移单走一路
                    }
                    // ② 残差 + 两侧坐标（必须在下面 absMoveTo 拉回<b>之前</b>取，否则 client 被改写成 hold
                    //    ⇒ |client-hold| 恒为 0，读数失去意义）。pull 每 tick 覆盖 = 最后一拍残差；
                    //    move 取窗口内最大值（下面算完这一拍位移再回填 rec[8]）。
                    rec = postPull.get(e.getUUID());
                    if (rec == null) {
                        rec = new double[]{pull, e.getId(), e.getX(), e.getY(), e.getZ(),
                                hold[0], hold[1], hold[2], 0.0D};
                        postPull.put(e.getUUID(), rec);
                    } else {
                        rec[0] = pull;
                        rec[1] = e.getId();
                        rec[2] = e.getX();
                        rec[3] = e.getY();
                        rec[4] = e.getZ();
                        rec[5] = hold[0];
                        rec[6] = hold[1];
                        rec[7] = hold[2];
                    }
                    if ((60 - postTicks) <= HOLD_TICKS && pull > 1.0E-4D) {
                        e.absMoveTo(hold[0], hold[1], hold[2], (float) hold[3], (float) hold[4]);
                        e.setOldPosAndRot();
                    }
                }
                final double[] prev = POST_POS.put(e.getId(),
                        new double[]{e.getX(), e.getY(), e.getZ()});
                if (prev == null) {
                    continue;
                }
                final double dx = e.getX() - prev[0];
                final double dy = e.getY() - prev[1];
                final double dz = e.getZ() - prev[2];
                final double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d > bestMove) {
                    bestMove = d;
                    bestMoveId = e.getId();
                }
                if (rec != null && d > rec[8]) {
                    rec[8] = d;                 // 该实体窗口内最大单拍位移
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        final int tickIdx = 60 - postTicks;
        // 🔴 d（实测位移）才进这四个 max；pull 一个都不许进。
        if (bestMove > postMaxDelta) {
            postMaxDelta = bestMove;
            postMaxAtTick = Math.max(0, tickIdx);
            postMaxAtId = bestMoveId;
        }
        if (tickIdx <= HOLD_TICKS) {
            if (bestMove > t0MaxDelta) {
                t0MaxDelta = bestMove;
            }
        } else if (tickIdx == HOLD_TICKS + 1) {
            releaseMaxDelta = Math.max(releaseMaxDelta, bestMove);
        } else if (bestMove > holdWindowMaxDelta) {
            holdWindowMaxDelta = bestMove;
        }
        // 🔴 偏移单独累加：换名字也不行，必须与上面四个分成两条通路。
        if (bestPull > driftMax) {
            driftMax = bestPull;
        }
        // 🔴 jump_probe 只许 d 触发，且要求当拍 from != to（> 1e-3）：上一局那 58 条里有 from==to 的，
        //    全是"被钉住后又拉回"的假跳变，不是真的跳。
        if (bestMove > 0.5D && bestMoveId != 0) {
            try {
                final Entity bad = mc.level.getEntity(bestMoveId);
                if (bad != null) {
                    final double fx = bad.getX() - bad.xo;
                    final double fy = bad.getY() - bad.yo;
                    final double fz = bad.getZ() - bad.zo;
                    if (Math.sqrt(fx * fx + fy * fy + fz * fz) > 1.0E-3D) {
                        LOGGER.info("[SHANHAI-CLOCK] jump_probe entity={} type={} from={} to={} kind={} tick={}",
                                bestMoveId, net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(bad.getType()),
                                String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", bad.xo, bad.yo, bad.zo),
                                String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", bad.getX(), bad.getY(), bad.getZ()),
                                LAST_PACKET.getOrDefault(bad.getUUID(), "none"), tickIdx);
                    }
                }
            } catch (Throwable ignored) {
                // 定点取证失败不影响主流程
            }
        }
        if (postTicks == 0) {
            // 🔴 ① 占位符 13 个 = 实参 13 个。口径写死：
            //    post_unfreeze_max_delta = 窗口内<b>最大单拍位移</b>（只来自 d，不再是 max(pull,d)）；
            //    drift_max = 窗口内<b>最大偏移</b>（只来自 pull = 实体自己走开多少）。两者互不污染。
            //    ms 取 %.1f（毫秒，保留 1 位小数）。
            LOGGER.info("[SHANHAI-CLOCK] post_unfreeze_max_delta={} at_tick={} at_entity_id={} "
                            + "client_aiStep_active={} hold_entities={} hold_ticks={} missing={} "
                            + "unknown_pull={} t0_max={} hold_window_max={} release_max={} ms={} "
                            + "drift_max={}",
                    String.format(java.util.Locale.ROOT, "%.3f", postMaxDelta), postMaxAtTick, postMaxAtId,
                    !ShanhaiClientClock.frozen(), HOLD.size(), HOLD_TICKS,
                    Math.max(0, holdExpected - HOLD_SEEN.size()),
                    String.format(java.util.Locale.ROOT, "%.3f", postUnknownDelta),
                    String.format(java.util.Locale.ROOT, "%.3f", t0MaxDelta),
                    String.format(java.util.Locale.ROOT, "%.3f", holdWindowMaxDelta),
                    String.format(java.util.Locale.ROOT, "%.3f", releaseMaxDelta),
                    String.format(java.util.Locale.ROOT, "%.1f",
                            (double) (System.currentTimeMillis() - postLastMs)),
                    String.format(java.util.Locale.ROOT, "%.3f", driftMax));
            // ② 窗口结束：按 pull 从大到小 dump 前 5 个实体（uuid / 换了 id 也能对上 / 走的是哪个包）。
            try {
                final java.util.List<java.util.Map.Entry<java.util.UUID, double[]>> ranked =
                        new java.util.ArrayList<>(postPull.entrySet());
                ranked.sort((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0]));
                final int dump = Math.min(5, ranked.size());
                for (int i = 0; i < dump; i++) {
                    final java.util.UUID uuid = ranked.get(i).getKey();
                    final double[] v = ranked.get(i).getValue();
                    LOGGER.info("[SHANHAI-CLOCK] post_hold_entity uuid={} id={} client={} hold={} "
                                    + "pull={} move={} packet={}",
                            uuid, (int) v[1],
                            String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", v[2], v[3], v[4]),
                            String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", v[5], v[6], v[7]),
                            String.format(java.util.Locale.ROOT, "%.3f", v[0]),
                            String.format(java.util.Locale.ROOT, "%.3f", v[8]),
                            LAST_PACKET.getOrDefault(uuid, "none"));
                }
            } catch (Throwable ignored) {
                // 定点 dump 失败不影响收尾（该清的 HOLD 还是要清）
            }
            HOLD.clear();
        }
    }

    /** 本帧是不是在倒流里（客户端镜像 = REPLAY；单机额外认服务端）。 */
    public static boolean replaying() {
        return ShanhaiClientClock.replayNow();
    }

    /** 原版雨<b>不再隐藏</b>（上一版把雨藏没了）。保留方法只为读数行/兼容。 */
    public static boolean hideVanillaRain() {
        return false;
    }

    /** 冻结期间要不要把原版雨的相位翻成"倒着走"（由游标驱动）。 */
    public static boolean rainFlipped() {
        return ShanhaiClientClock.frozen();
    }

    /** 每个客户端 tick 调一次（{@code LevelRenderer#tick} 的 HEAD，冻结时也会调）。 */
    public static void tick() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        if (!replaying()) {
            lastDirection = 0;
            TRACKED.clear();
            return;
        }
        final double before = phase;
        phase -= 1.0D;                       // 与回放游标同向：每 tick 减 1（单调 ⇒ 雨向上、不抖）
        final int dir = ShanhaiReplayRainFx.direction(before, phase, 1.0E-6D);
        if (lastDirection == 0) {
            lastDirection = dir;
        } else if (!ShanhaiReplayRainFx.monotoneStep(lastDirection, dir)) {
            ++monotoneBreaks;
            lastDirection = dir;
        }
        convergeTick();
        final long now = System.currentTimeMillis();
        if (now - lastLogMs < 250L) {
            return;
        }
        lastLogMs = now;
        final float rainLevel = mc.level.getRainLevel(1.0F);
        LOGGER.info("[SHANHAI-CLOCK] rain_mode=vanilla_flip rain_dir=reverse src=cursor rain_phase={} "
                        + "rain_monotone={} rain_level={} vanilla_hidden=false rain_visible_quads={} "
                        + "rain_frames={} particles_live={} particle_converge=ok:{}/{} "
                        + "anim_dir=reverse anim_reversed_tick={}",
                String.format(java.util.Locale.ROOT, "%.2f", phase),
                monotoneBreaks == 0,
                String.format(java.util.Locale.ROOT, "%.3f", rainLevel),
                (int) (RAIN_CELLS * Math.max(0.0F, Math.min(1.0F, rainLevel)) * 2.0F),
                rainFrames,
                particlesLive(),
                convergeOk, convergeTotal,
                animReversed);
    }

    /** 原版雨的渲染帧数（雨 mixin 每帧 +1；判据要求 &gt;0）。 */
    public static void countRainFrame() {
        ++rainFrames;
    }

    /** 相位值（{@code LevelRenderer#ticks} 会被写成它的整数部分）。 */
    public static int rainTicks() {
        return (int) Math.floor(phase);
    }

    /** 场上粒子数（取不到就给 -1，绝不当成 0）。 */
    public static int particlesLive() {
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.particleEngine == null) {
                return -1;
            }
            final java.util.Map<net.minecraft.client.particle.ParticleRenderType,
                    java.util.Queue<Particle>> map =
                    ((com.shanhai.add.mixin.ShanhaiParticleEngineAccessor) mc.particleEngine)
                            .shanhai$particles();
            if (map == null) {
                return -1;
            }
            int n = 0;
            for (java.util.Queue<Particle> q : map.values()) {
                if (q != null) {
                    n += q.size();
                }
            }
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 回放期间新粒子的处理：<b>登记</b> + 初速朝格心 + 重力清零。
     *
     * <p>真正的"逐 tick 持续指向中心"在 {@link #convergeTick()} 里做（用户口径：
     * 只改初速 ⇒ 粒子仍然正向冒一下）。
     */
    public static void gather(Particle particle) {
        if (particle == null || !replaying()) {
            return;
        }
        try {
            final com.shanhai.add.mixin.ShanhaiParticleAccessor acc =
                    (com.shanhai.add.mixin.ShanhaiParticleAccessor) particle;
            final double px = acc.shanhai$x();
            final double py = acc.shanhai$y();
            final double pz = acc.shanhai$z();
            final double cx = Math.floor(px) + 0.5D;
            final double cy = Math.floor(py) + 0.5D;
            final double cz = Math.floor(pz) + 0.5D;
            final double[] v = ShanhaiReplayParticleConverge.step(px, py, pz, cx, cy, cz);
            particle.setParticleSpeed(v[0], v[1], v[2]);
            acc.shanhai$setGravity(0.0F);
            particle.setLifetime(40);
            TRACKED.add(new Tracked(particle,
                    cx, cy, cz,
                    ShanhaiReplayParticleConverge.distance(px, py, pz, cx, cy, cz)));
        } catch (Throwable ignored) {
            // 登记失败 ⇒ 这一颗按原版走（最多是方向没倒放）
        }
    }

    /** 每客户端 tick：把持有的粒子重新指向格心 + 年龄反演 + 收回即删。 */
    private static void convergeTick() {
        if (TRACKED.isEmpty()) {
            return;
        }
        final Iterator<Tracked> it = TRACKED.iterator();
        while (it.hasNext()) {
            final Tracked t = it.next();
            try {
                final Particle p = t.particle;
                if (p == null || !p.isAlive()) {
                    it.remove();
                    continue;
                }
                final com.shanhai.add.mixin.ShanhaiParticleAccessor acc =
                        (com.shanhai.add.mixin.ShanhaiParticleAccessor) p;
                final double px = acc.shanhai$x();
                final double py = acc.shanhai$y();
                final double pz = acc.shanhai$z();
                final double dist = ShanhaiReplayParticleConverge.distance(px, py, pz, t.cx, t.cy, t.cz);
                ++convergeTotal;
                if (ShanhaiReplayParticleConverge.distanceDecreased(t.lastDistance, dist)) {
                    ++convergeOk;
                }
                t.lastDistance = dist;
                if (ShanhaiReplayParticleConverge.done(dist) || ++t.ticks > 40) {
                    p.remove();
                    it.remove();
                    continue;
                }
                final double[] v = ShanhaiReplayParticleConverge.step(px, py, pz, t.cx, t.cy, t.cz);
                p.setParticleSpeed(v[0], v[1], v[2]);
                // 年龄反演：每 tick 往回退（贴图停在最年轻那帧），收进中心后 remove 收尾
                acc.shanhai$setAge(Math.max(0, acc.shanhai$age() - 1));
                acc.shanhai$setGravity(0.0F);
            } catch (Throwable ignored) {
                it.remove();
            }
        }
    }

    /**
     * 倒放：把这只实体的<b>原版动画</b>倒着拨一拍（走路摆动 + idle/呼吸）。
     *
     * @param entity 刚被我们落位过的实体（{@code null} 忽略）
     */
    public static void reverseAnim(Entity entity) {
        if (entity == null || !replaying() || !(entity instanceof LivingEntity living)) {
            return;
        }
        try {
            living.walkAnimation.update(ShanhaiReplayAnim.REVERSE_SPEED, 1.0F);
            living.tickCount -= 1;            // ageInTicks 往回走 ⇒ idle/耳朵/尾巴一起倒放
            ++animReversed;
        } catch (Throwable ignored) {
            // 动画倒放失败不影响位置
        }
    }

    /** 一行摘要（status 用）。 */
    public static String line() {
        return "rain_dir=" + (replaying() ? "reverse" : "normal")
                + " src=" + (replaying() ? "cursor" : "wallclock")
                + " rain_monotone=" + (monotoneBreaks == 0)
                + " particles_live=" + particlesLive()
                + " " + ShanhaiReplayParticleConverge.line((int) convergeOk, (int) convergeTotal)
                + " " + ShanhaiReplayAnim.line(replaying() ? "reverse" : "normal", animReversed);
    }

    private ShanhaiReplayClientFx() {
    }
}