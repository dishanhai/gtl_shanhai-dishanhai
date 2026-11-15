package com.shanhai.add.time.replay;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.time.ShanhaiClockHooks;
import com.shanhai.add.time.ShanhaiClockMode;
import com.shanhai.add.time.ShanhaiWorldClock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;

/**
 * 山海重构 · <b>实体"出现 / 消失"记录器</b>（第 1 层，逐 tick 连续）。
 *
 * <h2>1. 用户口径</h2>
 * <blockquote>「我刷了几个生物也没回去」</blockquote>
 * ⇒ 后出现的必须被移除、后消失的必须被重建（覆盖<b>所有</b>实体类型：生物 / 掉落物 / 经验球 /
 * 投掷物 / 载具 / 画与拴绳结 —— 这一层不分类，只排除玩家）。
 *
 * <h2>2. 三个触发点</h2>
 * <ol>
 *   <li>{@link EntityJoinLevelEvent}：{@code loadedFromDisk()==true} 的一律不记
 *       （那是区块加载把老实体"重新加入世界"，不是新出现的生物）；</li>
 *   <li>{@link EntityLeaveLevelEvent}：换维度/离开世界；</li>
 *   <li>每 tick 末的<b>已移除扫描</b>：死亡 / {@code discard()} / 反注册走这条（它们不一定发 leave 事件）。</li>
 * </ol>
 *
 * <h2>3. 玩家永远豁免</h2>
 * 既不记也不动（{@link ShanhaiReplayEntityGate#SKIP_PLAYER}）：把玩家删掉就是掉线崩客户端。
 *
 * <h2>4. 失败方向</h2>
 * 全程自吞 + 限流 WARN：记录器坏了绝不能让生成/移除实体这件事失败。
 */
public final class ShanhaiReplayEntityRecorder {

    /** 被跟踪的实体上限（超过就不再跟踪新的，记 {@code trackOverflow}）。 */
    private static final int MAX_TRACKED = 4_096;
    /** 实体 NBT 载荷池上限（每份 ≈ 0.5–2 KB）。 */
    private static final int MAX_PAYLOAD = 20_000;

    private static final ShanhaiReplayEntityJournal JOURNAL =
            new ShanhaiReplayEntityJournal(ShanhaiReplayConst.MAX_ENTITY_RECORDS);

    /** UUID → 跟踪项（弱引用实体：区块卸载后自动失效，绝不拖住世界内存）。 */
    private static final Map<UUID, Track> TRACKED = new HashMap<>();
    /** 实体 NBT 载荷池（消失时存整份，回退时用来重建）。 */
    private static final List<CompoundTag> PAYLOAD = new ArrayList<>();

    private static long joins;
    private static long leaves;
    private static long swept;
    private static long missedNbt;
    private static long trackOverflow;
    private static long lastWarnMs;

    /** 五层自证行里这一层的判据（Forge 监听器注册成功 = {@code true}）。 */
    public static final String LAYER_ID = "entities";
    private static volatile boolean listenerArmed;

    /** 分类计数（报告里"记了几只"的读数）。 */
    private static long appearCount;
    private static long disappearCount;
    private static long cancelledCount;
    private static long removeNotFound;
    private static long removePlayerSkipped;
    private static long rebuildNoPayload;
    private static long tntFrozen;

    /**
     * 重建出来的 TNT 的引信（{@code Integer.MAX_VALUE} tick ≈ 3.4 年 ⇒ 实际"永不爆炸"）。
     *
     * <p>用户口径：「TNT 实体就是要恢复的，但是它不应该做出爆炸这一动作，而是做出和当时点燃->爆炸
     * 刚好相反的动作」⇒ 重建的 TNT 只做"它在场"这一件事：<b>引信冻死</b>，坑由方块日志逐步补；
     * 继续往回退到它被点燃的那个 tick 时，出现/消失层会把这只实体删掉（= 点燃的反动作）。
     * 于是永远不会出现"回放时又炸一次"（二次爆炸）。
     */
    public static final int TNT_FROZEN_FUSE = Integer.MAX_VALUE;

