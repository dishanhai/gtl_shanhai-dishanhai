package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Supplier;

/**
 * 商店编辑包（C→S）：新增 / 编辑商品条目。
 *
 * <p>与买卖包 {@link ShopActionPacket} 分离，字段齐全（商品/数量/货币/价格/分类）。
 * 服务端用 {@link ShopEditPermission#canEdit} 强校验权限（OP 或白名单），
 * 通过后调 {@link ShopConfig#addEntry}（ADD）或 {@link ShopConfig#replaceEntry}（EDIT，
 * 按旧 goods+category 定位原条目）。</p>
 */
public class ShopEditPacket {

    public enum Action { ADD, EDIT }

    private final Action action;
    // 新条目字段
    private final ResourceLocation goods;
    private final int count;
    private final ResourceLocation currency;
    private final int price;
    private final String category;
    private final net.minecraft.nbt.CompoundTag goodsNbt; // 商品 NBT 快照（可空）
    // EDIT 时用于定位原条目（ADD 时忽略）
    private final ResourceLocation oldGoods;
    private final String oldCategory;

    public ShopEditPacket(Action action, ResourceLocation goods, int count, ResourceLocation currency,
                          int price, String category, net.minecraft.nbt.CompoundTag goodsNbt,
                          ResourceLocation oldGoods, String oldCategory) {
        this.action = action;
        this.goods = goods == null ? new ResourceLocation("minecraft:air") : goods;
        this.count = Math.max(1, count);
        this.currency = currency == null ? new ResourceLocation("minecraft:air") : currency;
        this.price = Math.max(0, price);
        this.category = category == null || category.isBlank() ? ShopEntry.DEFAULT_CATEGORY : category;
        this.goodsNbt = (goodsNbt == null || goodsNbt.isEmpty()) ? null : goodsNbt;
        this.oldGoods = oldGoods == null ? new ResourceLocation("minecraft:air") : oldGoods;
        this.oldCategory = oldCategory == null ? "" : oldCategory;
    }

    public ShopEditPacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
        this.goods = buf.readResourceLocation();
        this.count = buf.readVarInt();
        this.currency = buf.readResourceLocation();
        this.price = buf.readVarInt();
        this.category = buf.readUtf();
        this.goodsNbt = buf.readNbt(); // 无 NBT 时对端写的是 null，readNbt 返回 null
        this.oldGoods = buf.readResourceLocation();
        this.oldCategory = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeResourceLocation(goods);
        buf.writeVarInt(count);
        buf.writeResourceLocation(currency);
        buf.writeVarInt(price);
        buf.writeUtf(category);
        buf.writeNbt(goodsNbt); // null 安全：writeNbt 接受 null
        buf.writeResourceLocation(oldGoods);
        buf.writeUtf(oldCategory);
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
        if (!ForgeRegistries.ITEMS.containsKey(pkt.goods)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 商品物品不存在: " + pkt.goods));
            return;
        }
        if (!ForgeRegistries.ITEMS.containsKey(pkt.currency)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 货币物品不存在: " + pkt.currency));
            return;
        }
        ShopEntry entry = new ShopEntry(pkt.goods, pkt.count, pkt.currency, pkt.price, pkt.category, pkt.goodsNbt);

        if (pkt.action == Action.ADD) {
            ShopConfig.addEntry(entry);
            player.sendSystemMessage(Component.literal("§b[山海商店] §a已新增 §f"
                    + pkt.count + "x " + entry.goodsDisplayName() + " §7[" + pkt.category + "]"));
            return;
        }

        // EDIT：按旧 goods+category 定位原条目后替换
        ShopEntry old = locate(pkt.oldGoods, pkt.oldCategory);
        if (old == null) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 待编辑的原条目不存在"));
            return;
        }
        boolean ok = ShopConfig.replaceEntry(old, entry);
        player.sendSystemMessage(ok
                ? Component.literal("§b[山海商店] §a已更新 §f" + entry.goodsDisplayName())
                : Component.literal("§c[山海商店] 更新失败"));
    }

    /** 按 goods+category 定位商品（category 为空则只按 goods）。 */
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
