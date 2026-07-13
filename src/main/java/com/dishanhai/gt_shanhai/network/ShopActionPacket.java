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
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 商店动作包（C→S）：购买 / 出售 / 充值全部 / 删除条目。
 *
 * <p>服务端校验发包玩家背包/饰品栏任意位置是否携带钱包 {@link WalletItem#isCarrying}，
 * 用玩家账户虚拟余额结算（{@link ShopPurchase}）。商品用 goodsId+category 定位到 {@link ShopEntry}。</p>
 */
public class ShopActionPacket {

    public enum Action { BUY, SELL, DEPOSIT, DELETE, UNDO_DELETE }

    private final Action action;
    private final ResourceLocation goodsId;   // DEPOSIT 时忽略
    private final String category;            // 定位条目用（同 goods 可能跨分类）
    private final long times;                 // 购买/出售次数（支持 Long.MAX）
    private final boolean aeMode;             // AE 模式：交付优先注入玩家绑定的 AE 网络
    private final int entryIndex;             // 条目在 ShopConfig.getEntries() 的精确索引（-1=未知则回退 goodsId+category 定位）
    private final int chosenRewardIndex;      // 自选奖励模式：玩家在选择界面选中的 rewardPool 下标；-1=未选/不适用

    public ShopActionPacket(Action action, ResourceLocation goodsId, String category, long times) {
        this(action, goodsId, category, times, false, -1);
    }

    public ShopActionPacket(Action action, ResourceLocation goodsId, String category, long times, boolean aeMode) {
        this(action, goodsId, category, times, aeMode, -1);
    }

    public ShopActionPacket(Action action, ResourceLocation goodsId, String category, long times, boolean aeMode, int entryIndex) {
        this(action, goodsId, category, times, aeMode, entryIndex, -1);
    }

    public ShopActionPacket(Action action, ResourceLocation goodsId, String category, long times, boolean aeMode,
                            int entryIndex, int chosenRewardIndex) {
        this.action = action;
        this.goodsId = goodsId == null ? new ResourceLocation("minecraft:air") : goodsId;
        this.category = category == null ? "" : category;
        this.times = Math.max(1L, times);
        this.aeMode = aeMode;
        this.entryIndex = entryIndex;
        this.chosenRewardIndex = chosenRewardIndex;
    }

    public ShopActionPacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
        this.goodsId = buf.readResourceLocation();
        this.category = buf.readUtf();
        this.times = buf.readVarLong();
        this.aeMode = buf.readBoolean();
        this.entryIndex = buf.readInt();
        this.chosenRewardIndex = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeResourceLocation(goodsId);
        buf.writeUtf(category);
        buf.writeVarLong(times);
        buf.writeBoolean(aeMode);
        buf.writeInt(entryIndex);
        buf.writeInt(chosenRewardIndex);
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
        if (!WalletItem.isCarrying(player)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 需持有山海钱包（背包/饰品栏任意位置都行）"));
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

        if (pkt.action == Action.UNDO_DELETE) {
            if (!com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.canEdit(player)) {
                player.sendSystemMessage(Component.literal("§c[山海商店] 无权限撤销"));
                return;
            }
            ShopEntry restored = ShopConfig.undoLastRemove();
            if (restored != null) {
                ShopRefreshPacket.sendTo(player);
                player.sendSystemMessage(Component.literal("§b[山海商店] §a已撤销删除 §f" + restored.goodsDisplayName()));
            } else {
                player.sendSystemMessage(Component.literal("§c[山海商店] 没有可撤销的删除（已超时或已撤销过）"));
            }
            return;
        }

        ShopEntry entry = resolve(pkt);
        if (entry == null) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 商品不存在"));
            return;
        }

        switch (pkt.action) {
            case BUY -> doBuy(player, entry, pkt.times, pkt.aeMode, pkt.chosenRewardIndex);
            case SELL -> doSell(player, entry, pkt.times);
            case DELETE -> {
                if (!com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.canEdit(player)) {
                    player.sendSystemMessage(Component.literal("§c[山海商店] 无权限删除"));
                    return;
                }
                boolean ok = ShopConfig.removeEntry(entry);
                ShopRefreshPacket.sendTo(player); // 回推刷新界面（实时）
                player.sendSystemMessage(ok
                        ? Component.literal("§b[山海商店] §a已删除 §f" + entry.goodsDisplayName())
                        : Component.literal("§c[山海商店] 删除失败"));
            }
            default -> {}
        }
    }

    private static void doBuy(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, int chosenRewardIndex) {
        boolean cheat = com.dishanhai.gt_shanhai.common.shop.ShopCheatMode.isEnabled(player.getUUID());
        if (entry.hasMissingItems()) {
            // 商品/成本引用的物品当前注册表里查不到（对应模组缺失/被卸载）：客户端按钮已经拦了，这里是兜底，
            // 消息要说清楚原因，别落到下面的"余额不足"分支误导玩家去充值。
            player.sendSystemMessage(Component.literal("§c[山海商店] 该商品引用的物品缺失（对应模组可能未安装），无法购买"));
            return;
        }
        if (!cheat && entry.isLimited() && entry.getRemainingUses() <= 0L) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 该商品限购次数已用完"));
            return;
        }
        // 交易前的剩余限购次数：若本次成交后限购归零且未买够 times，说明是"次数用尽"而非"余额不足"，
        // 消息要用这个数区分，见下方 limitExhausted 分支。
        long remainingBeforeUses = entry.isLimited() ? entry.getRemainingUses() : -1L;
        ShopPurchase.BulkBuyResult r;
        switch (entry.getRewardMode()) {
            case CHOICE -> {
                var pool = entry.getRewardPool();
                if (chosenRewardIndex < 0 || chosenRewardIndex >= pool.size()) {
                    player.sendSystemMessage(Component.literal("§c[山海商店] 选择的奖励无效，请重新选择"));
                    return;
                }
                ShopEntry.RewardOption chosen = pool.get(chosenRewardIndex);
                r = cheat ? ShopPurchase.giveBulkChoice(player, entry, times, aeMode, chosen)
                          : ShopPurchase.buyBulkChoice(player, entry, times, aeMode, chosen);
            }
            case RANDOM -> r = cheat ? ShopPurchase.giveBulkRandom(player, entry, times, aeMode)
                                      : ShopPurchase.buyBulkRandom(player, entry, times, aeMode);
            case ALL -> r = cheat ? ShopPurchase.giveBulkAll(player, entry, times, aeMode)
                                   : ShopPurchase.buyBulkAll(player, entry, times, aeMode);
            case FTBQ -> {
                // FTBQ 表也支持自选/随机/全部三种子模式（见 ShopEntry#getFtbqSubMode），语义对齐本地奖励池，
                // 只是奖励来源换成表内容；自选沿用同一个 chosenRewardIndex 字段，服务端按表内「仅物品类奖励」
                // 子序列的下标重新解出实际物品（见 ShopPurchase#resolveFtbqItemRewardByIndex）。
                r = switch (entry.getFtbqSubMode()) {
                    case CHOICE -> cheat
                            ? ShopPurchase.giveBulkFtbqChoice(player, entry, times, aeMode, chosenRewardIndex)
                            : ShopPurchase.buyBulkFtbqChoice(player, entry, times, aeMode, chosenRewardIndex);
                    case ALL -> cheat
                            ? ShopPurchase.giveBulkFtbqAll(player, entry, times, aeMode)
                            : ShopPurchase.buyBulkFtbqAll(player, entry, times, aeMode);
                    default -> cheat
                            ? ShopPurchase.giveBulkFtbq(player, entry, times, aeMode)
                            : ShopPurchase.buyBulkFtbq(player, entry, times, aeMode);
                };
            }
            default -> r = cheat ? ShopPurchase.giveBulk(player, entry, times, aeMode)   // 作弊：不扣成本直取
                                  : ShopPurchase.buyBulk(player, entry, times, aeMode);
        }
        if (r.done() <= 0L) {
            player.sendSystemMessage(Component.literal(cheat
                    ? "§c[山海商店] 商品无效，无法获取"
                    : "§c[山海商店] 余额不足，先充值"));
            return;
        }
        if (!cheat) {
            // 已购买次数只统计真实付款的购买（作弊直取不算"买"），随账户快照一起推给客户端展示
            WalletAccountAPI.addPurchaseCount(player.getServer(), player.getUUID(),
                    WalletAccountAPI.purchaseKey(entry.getGoodsId(), entry.getCategory()), r.done());
            WalletAccountAPI.sync(player);
        }
        String viaText = switch (r.via() == null ? "" : r.via()) {
            case "ae" -> " §7→ 已注入 AE 网络";
            case "sda" -> " §7→ 已打包为超级磁盘阵列";
            default -> " §7→ 已放入背包";
        };
        String amt = ShopPurchase.formatCount(r.done());
        // 奖励表模式（CHOICE/RANDOM/ALL/FTBQ）单次独立随机数受 rewardRollCap() 硬顶（防卡服），命中上限时
        // done 恰好等于该顶值——这不是余额不够，是"这一下子买太多了，分批买"，消息不能说成"余额不足"误导玩家。
        boolean rollCapped = entry.getRewardMode() != ShopEntry.RewardMode.NONE
                && r.done() >= ShopPurchase.rewardRollCap() && times > r.done();
        // 本次成交把限购额度用完了（而不是余额不够），提示要说"次数用完"而不是"余额不足"，免得误导玩家去充值。
        boolean limitExhausted = !cheat && entry.isLimited() && entry.getRemainingUses() <= 0L;
        if (cheat) {
            player.sendSystemMessage(Component.literal("§d[山海商店·作弊] §a直接获取 §f" + amt + " §a次 " + entry.goodsDisplayName() + viaText));
        } else if (r.done() == times) {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a成功购买 §f" + amt + " §a次 " + entry.goodsDisplayName() + viaText));
        } else if (rollCapped) {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a本次抽取 §f" + amt + " §a次 " + entry.goodsDisplayName()
                    + " §7(单次最多抽 " + ShopPurchase.formatCount(ShopPurchase.rewardRollCap()) + " 次，再买一次继续)" + viaText));
        } else if (limitExhausted) {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a达到购买次数 §f" + amt + " §a次后可购买次数不足§7，商品限制次数 §f"
                    + ShopPurchase.formatCount(remainingBeforeUses) + viaText));
        } else {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a购买 §f" + amt + "§7/§f" + ShopPurchase.formatCount(times) + " §a次后余额不足" + viaText));
        }
    }

    private static void doSell(ServerPlayer player, ShopEntry entry, long times) {
        if (entry.getRewardMode() != ShopEntry.RewardMode.NONE) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 自选/随机/全部奖励商品不支持出售"));
            return;
        }
        if (entry.isLimited() && entry.getRemainingUses() <= 0L) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 该商品限购次数已用完"));
            return;
        }
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

    /**
     * 精确定位条目：优先用客户端下发的 entryIndex —— 同物品不同 NBT 的多条目（如各种超级磁盘阵列）
     * goodsId 会撞车，仅靠 {@link #locate} 的 goodsId+category 会误取到首个同物品条目。
     * 索引越界或指向的条目 goodsId 对不上（列表漂移）时，回退按 goodsId+category 定位。
     */
    private static ShopEntry resolve(ShopActionPacket pkt) {
        var all = ShopConfig.getEntries();
        if (pkt.entryIndex >= 0 && pkt.entryIndex < all.size()) {
            ShopEntry e = all.get(pkt.entryIndex);
            if (e != null && e.getGoodsId().equals(pkt.goodsId)) return e; // 校验索引未漂移
        }
        return locate(pkt.goodsId, pkt.category);
    }

    /** 按 goodsId + category 定位商品条目（回退用；同物品多条目会撞车，优先走 {@link #resolve}）。 */
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