    /**
     * 一个被跟踪的实体。
     *
     * @param dim 出现时所在维度
     * @param ref 弱引用（实体活着时一定被世界强引用）
     */
    private record Track(int dim, WeakReference<Entity> ref) {
    }

    public static ShanhaiReplayEntityJournal journal() {
        return JOURNAL;
    }

    public static long joins() {
        return joins;
    }

    public static long leaves() {
        return leaves;
    }

    public static int trackedSize() {
        return TRACKED.size();
    }

    public static int payloadSize() {
        return PAYLOAD.size();
    }

    public static void reset() {
        TRACKED.clear();
        PAYLOAD.clear();
        JOURNAL.clear();
        joins = 0L;
        leaves = 0L;
        swept = 0L;
        revived = 0L;
        missedNbt = 0L;
        trackOverflow = 0L;
        appearCount = 0L;
        disappearCount = 0L;
        cancelledCount = 0L;
        removeNotFound = 0L;
        removePlayerSkipped = 0L;
        rebuildNoPayload = 0L;
        tntFrozen = 0L;
    }

    /** Forge 监听器注册成功后由 {@code ShanhaiClockInit} 调一次（armed 自证用）。 */
    public static void markListenerArmed() {
        listenerArmed = true;
    }

    public static boolean listenerArmed() {
        return listenerArmed;
    }

    /**
     * 一行分类读数：记了几只、删了几只、为什么删不掉。
     *
     * <p>真机血账：日志只有 {@code entities=records=220}，于是"记了 220 条"和"回放应用了 0 条"
     * 分不清是记录侧还是取用侧的问题。这一行把记录侧摊开。
     */
    public static String classifyLine() {
        return "appear=" + appearCount + " disappear=" + disappearCount
                + " cancelled=" + cancelledCount + " joins=" + joins + " leaves=" + leaves
                + " swept=" + swept + " remove_not_found=" + removeNotFound
                + " remove_player_skipped=" + removePlayerSkipped
                + " rebuild_no_payload=" + rebuildNoPayload + " tnt_frozen=" + tntFrozen
                + " missed_nbt=" + missedNbt;
    }

    // ------------------------------------------------------------------ 事件

    /** {@code EntityJoinLevelEvent}（服务端 Forge 事件总线）。 */
    public static void onJoin(EntityJoinLevelEvent event) {
        try {
            if (event == null || event.getEntity() == null || event.getLevel() == null) {
                return;
            }
            final Entity entity = event.getEntity();
            final Level level = event.getLevel();
            final int dim = ShanhaiReplayEngine.dimIdOf(level.dimension());
            final ShanhaiReplayEntityGate.Reason reason = ShanhaiReplayEntityGate.decide(
                    ShanhaiWorldClock.SERVER.getMode(), entity instanceof Player, level.isClientSide(),
                    ShanhaiClockHooks.inReplayWrite(), event.loadedFromDisk());
            if (!reason.accepted() || dim < 0) {
                return;
            }
            final long tick = level.getGameTime();
            final UUID uuid = entity.getUUID();
            if (JOURNAL.record((int) tick, dim, uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                    ShanhaiReplayEntityJournal.Kind.APPEAR, ShanhaiReplayJournal.NO_PAYLOAD)) {
                ++joins;
                ++appearCount;
            }
            if (TRACKED.size() < MAX_TRACKED) {
                TRACKED.put(uuid, new Track(dim, new WeakReference<>(entity)));
            } else {
                ++trackOverflow;
            }
        } catch (Throwable t) {
            warnThrottled("实体出现记录失败（这一只不会被回退）", t);
        }
    }

