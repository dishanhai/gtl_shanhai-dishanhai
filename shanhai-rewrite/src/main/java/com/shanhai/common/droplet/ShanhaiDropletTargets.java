package com.shanhai.common.droplet;

import com.shanhai.ShanhaiMod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * 多个水滴的<b>目标分配（不锁同一个目标）</b>——2026-11-24 新增。
 *
 * <h2>一、要解决的问题（用户原话）</h2>
 * 「<b>多个水滴会锁同一个目标导致效率低下，所以让多个水滴不要锁相同的目标</b>」。
 *
 * <p>老实现是"每个水滴各自找最近的一个候选"（{@code ShanhaiDropletEntity#findNearestTarget}），
 * 各自独立 ⇒ 三个水滴贴着同一个最近的目标，等于三倍火力打一个、其它目标没人管。
 *
 * <h2>二、算法（每 tick 每层一次，按实体 id 升序）</h2>
 * <ol>
 *   <li><b>先认账</b>：仍然<b>有效</b>的已锁定目标优先保留（规格 5 的"锁到死/消失/超 128 格"）。
 *       同一个目标被两个水滴同时锁着时，<b>id 小的留着</b>，另一个释放（这一步顺便把历史重复锁清掉）。</li>
 *   <li><b>再分配</b>：需要选目标的水滴，按<b>拥有者距离升序</b>遍历自己的候选，
 *       取第一个<b>还没被领走</b>的。</li>
 *   <li><b>都领完了</b>：允许复用 —— 取自己最近的候选（哪怕已被别人领走）。
 *       也就是说"优先未领"，不是"没有就空转"。</li>
 * </ol>
 *
 * <h2>三、释放时机</h2>
 * 水滴死亡/被收回 ⇒ 下一 tick 的 {@link #prune()} 把它从注册表里删掉（它的目标自然不再被占）；
 * 切到"不攻击"模式 / 拥有者离线 / 拥有者在别的维度 ⇒ 这一 tick 直接 {@code assignTarget(null)}。
 *
 * <h2>四、判据（实机看日志一行，5 秒限流）</h2>
 * <pre>[SHANHAI-DROPLET] target_claim droplets=N distinct=M taken=[…]</pre>
 * {@code M} = 被领走的<b>不同</b>目标数。三个水滴、三个候选时 {@code M} 应为 3；
 * 只有一个候选时 {@code M} 只能是 1（其余按"都领完了"复用）。
 *
 * <h2>五、线程</h2>
 * 只在服务端主线程调用（{@link ShanhaiDropletEntity#tick()} → {@link #allocate(ServerLevel)}），无锁。
 */
public final class ShanhaiDropletTargets {

    private static final String PREFIX = ShanhaiDropletCombat.PREFIX;

    /** 注册表：活着的服务端水滴（每 tick 剪一次"已被移除"的）。 */
    private static final Set<ShanhaiDropletEntity> REGISTRY = new LinkedHashSet<>();

    /** 每层最后一次分配的时间戳（{@code level.getGameTime()}）——保证每 tick 只分一次。 */
    private static final WeakHashMap<Level, long[]> LAST_ALLOC = new WeakHashMap<>();

    /** 日志限流：5 秒 = 100 tick。 */
    private static final long LOG_INTERVAL_TICKS = 100L;

    private static long lastLogTick = Long.MIN_VALUE;

    private ShanhaiDropletTargets() {}

    /** 把一个水滴登记进来（幂等；每 tick 调一次没关系）。 */
    public static void register(ShanhaiDropletEntity droplet) {
        REGISTRY.add(droplet);
    }

    /**
     * 本 tick 的<b>整层</b>目标分配（幂等：同一 tick 里被多个水滴调用只跑一次）。
     *
     * @param level 水滴所在层（服务端）
     */
    public static void allocate(ServerLevel level) {
        final long now = level.getGameTime();
        long[] stamp = LAST_ALLOC.get(level);
        if (stamp == null) {
            stamp = new long[] {Long.MIN_VALUE};
            LAST_ALLOC.put(level, stamp);
        }
        if (stamp[0] == now) {
            return;                     // 这一 tick 已经分过了
        }
        stamp[0] = now;

        prune();
        final List<ShanhaiDropletEntity> droplets = new ArrayList<>();
        for (final Iterator<ShanhaiDropletEntity> it = REGISTRY.iterator(); it.hasNext(); ) {
            final ShanhaiDropletEntity droplet = it.next();
            if (droplet.level() == level) {
                droplets.add(droplet);
            }
        }
        if (droplets.isEmpty()) {
            return;
        }
        // 🔴 按实体 id 升序：分配结果与"谁先 tick"无关 ⇒ 可复现
        droplets.sort(Comparator.comparingInt(ShanhaiDropletEntity::getId));

        // 先把每滴的"旧锁 + 候选"整理成纯数据，交给 plan(...) 定夺（plan 是零 MC 依赖的，
        // 离线探针直接跑它 ⇒ 测的是真正在用的那份算法，不是复制品）
        final List<UUID> locked = new ArrayList<>(droplets.size());
        final List<List<UUID>> candidates = new ArrayList<>(droplets.size());
        final Map<UUID, LivingEntity> byUuid = new HashMap<>();
        for (int i = 0; i < droplets.size(); i++) {
            final ShanhaiDropletEntity droplet = droplets.get(i);
            final ServerPlayer owner = droplet.resolveOwner();
            if (owner == null || owner.level() != level) {
                // 拥有者离线 / 在别的维度：这一 tick 不索敌（换维度由水滴自己 tick 里的跟随处理）
                locked.add(null);
                candidates.add(java.util.Collections.emptyList());
                continue;
            }
            final int mode = ShanhaiDropletSettings.mode(owner);
            if (mode == ShanhaiDropletSettings.MODE_NONE) {
                locked.add(null);
                candidates.add(java.util.Collections.emptyList());
                continue;
            }
            final LivingEntity keep = droplet.lockedTargetUsable(owner, mode);
            if (keep != null) {
                byUuid.put(keep.getUUID(), keep);
            }
            locked.add(keep == null ? null : keep.getUUID());
            final List<LivingEntity> raw = droplet.candidatesByDistance(owner, mode);
            final List<UUID> ids = new ArrayList<>(raw.size());
            for (int c = 0; c < raw.size(); c++) {
                final LivingEntity candidate = raw.get(c);
                ids.add(candidate.getUUID());
                byUuid.put(candidate.getUUID(), candidate);
            }
            candidates.add(ids);
        }
        final UUID[] assigned = plan(locked, candidates);
        final Set<UUID> taken = new HashSet<>();
        for (int i = 0; i < droplets.size(); i++) {
            final UUID uuid = assigned[i];
            if (uuid == null) {
                droplets.get(i).assignTarget(null);
                continue;
            }
            taken.add(uuid);
            droplets.get(i).assignTarget(byUuid.get(uuid));
        }
        logClaim(level, droplets.size(), taken);
    }

    /**
     * <b>纯逻辑</b>（零 MC 依赖）：算出每滴该拿哪个目标。
     *
     * <h4>规则（与类注释 §二 同一条）</h4>
     * <ol>
     *   <li>按<b>下标顺序</b>（调用方已按实体 id 升序排好）逐个处理；</li>
     *   <li>旧锁仍然有效且<b>还没被别人领走</b> ⇒ 继续锁（{@code taken.add} 成功才算领到）；</li>
     *   <li>否则按<b>距离升序</b>取第一个"没被领走"的候选；</li>
     *   <li>都被领完了 ⇒ 允许复用：取自己最近的（{@code candidates[0]}）。</li>
     * </ol>
     *
     * @param locked     每滴的旧锁（可 {@code null}）
     * @param candidates 每滴的候选（<b>按距离升序</b>；可空）
     * @return 每滴的结果（{@code null} = 这滴这一 tick 没有目标）
     */
    public static UUID[] plan(List<UUID> locked, List<List<UUID>> candidates) {
        final int n = Math.min(locked.size(), candidates.size());
        final UUID[] out = new UUID[n];
        final Set<UUID> taken = new HashSet<>();
        for (int i = 0; i < n; i++) {
            final UUID keep = locked.get(i);
            if (keep != null && taken.add(keep)) {
                out[i] = keep;
                continue;
            }
            final List<UUID> list = candidates.get(i);
            UUID pick = null;
            for (int c = 0; c < list.size(); c++) {
                if (!taken.contains(list.get(c))) {
                    pick = list.get(c);
                    break;
                }
            }
            if (pick == null && !list.isEmpty()) {
                pick = list.get(0);      // 都领完了 ⇒ 允许复用
            }
            out[i] = pick;
            if (pick != null) {
                taken.add(pick);
            }
        }
        return out;
    }

    /** 剪掉已经被移除的水滴（死亡 / 被 {@code /shanhai droplet recall} 收回 / 换维度后旧对象）。 */
    private static void prune() {
        if (REGISTRY.isEmpty()) {
            return;
        }
        REGISTRY.removeIf(droplet -> droplet.isRemoved() || droplet.level().isClientSide);
    }

    /** 判据行（5 秒限流）。 */
    private static void logClaim(ServerLevel level, int droplets, Set<UUID> taken) {
        final long now = level.getGameTime();
        if (lastLogTick != Long.MIN_VALUE && now - lastLogTick < LOG_INTERVAL_TICKS) {
            return;
        }
        lastLogTick = now;
        final StringBuilder list = new StringBuilder();
        int shown = 0;
        for (final UUID uuid : taken) {
            if (shown > 0) {
                list.append(',');
            }
            if (shown >= 6) {
                list.append("…");
                break;
            }
            list.append(uuid.toString(), 0, 8);
            shown++;
        }
        ShanhaiMod.LOGGER.info("{} target_claim droplets={} distinct={} taken=[{}] dim={}",
                PREFIX, Integer.valueOf(droplets), Integer.valueOf(taken.size()), list,
                level.dimension().location());
    }
}
