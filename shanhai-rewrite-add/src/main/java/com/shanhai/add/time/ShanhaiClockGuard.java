package com.shanhai.add.time;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.shanhai.ShanhaiMod;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.item.MilkBucketItem;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.NameTagItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SaddleItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;

/**
 * 山海重构 · <b>「冻结期间禁止改世界」的拦截层</b>（服务端权威）。
 *
 * <h2>1. 口径（用户已定，别改）</h2>
 * 冻结期间玩家<b>只许走动和看</b>：
 * <ul>
 *   <li><b>放行</b>：走动、转视角、聊天、切手持、开面板/看 GUI、{@code /shanhai_add clock …}；</li>
 *   <li><b>拦截</b>：挖方块 / 放方块 / 放置类物品 / 桶与流体 / 点火与爆炸 / 捡起与丢出 /
 *       容器与机器界面里的物品转移 / 攻击造成伤害 / 吃东西喝药 / 会改世界的指令。</li>
 * </ul>
 *
 * <h2>2. 三道防线（从软到硬）</h2>
 * <ol>
 *   <li><b>本类的事件层</b> —— 能给出「你被拦了」的一句提示，是主防线；</li>
 *   <li><b>mixin 层</b> —— 容器点击（{@code AbstractContainerMenu#clicked}）；</li>
 *   <li><b>硬网</b> —— {@code Level#setBlock} 一律返回 {@code false}（{@link ShanhaiClockHooks#denyWorldWrite()}）。
 *       它兜的是"某个第三方 mod 走了我们没挂事件的路径"，<b>不</b>负责提示。</li>
 * </ol>
 *
 * <h2>3. 为什么"世界冻结"本身就已经拦掉了大半</h2>
 * 冻结时 {@code ServerLevel#tick} 整段被跳过 ⇒ 没有 block tick / fluid tick / 实体 tick /
 * 区块 tick / 随机 tick。所以"方块自己变化""机器自己跑""生物自己动"是<b>结构上不可能</b>的，
 * 本类只需要管<b>玩家主动发起</b>的那部分（它们走的是网络包 → 主线程任务，不经过 level tick）。
 *
 * <h2>4. 提示与日志的节流</h2>
 * 玩家侧：同一类别每 5 秒最多一句话（否则连点左键会刷屏）。
 * 日志侧：每类别每 5 秒打一条 {@code [SHANHAI-CLOCK] blocked=<类别> xN}，N = 这 5 秒里的次数。
 */
public final class ShanhaiClockGuard {

    /** 被拦截的动作类别（字符串会进入日志，改名要同步报告）。 */
    public enum Category {
        BREAK("挖方块"),
        PLACE("放置方块"),
        ITEM_USE("使用会改世界的物品"),
        ATTACK("攻击造成伤害"),
        DROP("丢出物品"),
        PICKUP("捡起物品"),
        CONTAINER("容器内物品转移"),
        EXPLOSION("爆炸"),
        COMMAND("会改世界的指令"),
        WORLD_WRITE("世界写入（硬网）");

        public final String cn;

        Category(String cn) {
            this.cn = cn;
        }
    }

    /** 玩家提示节流窗口。 */
    private static final long MESSAGE_INTERVAL_MS = 5_000L;
    /** 日志节流窗口。 */
    private static final long LOG_INTERVAL_MS = 5_000L;

    private static final Map<String, Long> LAST_MESSAGE = new HashMap<>();
    private static final Map<Category, Integer> PENDING_COUNT = new EnumMap<>(Category.class);
    private static final Map<Category, Long> LAST_LOG = new EnumMap<>(Category.class);
    /** 累计计数（status 用）。 */
    private static final Map<Category, Long> TOTAL = new EnumMap<>(Category.class);