    /** {@code EntityLeaveLevelEvent}：消失 = 存整份 NBT + 记一条 DISAPPEAR。 */
    public static void onLeave(EntityLeaveLevelEvent event) {
        try {
            if (event == null || event.getEntity() == null || event.getLevel() == null) {
                return;
            }
            final Entity entity = event.getEntity();
            final Level level = event.getLevel();
            final ShanhaiReplayEntityGate.Reason reason = ShanhaiReplayEntityGate.decide(
                    ShanhaiWorldClock.SERVER.getMode(), entity instanceof Player, level.isClientSide(),
                    ShanhaiClockHooks.inReplayWrite(), false);
            if (!reason.accepted()) {
                return;
            }
            recordDisappear(entity, level);
            TRACKED.remove(entity.getUUID());
        } catch (Throwable t) {
            warnThrottled("实体消失记录失败（这一只不会被重建）", t);
        }
    }

    /**
     * 每 tick 末的已移除扫描（死亡 / {@code discard()} / 反注册）。
     *
     * <p>成本：只遍历"这一窗口里出现过"的实体（弱引用），不是全服实体。
     */
    public static void sweep(MinecraftServer server) {
        if (TRACKED.isEmpty() || server == null) {
            return;
        }
        if (!ShanhaiReplayModeCompose.recordsUnder(ShanhaiWorldClock.SERVER.getMode())) {
            return;
        }
        final List<UUID> gone = new ArrayList<>();
        for (Map.Entry<UUID, Track> entry : TRACKED.entrySet()) {
            final Track track = entry.getValue();
            final Entity entity = track.ref() == null ? null : track.ref().get();
            if (entity != null && !entity.isRemoved()) {
                continue;
            }
            gone.add(entry.getKey());
            if (entity == null) {
                ++missedNbt;   // 引用没了 ⇒ 拿不到 NBT，重建不了（记进报告/矩阵）
                continue;
            }
            final ServerLevel level = levelOf(server, track.dim());
            if (level != null) {
                try {
                    recordDisappear(entity, level);
                    ++swept;
                } catch (Throwable t) {
                    ++missedNbt;
                }
            }
        }
        for (UUID uuid : gone) {
            TRACKED.remove(uuid);
        }
    }

