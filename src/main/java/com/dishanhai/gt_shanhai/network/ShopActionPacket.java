package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;
import com.dishanhai.gt_shanhai.common.item.WalletItem;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 商店动作包（C→S）：购买 / 出售 / 充值全部 / 删除条目。
 *
 * <p>服务端从发包玩家的主手/副手定位钱包 {@link WalletItem}，用钱包 NBT 虚拟余额结算
 * （{@link ShopPurchase}）。商品用 goodsId+category 定位到 {@link ShopEntry}。</p>
 */
public class ShopActionPacket {

    public enum Action { BUY, SELL, DEPOSIT, DELETE }

    private final Action action;
    private final ResourceLocation goodsId;   // DEPOSIT 时忽略
    private final String category;            // 定位条目用（同 goods 可能跨分类）
    private final long times;                 // 购买/出售次数（支持 Long.MAX）
    private final boolean aeMode;             // AE 模式：交付优先注入玩家绑定的 AE 网络

    public ShopActionPacket(Action action, ResourceLocation goodsId, String category, long times) {
        this(action, goodsId, category, times, false);
    }

    public ShopActionPacket(Action action, ResourceLocation goodsId, String category, long times, boolean aeMode) {
        this.action = action;
        this.goodsId = goodsId == null ? new ResourceLocation("minecraft:air") : goodsId;
        this.category = category == null ? "" : category;
        this.times = Math.max(1L, times);
        this.aeMode = aeMode;
    }

    public ShopActionPacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
        this.goodsId = buf.readResourceLocation();
        this.category = buf.readUtf();
        this.times = buf.readVarLong();
        this.aeMode = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeResourceLocation(goodsId);
        buf.writeUtf(category);
        buf.writeVarLong(times);
        buf.writeBoolean(aeMode);
    }

    public static void handle(ShopActionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopActionPacket pkt, ServerPlayer player) {
        ItemStack wallet = findWallet(player);
        if (wallet.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 需手持山海钱包"));
            return;
        }

        if (pkt.action == Action.DEPOSIT) {
            long got = ShopPurchase.deposit(player);
            if (got > 0) WalletAccountAPI.sync(player);
            player.sendSystemMessage(got > 0
                    ? Component.literal("§b[山海商店] §a已充值 §f" + got + " §a枚币入钱包")
                    : Component.literal("§c[山海商店] 背包里没有可充值的货币"));
            return;
        }

        ShopEntry entry = locate(pkt.goodsId, pkt.category);
        if (entry == null) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 商品不存在"));
            return;
        }

        switch (pkt.action) {
            case BUY -> doBuy(player, entry, pkt.times, pkt.aeMode);
            case SELL -> doSell(player, entry, pkt.times);
            case DELETE -> {
                if (!com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.canEdit(player)) {
                    player.sendSystemMessage(Component.literal("§c[山海商店] 无权限删除"));
                    return;
                }
                boolean ok = ShopConfig.removeEntry(entry);
                player.sendSystemMessage(ok
                        ? Component.literal("§b[山海商店] §a已删除 §f" + entry.goodsDisplayName())
                        : Component.literal("§c[山海商店] 删除失败"));
            }
            default -> {}
        }
    }

    private static void doBuy(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
        ShopPurchase.BulkBuyResult r = ShopPurchase.buyBulk(player, entry, times, aeMode);
        if (r.done() <= 0L) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 余额不足，先充值"));
            return;
        }
        WalletAccountAPI.sync(player);
        String viaText = switch (r.via() == null ? "" : r.via()) {
            case "ae" -> " §7→ 已注入 AE 网络";
            case "sda" -> " §7→ 已打包为超级磁盘阵列";
            default -> " §7→ 已放入背包";
        };
        String amt = ShopPurchase.formatCount(r.done());
        if (r.done() == times) {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a成功购买 §f" + amt + " §a次 " + entry.goodsDisplayName() + viaText));
        } else {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a购买 §f" + amt + "§7/§f" + ShopPurchase.formatCount(times) + " §a次后余额不足" + viaText));
        }
    }

    private static void doSell(ServerPlayer player, ShopEntry entry, long times) {
        long sold = ShopPurchase.sellBulk(player, entry, times);
        if (sold <= 0L) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 背包里没有足够的 " + entry.goodsDisplayName()));
            return;
        }
        WalletAccountAPI.sync(player);
        String amt = ShopPurchase.formatCount(sold);
        if (sold == times) {
            player.sendSystemMessage(Component.literal("§b[山海商店] §e成功出售 §f" + amt + " §e份 " + entry.goodsDisplayName()));
        } else {
            player.sendSystemMessage(Component.literal("§b[山海商店] §e出售 §f" + amt + "§7/§f" + ShopPurchase.formatCount(times) + " §e份后商品不足"));
        }
    }

    /** 从主手/副手找钱包 ItemStack。 */
    private static ItemStack findWallet(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack s = player.getItemInHand(hand);
            if (!s.isEmpty() && s.getItem() instanceof WalletItem) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 按 goodsId + category 定位商品条目。 */
    private static ShopEntry locate(ResourceLocation goodsId, String category) {
        for (ShopEntry e : ShopConfig.getEntries()) {
            if (e.getGoodsId().equals(goodsId)
                    && (category.isEmpty() || e.getCategory().equals(category))) {
                return e;
            }
        }
        return null;
    }
}
