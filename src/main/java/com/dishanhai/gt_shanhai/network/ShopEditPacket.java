package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopCost;
import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 商店编辑包（C→S）：新增 / 编辑商品条目。字段：商品/数量/分类/NBT + 多元成本 {@link ShopCost}。
 *
 * <p>服务端用 {@link ShopEditPermission#canEdit} 强校验权限，通过后调
 * {@link ShopConfig#addEntry}（ADD）或 {@link ShopConfig#replaceEntry}（EDIT）。</p>
 */
public class ShopEditPacket {

    public enum Action { ADD, EDIT }

    private final Action action;
    private final List<ShopEntry.GoodsStack> goodsList; // 商品清单（≥1 项，多项=组合商品，购买时一次性交付全部）
    private final String category;
    private final String description;
    private final ShopCost cost;
    private final ResourceLocation oldGoods;
    private final String oldCategory;
    private final int oldEntryIndex;          // 原条目在 ShopConfig.getEntries() 的精确索引（-1=回退 goods+category 定位）
    private final long limit;                 // 限购次数（购买/出售共享）；-1=不限
    private final List<ShopEntry.DisplayIcon> displayIcons; // 自定义显示图标（1 主+最多4附属，物品/贴图二选一），空=用商品本身图标
    private final ShopEntry.RewardMode rewardMode; // 奖励模式：NONE=普通固定商品
    private final List<ShopEntry.RewardOption> rewardPool; // 奖励池（各自权重+数量区间），仅 rewardMode != NONE 时有意义
    private final boolean hidden;   // 隐藏商品：不出现在分类网格/枚举里，只能被跳转直达
    private final String linkKey;   // 本条目的跳转目标别名（供其他条目 linkTo 引用），可空
    private final String linkTo;    // 跳转目标：指向某条目 linkKey，可空，非空则详情页显示可点击跳转
    private final String displayName; // 自定义显示名称，可空 = 用商品本身的物品名
    private final String ftbqTableId; // FTBQ 奖励表 ID（十六进制），仅 rewardMode==FTBQ 时有意义

    public ShopEditPacket(Action action, List<ShopEntry.GoodsStack> goodsList, String category, String description,
                          ShopCost cost, ResourceLocation oldGoods, String oldCategory, int oldEntryIndex, long limit,
                          List<ShopEntry.DisplayIcon> displayIcons, ShopEntry.RewardMode rewardMode,
                          List<ShopEntry.RewardOption> rewardPool, boolean hidden, String linkKey, String linkTo,
                          String displayName, String ftbqTableId) {
        this.action = action;
        this.goodsList = (goodsList == null || goodsList.isEmpty())
                ? List.of(ShopEntry.GoodsStack.of(new ResourceLocation("minecraft:air"), 1, null)) : goodsList;
        this.category = category == null || category.isBlank() ? ShopEntry.DEFAULT_CATEGORY : category;
        this.description = description == null ? "" : description;
        this.cost = cost == null ? new ShopCost(BigInteger.ZERO, null, null) : cost;
        this.oldGoods = oldGoods == null ? new ResourceLocation("minecraft:air") : oldGoods;
        this.oldCategory = oldCategory == null ? "" : oldCategory;
        this.oldEntryIndex = oldEntryIndex;
        this.limit = limit < 0L ? -1L : limit;
        this.displayIcons = displayIcons == null ? List.of() : displayIcons;
        this.rewardMode = rewardMode == null ? ShopEntry.RewardMode.NONE : rewardMode;
        this.rewardPool = rewardPool == null ? List.of() : rewardPool;
        this.hidden = hidden;
        this.linkKey = linkKey == null ? "" : linkKey.trim();
        this.linkTo = linkTo == null ? "" : linkTo.trim();
        this.displayName = displayName == null ? "" : displayName.trim();
        this.ftbqTableId = ftbqTableId == null ? "" : ftbqTableId.trim();
    }

    public ShopEditPacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
        int ng = buf.readVarInt();
        List<ShopEntry.GoodsStack> gl = new ArrayList<>(ng);
        for (int i = 0; i < ng; i++) {
            ResourceLocation gid = buf.readResourceLocation();
            int gcount = buf.readVarInt();
            gl.add(ShopEntry.GoodsStack.of(gid, gcount, buf.readNbt()));
        }
        this.goodsList = gl;
        this.category = buf.readUtf();
        this.description = buf.readUtf();
        this.cost = readCost(buf);
        this.oldGoods = buf.readResourceLocation();
        this.oldCategory = buf.readUtf();
        this.oldEntryIndex = buf.readInt();
        this.limit = buf.readLong();
        int ni = buf.readVarInt();
        List<ShopEntry.DisplayIcon> icons = new ArrayList<>(ni);
        for (int i = 0; i < ni; i++) {
            boolean tex = buf.readBoolean();
            icons.add(tex ? ShopEntry.DisplayIcon.ofTexture(buf.readResourceLocation()) : ShopEntry.DisplayIcon.ofItem(buf.readItem()));
        }
        this.displayIcons = icons;
        this.rewardMode = buf.readEnum(ShopEntry.RewardMode.class);
        int np = buf.readVarInt();
        List<ShopEntry.RewardOption> pool = new ArrayList<>(np);
        for (int i = 0; i < np; i++) {
            net.minecraft.world.item.ItemStack st = buf.readItem();
            int weight = buf.readVarInt();
            int min = buf.readVarInt();
            int max = buf.readVarInt();
            pool.add(ShopEntry.RewardOption.of(st, weight, min, max));
        }
        this.rewardPool = pool;
        this.hidden = buf.readBoolean();
        this.linkKey = buf.readUtf();
        this.linkTo = buf.readUtf();
        this.displayName = buf.readUtf();
        this.ftbqTableId = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeVarInt(goodsList.size());
        for (ShopEntry.GoodsStack gs : goodsList) {
            buf.writeResourceLocation(gs.id());
            buf.writeVarInt(gs.count());
            buf.writeNbt(gs.nbt());
        }
        buf.writeUtf(category);
        buf.writeUtf(description);
        writeCost(buf, cost);
        buf.writeResourceLocation(oldGoods);
        buf.writeUtf(oldCategory);
        buf.writeInt(oldEntryIndex);
        buf.writeLong(limit);
        buf.writeVarInt(displayIcons.size());
        for (ShopEntry.DisplayIcon d : displayIcons) {
            buf.writeBoolean(d.isTexture());
            if (d.isTexture()) buf.writeResourceLocation(d.texture());
            else buf.writeItem(d.item());
        }
        buf.writeEnum(rewardMode);
        buf.writeVarInt(rewardPool.size());
        for (ShopEntry.RewardOption opt : rewardPool) {
            buf.writeItem(opt.item());
            buf.writeVarInt(opt.weight());
            buf.writeVarInt(opt.minCount());
            buf.writeVarInt(opt.maxCount());
        }
        buf.writeBoolean(hidden);
        buf.writeUtf(linkKey);
        buf.writeUtf(linkTo);
        buf.writeUtf(displayName);
        buf.writeUtf(ftbqTableId);
    }

    private static void writeCost(FriendlyByteBuf buf, ShopCost cost) {
        buf.writeByteArray(cost.spark.toByteArray());
        buf.writeVarInt(cost.coins.size());
        for (Map.Entry<ResourceLocation, BigInteger> c : cost.coins.entrySet()) {
            buf.writeResourceLocation(c.getKey());
            buf.writeByteArray(c.getValue().toByteArray());
        }
        buf.writeVarInt(cost.physical.size());
        for (ExchangeEntry.Ingredient in : cost.physical) {
            buf.writeResourceLocation(in.id);
            buf.writeBoolean(in.isFluid);
            buf.writeVarLong(in.count);
        }
    }

    private static ShopCost readCost(FriendlyByteBuf buf) {
        BigInteger spark = new BigInteger(buf.readByteArray());
        int nc = buf.readVarInt();
        LinkedHashMap<ResourceLocation, BigInteger> coins = new LinkedHashMap<>();
        for (int i = 0; i < nc; i++) {
            ResourceLocation id = buf.readResourceLocation();
            BigInteger amt = new BigInteger(buf.readByteArray());
            coins.put(id, amt);
        }
        int np = buf.readVarInt();
        List<ExchangeEntry.Ingredient> physical = new ArrayList<>();
        for (int i = 0; i < np; i++) {
            ResourceLocation id = buf.readResourceLocation();
            boolean fluid = buf.readBoolean();
            long cnt = buf.readVarLong();
            physical.add(new ExchangeEntry.Ingredient(id, fluid, cnt));
        }
        return new ShopCost(spark, coins, physical);
    }

    public static void handle(ShopEditPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopEditPacket pkt, ServerPlayer player) {
        if (!ShopEditPermission.canEdit(player)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 无编辑权限"));
            return;
        }
        for (ShopEntry.GoodsStack gs : pkt.goodsList) {
            if (!ForgeRegistries.ITEMS.containsKey(gs.id())) {
                player.sendSystemMessage(Component.literal("§c[山海商店] 商品物品不存在: " + gs.id()));
                return;
            }
        }
        if (!pkt.cost.isValid()) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 成本无效（需至少一项有效的星火/币种/物品/流体）"));
            return;
        }
        ShopEntry entry = new ShopEntry(pkt.goodsList, pkt.category, pkt.cost, pkt.description, pkt.limit,
                pkt.displayIcons, pkt.rewardMode, pkt.rewardPool, pkt.hidden, pkt.linkKey, pkt.linkTo, pkt.displayName, pkt.ftbqTableId);
        String limitTip = entry.isLimited() ? " §d(限" + entry.getRemainingUses() + "次)" : "";

        if (pkt.action == Action.ADD) {
            ShopConfig.addEntry(entry);
            ShopRefreshPacket.sendTo(player); // 回推刷新界面（实时）
            player.sendSystemMessage(Component.literal("§b[山海商店] §a已新增 §f"
                    + entry.getGoodsCount() + "x " + entry.goodsDisplayName() + " §7[" + pkt.category + "]" + limitTip));
            return;
        }

        ShopEntry old = resolveOld(pkt);
        if (old == null) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 待编辑的原条目不存在"));
            return;
        }
        boolean ok = ShopConfig.replaceEntry(old, entry);
        ShopRefreshPacket.sendTo(player); // 回推刷新界面（实时）
        player.sendSystemMessage(ok
                ? Component.literal("§b[山海商店] §a已更新 §f" + entry.goodsDisplayName() + limitTip)
                : Component.literal("§c[山海商店] 更新失败"));
    }

    /**
     * 精确定位待编辑的原条目：优先用客户端下发的 oldEntryIndex —— 同物品不同 NBT 的多条目
     * （如各种超级磁盘阵列，goodsId 都是 super_disk_array）仅靠 goods+category 会误取到首个同物品条目，
     * 导致把更新写到别的条目上（你编辑的这条看着没变）。索引越界或指向条目 goodsId 对不上（列表漂移）时回退。
     */
    private static ShopEntry resolveOld(ShopEditPacket pkt) {
        var all = ShopConfig.getEntries();
        if (pkt.oldEntryIndex >= 0 && pkt.oldEntryIndex < all.size()) {
            ShopEntry e = all.get(pkt.oldEntryIndex);
            if (e != null && e.getGoodsId().equals(pkt.oldGoods)) return e; // 校验索引未漂移
        }
        return locate(pkt.oldGoods, pkt.oldCategory);
    }

    /** 按 goods+category 定位商品（category 为空则只按 goods；同物品多条目会撞车，优先走 {@link #resolveOld}）。 */
    private static ShopEntry locate(ResourceLocation goods, String category) {
        for (ShopEntry e : ShopConfig.getEntries()) {
            if (e.getGoodsId().equals(goods)
                    && (category.isEmpty() || e.getCategory().equals(category))) {
                return e;
            }
        }
        return null;
    }
}
