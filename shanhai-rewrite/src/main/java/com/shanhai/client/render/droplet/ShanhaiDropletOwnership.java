package com.shanhai.client.render.droplet;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.droplet.ShanhaiDropletEntity;
import com.shanhai.item.ShanhaiItems;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 「<b>哪些水滴是我名下的</b>」的<b>客户端判定</b> + 每 tick 刷新的只读清单。
 *
 * <h2>一、为什么需要这个类（先把真实约束说清楚）</h2>
 * 权威判据本来只有一个：{@code ShanhaiDropletEntity#ownerUuid()} / {@code #isOwnedBy(Player)}。
 * 但 🔴 <b>拥有者 UUID 是服务端字段，不参与同步</b>：
 * {@code ownerUuid} 只在构造器与 {@code readAdditionalSaveData} 里赋值，
 * 而客户端实体是从 {@code ClientboundAddEntityPacket} 造出来的（那个包只带
 * id/uuid/type/坐标/朝向/数据值，<b>不带 NBT</b>）⇒ <b>客户端上它恒为 null</b>。
 * {@code DATA_*} 那 5 个同步字段里也没有拥有者。
 *
 * <p>所以本类按三条<b>逐级降级</b>的口径判定，并且<b>只允许</b>这三条
 * （任何"猜"都不许加进来 —— 宁可 HUD 不显示，也不要显示别人的水滴）：
 * <ol>
 *   <li><b>权威</b>：{@code ownerUuid()!=null} ⇒ 直接用 {@code isOwnedBy(mc.player)}。
 *       今天走不到，但一旦同步字段补上（另一条线的事），这里自动就对了。</li>
 *   <li><b>单人</b>：{@code mc.level.players().size()==1} ⇒ 这个世界只有你一个玩家，
 *       任何水滴都只能是你的（{@code /summon} 出来的孤儿也归你 —— 宁可多认，不看不见）。</li>
 *   <li><b>放置预测</b>：本地玩家拿水滴物品右键方块的那一刻记一个"待认领点"
 *       （{@link #onRightClickBlock}），下一 tick 起把<b>位置吻合（≤0.35 格）</b>的
 *       新水滴认成自己的。为什么这条是可靠的：{@code ShanhaiDropletItem#useOn} 用的是
 *       {@code context.getClickLocation()}，服务端<b>原样</b> {@code setPos} ⇒ 位置逐位可对上。</li>
 * </ol>
 * 三条都不命中 ⇒ <b>不认</b>（HUD/高亮都不显示），并<b>只在第一次</b>打一行
 * {@code ownership_unknown}，把"为什么没有"留在日志里，而不是沉默。
 *
 * <h2>二、为什么要每 tick 刷新一份清单</h2>
 * 世界渲染那一趟（{@link ShanhaiDropletWorldFx}）跑在渲染线程、每帧一次；
 * 让它<b>每帧去扫全世界的实体</b>既慢又容易和实体增删撞上。
 * 所以这里在客户端 tick 里扫一遍，缓存成 {@link #ownedInLevel()}（<b>只读、不复制</b>）。
 *
 * <h2>三、线程</h2>
 * 全部读写在<b>客户端主线程</b>（tick 事件 + 渲染都在主线程；1.20.1 的 Minecraft 客户端
 * 渲染线程与主线程是同一个）⇒ 没有加锁，也不许从别的线程调。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiDropletOwnership {

    private static final String PREFIX = "[SHANHAI-DROPLET-FX]";

    /** 日志开关：默认开（每次数量变化打一行，不刷屏）。 */
    private static final boolean DEBUG_OWNERSHIP_LOG = true;

    /** 已认领的实体 UUID（跨 tick 保留；换世界/断线清空）。 */
    private static final Set<UUID> CLAIMED = new HashSet<>();

    /** 待认领点：{@code {x,y,z,tick}}。 */
    private static final List<double[]> PENDING = new ArrayList<>();

    /** 只读清单（每 tick 重建；外面<b>不许</b>改它）。 */
    private static final List<ShanhaiDropletEntity> OWNED = new ArrayList<>();

    /** 待认领点的寿命（tick）：60 = 3 秒，足够任何一次放置被下一 tick 看到。 */
    private static final int PENDING_LIFE_TICKS = 60;

    /** 位置吻合半径（格）。服务端 setPos 用的是同一个点击点 ⇒ 真正需要的只有浮点误差。 */
    private static final double PENDING_MATCH_RADIUS = 0.35D;

    private static ClientLevel lastLevel;
    private static long tickCounter;
    private static int lastLoggedOwned = -1;
    private static boolean loggedUnknown;

    private ShanhaiDropletOwnership() {}

    /** 当前世界里<b>我名下</b>的水滴（只读；每 tick 刷新一次，别在别处改它）。 */
    public static List<ShanhaiDropletEntity> ownedInLevel() {
        return OWNED;
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    //  tick：认领 + 刷新清单
    // ══════════════════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        final Player player = mc.player;
        if (level == null || player == null) {
            clearAll();
            return;
        }
        if (level != lastLevel) {
            // 换维度 / 重进世界：UUID 清单失效（同一个实体在另一个 ClientLevel 里是新对象，
            // 但 UUID 会保留 —— 所以只在"换 level 对象"时清，维度切换也走这里，代价只是重认一次）
            lastLevel = level;
            CLAIMED.clear();
            PENDING.clear();
            loggedUnknown = false;
        }
        expirePending();

        final boolean solo = level.players().size() <= 1;
        int unknown = 0;
        OWNED.clear();
        for (final Entity candidate : level.entitiesForRendering()) {
            if (!(candidate instanceof ShanhaiDropletEntity droplet)) {
                continue;
            }
            if (isMine(droplet, player, solo, level)) {
                OWNED.add(droplet);
            } else if (droplet.ownerUuid() == null) {
                unknown++;
            }
        }
        if (unknown > 0 && !loggedUnknown && DEBUG_OWNERSHIP_LOG) {
            loggedUnknown = true;
            ShanhaiMod.LOGGER.info("{} ownership_unknown n={}（拥有者 UUID 未同步、非单人、也没在本机放置记录里"
                    + "⇒ HUD/高亮不显示；这是保守口径，不是 bug）", PREFIX, Integer.valueOf(unknown));
        }
        if (OWNED.size() != lastLoggedOwned) {
            lastLoggedOwned = OWNED.size();
            ShanhaiMod.LOGGER.info("{} ownership_owned n={} solo={} claimed={} dim={}",
                    PREFIX, Integer.valueOf(OWNED.size()), Boolean.valueOf(solo),
                    Integer.valueOf(CLAIMED.size()), level.dimension().location());
        }
    }

    /** 单个水滴是不是我的（三条降级口径，顺序固定）。 */
    private static boolean isMine(ShanhaiDropletEntity droplet, Player player, boolean solo,
                                  ClientLevel level) {
        // ① 权威
        final UUID owner = droplet.ownerUuid();
        if (owner != null) {
            return owner.equals(player.getUUID());
        }
        // ② 单人
        if (solo) {
            return true;
        }
        // ③ 放置预测
        if (CLAIMED.contains(droplet.getUUID())) {
            return true;
        }
        if (matchPending(droplet)) {
            CLAIMED.add(droplet.getUUID());
            ShanhaiMod.LOGGER.info("{} ownership_claimed uuid={} by_placement=true",
                    PREFIX, droplet.getUUID());
            return true;
        }
        return false;
    }

    private static boolean matchPending(ShanhaiDropletEntity droplet) {
        if (PENDING.isEmpty()) {
            return false;
        }
        final double r2 = PENDING_MATCH_RADIUS * PENDING_MATCH_RADIUS;
        for (Iterator<double[]> it = PENDING.iterator(); it.hasNext(); ) {
            final double[] p = it.next();
            final double dx = droplet.getX() - p[0];
            final double dy = droplet.getY() - p[1];
            final double dz = droplet.getZ() - p[2];
            if (dx * dx + dy * dy + dz * dz <= r2) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private static void expirePending() {
        if (PENDING.isEmpty()) {
            return;
        }
        PENDING.removeIf(p -> tickCounter - (long) p[3] > PENDING_LIFE_TICKS);
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    //  放置预测的入口
    // ══════════════════════════════════════════════════════════════════════════════════

    /**
     * 本地玩家拿水滴物品右键方块 ⇒ 记一个待认领点。
     *
     * <p>为什么能这样接：{@code ShanhaiDropletItem#useOn} 在服务端用
     * {@code context.getClickLocation()} 当生成坐标，而这里拿到的
     * {@code event.getHitVec().getLocation()} <b>就是同一个点击点</b>
     * （客户端与服务端各自算同一个 {@code BlockHitResult}）。
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getEntity() != mc.player || mc.level == null) {
            return;
        }
        if (!event.getEntity().level().isClientSide()) {
            return;
        }
        final ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !stack.is(ShanhaiItems.STRONG_INTERACTION_DROPLET.get())) {
            return;
        }
        final Vec3 at = event.getHitVec() == null ? null : event.getHitVec().getLocation();
        if (at == null) {
            return;
        }
        PENDING.add(new double[] {at.x, at.y, at.z, (double) tickCounter});
        // 只留最近 4 个（连点放置也不会把内存撑大）
        while (PENDING.size() > 4) {
            PENDING.remove(0);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAll();
    }

    private static void clearAll() {
        OWNED.clear();
        CLAIMED.clear();
        PENDING.clear();
        lastLevel = null;
        lastLoggedOwned = -1;
        loggedUnknown = false;
    }

}