    /** 存一份实体 NBT（含 {@code id} 注册名，重建要用）并记一条 DISAPPEAR。 */
    private static void recordDisappear(Entity entity, Level level) {
        final int dim = ShanhaiReplayEngine.dimIdOf(level.dimension());
        if (dim < 0) {
            return;
        }
        final CompoundTag tag = entity.saveWithoutId(new CompoundTag());
        if (tag == null) {
            ++missedNbt;
            return;
        }
        tag.putString("id", EntityType.getKey(entity.getType()).toString());
        final int slot = putPayload(tag);
        final UUID uuid = entity.getUUID();
        // 🔴 B：被 kill 的实体必须有一条<b>死亡边界</b>血量（否则回退时它永远没有血可回）
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            ShanhaiReplayHealthRecorder.recordNow(dim, living, level.getGameTime());
        }
        if (JOURNAL.record((int) level.getGameTime(), dim, uuid.getMostSignificantBits(),
                uuid.getLeastSignificantBits(), ShanhaiReplayEntityJournal.Kind.DISAPPEAR, slot)) {
            ++leaves;
            ++disappearCount;
        }
    }

    private static int putPayload(CompoundTag tag) {
        if (PAYLOAD.size() >= MAX_PAYLOAD) {
            return ShanhaiReplayJournal.NO_PAYLOAD;
        }
        final int slot = PAYLOAD.size();
        PAYLOAD.add(tag);
        return slot;
    }

    private static CompoundTag getPayload(int slot) {
        if (slot < 0 || slot >= PAYLOAD.size()) {
            return null;
        }
        return PAYLOAD.get(slot);
    }

    // ------------------------------------------------------------------ 回退

    /** 把"后来才出现"的实体删掉。 */
    public static boolean remove(ServerLevel level, long uuidMost, long uuidLeast) {
        if (level == null) {
            ++removeNotFound;
            return false;
        }
        final Entity entity = level.getEntity(new UUID(uuidMost, uuidLeast));
        if (entity == null) {
            ++removeNotFound;   // 区块没加载 / 已经被别的路径删了：must 可判（不静默）
            return false;
        }
        if (entity instanceof Player) {
            ++removePlayerSkipped;
            return false;   // 第三道保险：玩家绝不删
        }
        ShanhaiClockHooks.beginReplayWrite();
        try {
            entity.discard();
            return true;
        } catch (Throwable t) {
            warnThrottled("回退时移除实体失败 uuid=" + uuidMost, t);
            return false;
        } finally {
            ShanhaiClockHooks.endReplayWrite();
        }
    }

    /** 用整份 NBT 重建"后来消失"的实体（同维度、记录里的位置）。 */
    public static boolean rebuild(ServerLevel level, int payload) {
        if (level == null) {
            ++rebuildNoPayload;
            return false;
        }
        final CompoundTag tag = getPayload(payload);
        if (tag == null) {
            ++rebuildNoPayload;
            return false;
        }
        ShanhaiClockHooks.beginReplayWrite();
        try {
            final Optional<Entity> created = EntityType.create(tag, level);
            if (created.isEmpty()) {
                return false;
            }
            final Entity entity = created.get();
            if (entity instanceof Player) {
                return false;
            }
            // 🔴 B：重建出来的生物<b>不许是尸体</b>（用户：「有些猪出现了假死」「被 kill 的生物血量没回来」）。
            //    被杀那一刻存的 NBT 带着 Health:0.0 / DeathTime>0 ⇒ 直接 addFreshEntity 会得到一个
            //    永远在死亡动画里、且一解冻就冒白烟（byte 60）的实体。这里当场救活：
            //    血量先给满（同一 tick 的 health 层随后会覆盖成记录值），死亡计时/受击计时清零。
            final boolean wasDead = reviveIfDead(entity);
            if (wasDead) {
                ++revivePoofAvoided;
                ++revivePreventedDie;
                // 它确实死在窗口里 ⇒ 死亡烟必须出现在"它死的那一拍"，而且是倒放的
                ShanhaiReplayRevive.sendReversedDeathPoof(level, entity);
            }
            freezeIfExplosive(entity);
            final boolean added = level.addFreshEntity(entity);
            if (added) {
                // 🔴 根因落点：重建拿到的是<b>新 server id</b>，而"告诉客户端这个 id"的 spawn 包
                //    只在被我们跳过的 chunk tracking 里发 ⇒ 我们的位置包被客户端当未知 id 丢掉
                //    ⇒ 解冻一次 spawn 按服务端当前位置放置 = 十几格。
                //    这里把"最后一个重建出来的实体"交给引擎，由它<b>直发 add → teleport</b>。
                LAST_REBUILT = new java.lang.ref.WeakReference<>(entity);
            }
            return added;
        } catch (Throwable t) {
            warnThrottled("回退时重建实体失败 payload=" + payload, t);
            return false;
        } finally {
            ShanhaiClockHooks.endReplayWrite();
        }
    }

    /**
     * 🔴 重建出来的 TNT 必须<b>惰性</b>：引信冻在 {@link #TNT_FROZEN_FUSE}。
     *
     * <p>用户口径（原话）：「TNT 实体就是要恢复的，但是它不应该做出爆炸这一动作，
     * 而是做出和当时点燃->爆炸刚好相反的动作」。
     * ⇒ 回放只做"它在场"；炸出来的坑由方块日志逐格补；继续往回退到点燃那一 tick 时，
     * 出现/消失层把它删掉（= 点燃的反动作）。<b>任何情况下都不许二次爆炸。</b>
     */
    private static void freezeIfExplosive(Entity entity) {
        try {
            if (entity instanceof net.minecraft.world.entity.item.PrimedTnt tnt) {
                tnt.setFuse(TNT_FROZEN_FUSE);
                ++tntFrozen;
            }
        } catch (Throwable t) {
            warnThrottled("冻结 TNT 引信失败（这只 TNT 可能会二次爆炸）！", t);
        }
    }

    /**
     * 🔴 B：把重建出来的"尸体"救活（用户口径：「有些猪出现了假死」「被 kill 的生物血量没回来」）。
     *
     * <p>被杀那一刻存下的 NBT 带着 {@code Health:0.0f} 与 {@code DeathTime>0}：
     * <ul>
     *   <li>{@code Health=0} ⇒ 回退后血是 0（用户看到的"血量没回来"）；</li>
     *   <li>{@code DeathTime>0} ⇒ 世界一解冻它继续走死亡动画，并在 {@code die()} 里
     *       {@code broadcastEntityEvent(this,(byte)60)}（{@code LivingEntity.java:549}）
     *       ⇒ <b>收尾那一瞬冒白烟</b>（用户看到的"白色烟团是生物死亡的特效"）。</li>
     * </ul>
     * 所以重建之后立刻：血量给满（同一 tick 的 health 层随后会覆盖成记录值）、
     * 死亡/受击/无敌计时清零。读数为 {@code entity_revive health=v/max deathTime=0}。
     */
    private static boolean reviveIfDead(Entity entity) {
        try {
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)) {
                return false;
            }
            final float max = living.getMaxHealth();
            final float before = living.getHealth();
            final int deathTime = living.deathTime;
            final boolean wasDead = before <= 0.0F || deathTime > 0;
            final float want = before > 0.0F ? before : max;
            living.setHealth(Math.min(want, max));
            living.deathTime = 0;
            living.hurtTime = 0;
            living.invulnerableTime = 0;
            ++revived;
            ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] entity_revive uuid={} health={}/{} deathTime=0 "
                            + "nbt_health={} nbt_death_time={} was_dead={}",
                    entity.getUUID(), living.getHealth(), max, before, deathTime, wasDead);
            return wasDead;
        } catch (Throwable t) {
            warnThrottled("救活重建出来的生物失败（这一只可能假死）", t);
            return false;
        }
    }

    /** 被救活的条数（判据 B：必须 &gt; 0）。 */
    public static long revived() {
        return revived;
    }

    /** "本来会冒白烟"的救活次数（重建时 NBT 带 DeathTime&gt;0 的只数）。 */
    public static long revivePoofAvoided() {
        return revivePoofAvoided;
    }

    /** 最近一次重建出来的实体（引擎要用它直发 add + teleport）。 */
    public static Entity lastRebuilt() {
        return LAST_REBUILT == null ? null : LAST_REBUILT.get();
    }

    private static java.lang.ref.WeakReference<Entity> LAST_REBUILT;
    private static long revivePoofAvoided;
    private static long revivePreventedDie;

    /** 在"死亡动画真正开始"之前被救活的次数（{@code revive_prevented_die=}）。 */
    public static long revivePreventedDie() {
        return revivePreventedDie;
    }

    private static long revived;

    private static ServerLevel levelOf(MinecraftServer server, int dim) {
        final net.minecraft.resources.ResourceKey<Level> key = ShanhaiReplayEngine.dimKeyOf(dim);
        return key == null ? null : server.getLevel(key);
    }

    public static String summary() {
        return "joins=" + joins + " leaves=" + leaves + " swept=" + swept + " tracked=" + TRACKED.size()
                + " payloads=" + PAYLOAD.size() + " missed_nbt=" + missedNbt
                + " track_overflow=" + trackOverflow + " journal[" + JOURNAL.summary() + "]";
    }

    private static void warnThrottled(String message, Throwable t) {
        final long now = System.currentTimeMillis();
        if (now - lastWarnMs < 30_000L) {
            return;
        }
        lastWarnMs = now;
        if (t == null) {
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] {}", message);
        } else {
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] {}", message, t);
        }
    }

    private ShanhaiReplayEntityRecorder() {
    }
}
