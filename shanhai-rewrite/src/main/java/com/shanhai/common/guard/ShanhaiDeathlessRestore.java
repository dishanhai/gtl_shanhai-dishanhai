package com.shanhai.common.guard;

import com.shanhai.ShanhaiMod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海 · <b>④ 死而无损（开关，默认开）</b>：万一真的走到死亡，也一件都不损失。
 *
 * <h2>它和「die() 拒绝」是什么关系</h2>
 * 现有的 {@code die()} 拒绝（{@code ShanhaiGuardValueLayer#refuseDie}）已经让<b>死亡根本不发生</b>
 * —— 这条路正常情况下走不到。本类只做<b>兜底</b>：万一 {@code die} 那条被绕过（别的 mod 直接走
 * 死亡流程 ／ 我们自己的 mixin 没挂上），死亡真的发生了，就把五样东西还原回去。
 *
 * <h2>还原的五项（用户口径，顺序固定）</h2>
 * <pre>
 *   ① 背包＋副手＋盔甲
 *   ② 经验等级与进度（等级 ／ 进度 ／ 累计）
 *   ③ 位置与朝向（x/y/z + yaw/pitch）
 *   ④ 饥饿与饱和度
 *   ⑤ 药水效果
 * </pre>
 * 每还原一次记一行 {@code [SHANHAI-GUARD] 死而无损：已还原 <哪几项>}（只列真还原了的）。
 *
 * <h2>快照什么时候拍</h2>
 * 每 {@link #SNAPSHOT_PERIOD_TICKS} tick 给每个受保护玩家拍一张（只读背包 ＋ 读几个标量，
 * 41 格 {@code ItemStack.copy()} 的开销是常数级）。死亡那一刻把最近一张快照冻结成"待还原"。
 *
 * <h2>位置为什么在下一 tick 再放一次</h2>
 * {@code PlayerEvent.Clone} 是在 {@code ServerPlayer#restoreFrom} 里发的，
 * 之后 {@code PlayerList#respawn} 还会把新玩家挪到重生点 ⇒ 在 Clone 里设的坐标会被覆盖。
 * 所以坐标额外排到<b>下一个 tick 末</b>再 {@code teleportTo} 一次（一处数据、两次落地）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID)
public final class ShanhaiDeathlessRestore {

    private ShanhaiDeathlessRestore() {}

    /** 日志前缀。 */
    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /** 快照周期：20 tick = 1 秒。 */
    public static final int SNAPSHOT_PERIOD_TICKS = 20;

    private static final ShanhaiGuardCore.LogGate LOG = new ShanhaiGuardCore.LogGate();

    /** 日志类别：死而无损还原。 */
    public static final String KIND_DEATHLESS = "死而无损";

    /** 最近一张快照（键 = 玩家 UUID）。 */
    private static final Map<UUID, Snapshot> SNAPSHOTS = new HashMap<>();

    /** 死亡那一刻冻结下来的快照（键 = 玩家 UUID；等 Clone 来取）。 */
    private static final Map<UUID, Snapshot> PENDING = new HashMap<>();

    /** 下一 tick 要再放一次的坐标（键 = 玩家 UUID）。 */
    private static final Map<UUID, Snapshot> PENDING_POSITION = new HashMap<>();

    private static int tickCounter = 0;

    /** 一张快照（五项全在里面）。 */
    private static final class Snapshot {

        final ItemStack[] contents;
        final int xpLevel;
        final int xpTotal;
        final float xpProgress;
        final double x;
        final double y;
        final double z;
        final float yRot;
        final float xRot;
        final int foodLevel;
        final float saturation;
        final List<MobEffectInstance> effects;

        Snapshot(ItemStack[] contents, int xpLevel, int xpTotal, float xpProgress,
                 double x, double y, double z, float yRot, float xRot,
                 int foodLevel, float saturation, List<MobEffectInstance> effects) {
            this.contents = contents;
            this.xpLevel = xpLevel;
            this.xpTotal = xpTotal;
            this.xpProgress = xpProgress;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yRot = yRot;
            this.xRot = xRot;
            this.foodLevel = foodLevel;
            this.saturation = saturation;
            this.effects = effects;
        }
    }

    // ═══════════════════════════════ 拍快照 ═══════════════════════════════

    /**
     * 每 tick 末：每 {@link #SNAPSHOT_PERIOD_TICKS} tick 给<b>受保护玩家</b>拍一张快照，
     * 并把上一 tick 排下的坐标落地。
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // ① 先把"上一 tick 排下的坐标"落地（Clone 里设的那次会被重生点覆盖）
        if (!PENDING_POSITION.isEmpty()) {
            final Map<UUID, Snapshot> batch = new HashMap<>(PENDING_POSITION);
            PENDING_POSITION.clear();
            final var server = event.getServer();
            if (server != null) {
                for (final ServerPlayer p : server.getPlayerList().getPlayers()) {
                    final Snapshot s = batch.get(p.getUUID());
                    if (s == null || p.hasDisconnected()) {
                        continue;
                    }
                    try {
                        p.teleportTo(s.x, s.y, s.z);
                        p.setYRot(s.yRot);
                        p.setXRot(s.xRot);
                        ShanhaiMod.LOGGER.info(PREFIX + "死而无损：坐标已落地 player={} x={} y={} z={} yaw={} pitch={}",
                                p.getName().getString(), s.x, s.y, s.z, s.yRot, s.xRot);
                    } catch (Throwable t) {
                        ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：坐标落地失败 player={} err={}",
                                p.getName().getString(), t.toString());
                    }
                }
            }
        }
        // ② 周期快照（只在开关打开时做；默认开）
        if (!ShanhaiGuardSwitches.deathLossless()) {
            return;
        }
        tickCounter++;
        if (tickCounter % SNAPSHOT_PERIOD_TICKS != 0) {
            return;
        }
        final var server = event.getServer();
        if (server == null) {
            return;
        }
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || player.hasDisconnected()) {
                continue;
            }
            if (!ShanhaiGuardEvents.isGuarded(player)) {
                continue;
            }
            try {
                SNAPSHOTS.put(player.getUUID(), capture(player));
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：快照失败 player={} err={}",
                        player.getName().getString(), t.toString());
            }
        }
        // 名单里没有的人 ⇒ 连快照一起清掉（避免无限增长）
        SNAPSHOTS.keySet().retainAll(uuidSet(server.getPlayerList().getPlayers()));
    }

    /** 取一张快照（五项）。 */
    private static Snapshot capture(ServerPlayer player) {
        final Inventory inv = player.getInventory();
        final int size = inv.getContainerSize();
        final ItemStack[] contents = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            final ItemStack s = inv.getItem(i);
            contents[i] = s == null ? ItemStack.EMPTY : s.copy();
        }
        final FoodData food = player.getFoodData();
        final List<MobEffectInstance> effects = new ArrayList<>();
        for (final MobEffectInstance e : player.getActiveEffects()) {
            effects.add(new MobEffectInstance(e));   // 拷贝构造（javap 已确认存在）
        }
        return new Snapshot(contents, player.experienceLevel, player.totalExperience,
                player.experienceProgress, player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                food == null ? 20 : food.getFoodLevel(),
                food == null ? 5.0f : food.getSaturationLevel(), effects);
    }

    private static java.util.Set<UUID> uuidSet(List<ServerPlayer> players) {
        final java.util.Set<UUID> out = new java.util.HashSet<>();
        for (final ServerPlayer p : players) {
            if (p != null) {
                out.add(p.getUUID());
            }
        }
        return out;
    }

    // ═══════════════════════════════ 死亡那一刻：冻结快照 ═══════════════════════════════

    /**
     * 死亡真的要发生了（{@code LivingDeathEvent}）⇒ 把最近一张快照冻结成"待还原"。
     *
     * <p>⚠️ 我们<b>不取消</b>这个事件：本功能的口径是"万一真的死了，也一件都不损失"，
     * 不是"再拦一次死亡"（那是 {@code die()} 拒绝那一路的事）。
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!ShanhaiGuardSwitches.deathLossless()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        try {
            if (!ShanhaiGuardEvents.isGuarded(player)) {
                return;
            }
            final Snapshot s = SNAPSHOTS.get(player.getUUID());
            if (s == null) {
                ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：走死亡流程了，但还没有快照（进世界不足 "
                        + SNAPSHOT_PERIOD_TICKS + " tick？）player={}", player.getName().getString());
                return;
            }
            PENDING.put(player.getUUID(), s);
            final String line = LOG.admit(KIND_DEATHLESS, System.currentTimeMillis());
            if (line != null) {
                ShanhaiMod.LOGGER.warn(line + " ／ 已冻结快照，等 Clone 还原 player="
                        + player.getName().getString() + " ／ 五项="
                        + String.join(" / ", ShanhaiGuardRemoval.deathlessParts()));
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：死亡判据抛异常（原样放行）：" + t);
        }
    }

    // ═══════════════════════════════ 重生：把五项还原回去 ═══════════════════════════════

    /**
     * 玩家被重建（{@code PlayerEvent.Clone}，{@code wasDeath = true}）⇒ 用冻结的快照还原五项。
     */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!ShanhaiGuardSwitches.deathLossless()) {
            return;
        }
        if (!event.isWasDeath()) {
            return;   // 换维度那种 Clone 不是死亡，不碰
        }
        try {
            final UUID id = event.getOriginal().getUUID();
            final Snapshot s = PENDING.remove(id);
            if (s == null) {
                return;   // 没冻结过快照（不是我们保护的玩家）⇒ 一个字节都不改
            }
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：Clone 的新实体不是 ServerPlayer，没法还原");
                return;
            }
            boolean inv = false;
            boolean xp = false;
            boolean pos = false;
            boolean hunger = false;
            boolean effects = false;
            // ① 背包＋副手＋盔甲
            try {
                final Inventory target = player.getInventory();
                target.clearContent();
                for (int i = 0; i < s.contents.length && i < target.getContainerSize(); i++) {
                    target.setItem(i, s.contents[i] == null ? ItemStack.EMPTY : s.contents[i].copy());
                }
                // 🔴 这一行是"尽力而为"：{@code PlayerEvent.Clone} 是在
                //    {@code ServerPlayer#restoreFrom} 里发的，而新玩家的 {@code connection}
                //    可能还没接上（javap：restoreFrom 在 PlayerList#respawn 的流程中间被调）⇒
                //    broadcastChanges 可能 NPE。真失败也不许把"物品已放回去"这件事实说成失败
                //    （新玩家加入时客户端本来就会收到一次完整背包同步）。
                inv = true;
                try {
                    player.inventoryMenu.broadcastChanges();
                } catch (Throwable t) {
                    ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：背包同步没发出去（物品已放回，客户端加入时会再同步一次）err="
                            + t);
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：还原背包失败 err=" + t);
            }
            // ② 经验等级与进度
            try {
                player.experienceLevel = s.xpLevel;
                player.experienceProgress = s.xpProgress;
                player.totalExperience = s.xpTotal;
                xp = true;
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：还原经验失败 err=" + t);
            }
            // ③ 位置与朝向（这一拍先设一次；下一 tick 末再落地一次，见类注释）
            try {
                player.moveTo(s.x, s.y, s.z, s.yRot, s.xRot);
                PENDING_POSITION.put(id, s);
                pos = true;
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：还原位置失败 err=" + t);
            }
            // ④ 饥饿与饱和度
            try {
                final FoodData food = player.getFoodData();
                if (food != null) {
                    food.setFoodLevel(s.foodLevel);
                    food.setSaturation(s.saturation);
                    hunger = true;
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：还原饥饿失败 err=" + t);
            }
            // ⑤ 药水效果
            try {
                player.removeAllEffects();
                for (final MobEffectInstance e : s.effects) {
                    player.addEffect(new MobEffectInstance(e));
                }
                effects = true;
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：还原药水效果失败 err=" + t);
            }
            final String plain = ShanhaiGuardRemoval.deathlessLogLine(inv, xp, pos, hunger, effects);
            ShanhaiMod.LOGGER.warn(PREFIX + "{} ／ player={} ／ 经验={}/{} 累计={} ／ 食物={} 饱和={} ／ 效果={}"
                            + " ／ 物品格={}",
                    plain, player.getName().getString(), s.xpLevel, s.xpProgress, s.xpTotal,
                    s.foodLevel, s.saturation, s.effects.size(), s.contents.length);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "死而无损：Clone 还原抛异常（原样放行）：" + t);
        }
    }

    /** 一行状态（命令回显 / 报告）。 */
    public static String statusLine() {
        return "death_lossless=" + ShanhaiGuardSwitches.deathLossless()
                + " 快照周期=" + SNAPSHOT_PERIOD_TICKS + "tick"
                + " 已有快照=" + SNAPSHOTS.size() + " 份 待还原=" + PENDING.size() + " 份";
    }
}