    /**
     * 会直接改写世界的物品类型。
     *
     * <p>⚠️ 这是一份<b>显式清单</b>，不是"全部危险物品"的证明：第三方 mod 自己的物品不在这里
     * （见报告「待你确认」）。{@code BlockItem} 刻意<b>不</b>列进来 —— 放置走
     * {@link BlockEvent.EntityPlaceEvent}，在那里拦能拿到坐标，提示也更准。
     */
    private static boolean isWorldChangingItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.isEdible()) {
            return true;
        }
        var item = stack.getItem();
        return item instanceof BucketItem          // 水桶/岩浆桶/鱼桶（含 SolidBucketItem 子类）
                || item instanceof MilkBucketItem  // 喝牛奶
                || item instanceof SolidBucketItem // 细雪桶
                || item instanceof FlintAndSteelItem
                || item instanceof FireChargeItem
                || item instanceof BoneMealItem
                || item instanceof HoeItem
                || item instanceof ShovelItem
                || item instanceof AxeItem
                || item instanceof ShearsItem
                || item instanceof DyeItem
                || item instanceof BoatItem
                || item instanceof MinecartItem
                || item instanceof EnderpearlItem
                || item instanceof SnowballItem
                || item instanceof EggItem
                || item instanceof ExperienceBottleItem
                || item instanceof PotionItem
                || item instanceof TridentItem
                || item instanceof ArrowItem
                || item instanceof NameTagItem
                || item instanceof LeadItem
                || item instanceof SaddleItem
                || item instanceof HangingEntityItem;
    }

    // ================================================================== 事件层

    /** 挖方块。 */
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!paused()) {
            return;
        }
        event.setCanceled(true);
        blocked(Category.BREAK, event.getPlayer());
    }

    /** 放置方块（{@code EntityMultiPlaceEvent} 是它的子类，双半砖/床/门会走子类）。 */
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!paused()) {
            return;
        }
        event.setCanceled(true);
        blocked(Category.PLACE, event.getEntity());
    }

    /** 右键方块：只拦"会改世界的物品"，其余（开箱子/开机器面板/看告示牌）放行。 */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!paused() || !isWorldChangingItem(event.getItemStack())) {
            return;
        }
        event.setCanceled(true);
        blocked(Category.ITEM_USE, event.getEntity());
    }

    /** 右键空中（桶、食物、药水、末影珍珠、雪球、打火石…）。 */
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!paused() || !isWorldChangingItem(event.getItemStack())) {
            return;
        }
        event.setCanceled(true);
        blocked(Category.ITEM_USE, event.getEntity());
    }

    /** 左键攻击（取消事件 ⇒ 伤害不进 {@code hurt} 链）。 */
    public static void onAttack(AttackEntityEvent event) {
        if (!paused()) {
            return;
        }
        event.setCanceled(true);
        blocked(Category.ATTACK, event.getEntity());
    }

    /**
     * 伤害兜底：任何<b>玩家造成</b>的伤害一律抹掉。
     *
     * <p>为什么还要这条：{@link #onAttack} 只覆盖"玩家左键实体"，覆盖不到
     * 弓箭/三叉戟/药水/荆棘/TNT 这类"玩家造成"的间接伤害。
     */
    public static void onHurt(LivingHurtEvent event) {
        if (!paused()) {
            return;
        }
        Entity source = event.getSource().getEntity();
        if (!(source instanceof Player player)) {
            return;
        }
        event.setCanceled(true);
        blocked(Category.ATTACK, player);
    }

    /** 丢出物品（Q 键 / 拖出 GUI）。
     *
     *  <p>⚠️ 2026-10-18 起这条<b>只是第三道纵深</b>：真正的拦截点在
     *  {@code Player#drop(boolean)} 的 HEAD（{@link ShanhaiClockHooks#denyItemDrop}），
     *  因为到这里时 {@code inventory.removeFromSelected(...)} 已经把数量扣掉了 ——
     *  只取消本事件会造出"背包少了、地上没有"的凭空消失（用户实机反馈）。
     *  保留它是为了兜"别的 mod 直接发 {@code ItemTossEvent}"这类路径。 */
    public static void onToss(ItemTossEvent event) {
        if (!paused()) {
            return;
        }
        event.setCanceled(true);
        blocked(Category.DROP, event.getPlayer());
    }

    /**
     * 捡起物品。
     *
     * <p>结构上本来就捡不起来（{@code ItemEntity} 不 tick ⇒ {@code playerTouch} 不会被调到），
     * 这条是<b>显式保险</b>：万一有 mod 直接把物品塞给玩家，这里也会被拦。
     */
    public static void onPickup(EntityItemPickupEvent event) {
        if (!paused()) {
            return;
        }
        event.setCanceled(true);
        blocked(Category.PICKUP, event.getEntity());
    }

    /** 爆炸（TNT/苦力怕/床）：取消整个爆炸的起爆。 */
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (!paused()) {
            return;
        }
        event.setCanceled(true);
        blocked(Category.EXPLOSION, null);
    }

    /**
     * 会改世界的指令。
     *
     * <p>⚠️ 这是<b>黑名单</b>，不是白名单：没列进来的指令仍然能跑（比如第三方 mod 的指令）。
     * 口径见报告「待你确认」。
     *
     * <p>只拦<b>玩家发起</b>的指令：控制台/命令方块是运维通道，冻结期间仍可用
     * （命令方块本来也不会 tick）。
     */
    public static void onCommand(CommandEvent event) {
        if (!paused()) {
            return;
        }
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        if (source.getEntity() == null) {
            return; // 控制台 / 命令方块
        }
        String root = commandRoot(event);
        if (root.isEmpty() || !WORLD_CHANGING_COMMANDS.contains(root)) {
            return;
        }
        event.setCanceled(true);
        blocked(Category.COMMAND, source.getEntity());
    }

    /** 世界写操作的硬网（{@code Level#setBlock} 被 mixin 拦下时调这里记一笔）。 */
    public static void noteWorldWriteBlocked() {
        blocked(Category.WORLD_WRITE, null);
    }

    /**
     * 供 mixin 转发层记一次"被拦"，<b>不需要</b>它是 Forge 事件。
     *
     * <p>为什么要有这个公开入口（2026-10-18）：这一轮新增的三处拦截
     * （{@code ServerPlayer#drop(boolean)}、{@code LocalPlayer#drop(boolean)}、
     * {@code ServerGamePacketListenerImpl#handleContainerClick}）都<b>不是</b> Forge 事件，
     * 但用户可见的判据必须是<b>同一套</b>日志与提示（{@code blocked=DROP xN} / {@code blocked=CONTAINER xN}），
     * 否则"哪条拦在哪一层生效"在日志上就分不出来。
     *
     * @param category 类别
     * @param actor    触发者（只有 {@link ServerPlayer} 会收到黄字提示；{@code null} 仅记日志）
     */
    public static void note(Category category, Object actor) {
        blocked(category, actor instanceof Entity e ? e : null);
    }

    private static String commandRoot(CommandEvent event) {
        var nodes = event.getParseResults().getContext().getNodes();
        if (nodes.isEmpty()) {
            return "";
        }
        return nodes.get(0).getNode().getName().toLowerCase(Locale.ROOT);
    }

    /**
     * 冻结期间禁止的指令根节点（黑名单）。
     *
     * <p>{@code execute} 也被列进来：它是任意指令的包装器，放行它等于黑名单全废。
     */
    private static final Set<String> WORLD_CHANGING_COMMANDS = Set.of(
            "setblock", "fill", "clone", "summon", "kill", "damage", "effect", "enchant",
            "item", "xp", "experience", "give", "clear", "time", "weather", "gamerule",
            "difficulty", "setworldspawn", "setidletimeout", "forceload", "spreadplayers",
            "function", "data", "execute", "place", "loot", "advancement", "recipe", "jfr");

    // ================================================================== 提示 / 计数

    private static boolean paused() {
        return ShanhaiWorldClock.SERVER.isPaused();
    }

    /**
     * 记一次拦截：累计计数 + 玩家提示（节流）+ 日志（节流）。
     *
     * @param category 类别
     * @param actor    触发者（任意实体，只有 {@link ServerPlayer} 才会收到提示）；
     *                 {@code null} 表示"没有具体的玩家"（爆炸/硬网）
     */
    private static void blocked(Category category, Entity actor) {
        TOTAL.merge(category, 1L, Long::sum);
        PENDING_COUNT.merge(category, 1, Integer::sum);

        long now = System.currentTimeMillis();
        Long lastLog = LAST_LOG.get(category);
        if (lastLog == null || now - lastLog >= LOG_INTERVAL_MS) {
            int n = PENDING_COUNT.getOrDefault(category, 0);
            if (n > 0) {
                PENDING_COUNT.put(category, 0);
                LAST_LOG.put(category, now);
                ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] blocked={} x{}（{}；累计 {}）",
                        category.name(), n, category.cn, TOTAL.getOrDefault(category, 0L));
            }
        }

        if (actor instanceof ServerPlayer serverPlayer) {
            String key = serverPlayer.getUUID() + "/" + category.name();
            Long last = LAST_MESSAGE.get(key);
            if (last == null || now - last >= MESSAGE_INTERVAL_MS) {
                LAST_MESSAGE.put(key, now);
                serverPlayer.sendSystemMessage(Component.literal("[山海时钟] 时间已暂停：不能")
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal(category.cn).withStyle(ChatFormatting.RED))
                        .append(Component.literal("（/shanhai_add clock resume 恢复）").withStyle(ChatFormatting.GRAY)));
            }
        }
    }

    /** status 用：各类别累计拦截次数。 */
    public static String summary() {
        if (TOTAL.isEmpty()) {
            return "blocked=<none>";
        }
        StringBuilder sb = new StringBuilder("blocked=");
        boolean first = true;
        for (Map.Entry<Category, Long> e : TOTAL.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey().name().toLowerCase(Locale.ROOT)).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 重新开世界/切换存档时清空计数（避免读到上一局残留）。 */
    public static void resetCounters() {
        TOTAL.clear();
        PENDING_COUNT.clear();
        LAST_LOG.clear();
        LAST_MESSAGE.clear();
    }

    private ShanhaiClockGuard() {
    }
}
