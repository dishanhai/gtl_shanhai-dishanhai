package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogSnapshot;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;
import com.dishanhai.gt_shanhai.common.item.WalletItem;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 商店动作包（C→S）：购买 / 出售 / 充值全部 / 删除条目。
 *
 * <p>服务端校验发包玩家背包/饰品栏任意位置是否携带钱包 {@link WalletItem#isCarrying}，
 * 用玩家账户虚拟余额结算（{@link ShopPurchase}）。商品用目录版本与服务端条目身份精确定位。</p>
 */
public class ShopActionPacket {

    public enum Action { BUY, SELL, DEPOSIT, DELETE, UNDO_DELETE }

    private final Action action;
    private final long catalogRevision;       // 服务端目录结构版本，过期请求必须拒绝
    private final long entryKey;              // 当前 revision 内的服务端商品身份；无商品动作=-1
    private final long times;                 // 购买/出售次数（支持 Long.MAX）
    private final boolean aeMode;             // AE 模式：交付优先注入玩家绑定的 AE 网络
    private final boolean backpackMode;       // 精妙背包模式：扣款/交付优先走随身穿戴的精妙背包
    private final int chosenRewardIndex;      // 自选奖励模式：玩家在选择界面选中的 rewardPool 下标；-1=未选/不适用
    private final boolean fromCart;           // 购物车结算发起的 BUY：服务端处理完额外回一个结构化结果包（见 ShopCartPurchaseResultPacket）

    public ShopActionPacket(Action action, long catalogRevision, long entryKey, long times) {
        this(action, catalogRevision, entryKey, times, false, false, -1, false);
    }

    public ShopActionPacket(Action action, long catalogRevision, long entryKey, long times, boolean aeMode) {
        this(action, catalogRevision, entryKey, times, aeMode, false, -1, false);
    }

    public ShopActionPacket(Action action, long catalogRevision, long entryKey, long times,
                            boolean aeMode, boolean backpackMode) {
        this(action, catalogRevision, entryKey, times, aeMode, backpackMode, -1, false);
    }

    public ShopActionPacket(Action action, long catalogRevision, long entryKey, long times, boolean aeMode,
                            boolean backpackMode, int chosenRewardIndex) {
        this(action, catalogRevision, entryKey, times, aeMode, backpackMode, chosenRewardIndex, false);
    }

    public ShopActionPacket(Action action, long catalogRevision, long entryKey, long times, boolean aeMode,
                            boolean backpackMode, int chosenRewardIndex, boolean fromCart) {
        this.action = action;
        this.catalogRevision = catalogRevision;
        this.entryKey = entryKey;
        this.times = Math.max(1L, times);
        this.aeMode = aeMode;
        this.backpackMode = backpackMode;
        this.chosenRewardIndex = chosenRewardIndex;
        this.fromCart = fromCart;
    }

    public ShopActionPacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
        this.catalogRevision = buf.readLong();
        this.entryKey = buf.readLong();
        this.times = buf.readVarLong();
        this.aeMode = buf.readBoolean();
        this.backpackMode = buf.readBoolean();
        this.chosenRewardIndex = buf.readInt();
        this.fromCart = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeLong(catalogRevision);
        buf.writeLong(entryKey);
        buf.writeVarLong(times);
        buf.writeBoolean(aeMode);
        buf.writeBoolean(backpackMode);
        buf.writeInt(chosenRewardIndex);
        buf.writeBoolean(fromCart);
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
        if (pkt.action != Action.DEPOSIT) {
            ShopCatalogSnapshot current = ShopConfig.snapshot();
            if (current.revision() != pkt.catalogRevision) {
                ShopCatalogManifestPacket.sendLatestIfAllowed(
                        player, current.manifest(), pkt.catalogRevision);
                if (pkt.action != Action.UNDO_DELETE) {
                    player.sendSystemMessage(Component.literal("§c[山海商店] 商品目录已更新，请重试"));
                    sendCartResult(player, pkt, 0L, "商品目录已更新，请重试");
                    return;
                }
            }
        }

        if (!WalletItem.isCarrying(player)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 需持有山海钱包（背包/饰品栏任意位置都行）"));
            sendCartResult(player, pkt, 0L, "需持有山海钱包");
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
                player.sendSystemMessage(Component.literal("§b[山海商店] §a已撤销删除 §f" + restored.goodsDisplayName()));
            } else {
                player.sendSystemMessage(Component.literal("§c[山海商店] 没有可撤销的删除（已超时或已撤销过）"));
            }
            return;
        }

        ShopEntry entry = resolve(pkt);
        if (entry == null) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 商品不存在"));
            sendCartResult(player, pkt, 0L, "商品不存在");
            return;
        }

        long remainingUsesBefore = entry.getRemainingUses();
        switch (pkt.action) {
            case BUY -> doBuy(player, entry, pkt);
            case SELL -> doSell(player, entry, pkt.times, pkt.backpackMode);
            case DELETE -> {
                if (!com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.canEdit(player)) {
                    player.sendSystemMessage(Component.literal("§c[山海商店] 无权限删除"));
                    return;
                }
                if (!com.dishanhai.gt_shanhai.common.shop.ShopEditMode.isEnabled(player.getUUID())) {
                    player.sendSystemMessage(Component.literal("§c[山海商店] 请先执行 /山海 商店 编辑 开启编辑模式后再删除商品"));
                    return;
                }
                boolean ok = ShopConfig.removeEntry(entry);
                player.sendSystemMessage(ok
                        ? Component.literal("§b[山海商店] §a已删除 §f" + entry.goodsDisplayName())
                        : Component.literal("§c[山海商店] 删除失败"));
            }
            default -> {}
        }
        if ((pkt.action == Action.BUY || pkt.action == Action.SELL) && remainingUsesBefore >= 0L) {
            long remainingUses = entry.getRemainingUses();
            if (remainingUses >= 0L && remainingUses < remainingUsesBefore) {
                ShopCatalogStatePacket.broadcast(
                        ShopConfig.snapshot().revision(), ShopConfig.keyOf(entry), remainingUses);
            }
        }
    }

    private static void doBuy(ServerPlayer player, ShopEntry entry, ShopActionPacket pkt) {
        long times = pkt.times;
        boolean aeMode = pkt.aeMode;
        boolean backpackMode = pkt.backpackMode;
        int chosenRewardIndex = pkt.chosenRewardIndex;
        boolean cheat = com.dishanhai.gt_shanhai.common.shop.ShopCheatMode.isEnabled(player.getUUID());
        if (entry.hasMissingItems()) {
            // 商品/成本引用的物品当前注册表里查不到（对应模组缺失/被卸载）：客户端按钮已经拦了，这里是兜底，
            // 消息要说清楚原因，别落到下面的"余额不足"分支误导玩家去充值。
            player.sendSystemMessage(Component.literal("§c[山海商店] 该商品引用的物品缺失（对应模组可能未安装），无法购买"));
            sendCartResult(player, pkt, 0L, "商品引用的物品缺失，无法购买");
            return;
        }
        if (!entry.allowsBuy()) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 该商品仅允许出售，不能购买"));
            sendCartResult(player, pkt, 0L, "该商品仅允许出售");
            return;
        }
        // 流体商品没有背包/精妙背包容器可落地，只能注入绑定的在线 AE 网络，购买前必须显式开启顶栏「AE模式」——
        // 不像成本流体那样静默兜底走 AE，这里要求玩家自己确认，避免没注意到就被动往 AE 里塞货物。
        if (entry.hasFluidGoods() && !aeMode) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 该商品含流体，须先开启顶栏「AE模式」才能购买（流体商品只能注入绑定的在线 AE 网络）"));
            sendCartResult(player, pkt, 0L, "含流体商品，需开启 AE 模式");
            return;
        }
        if (!cheat && entry.isLimited() && entry.getRemainingUses() <= 0L) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 该商品限购次数已用完"));
            sendCartResult(player, pkt, 0L, "限购次数已用完");
            return;
        }
        // 交易前的剩余限购次数：若本次成交后限购归零且未买够 times，说明是"次数用尽"而非"余额不足"，
        // 消息要用这个数区分，见下方 limitExhausted 分支。
        long remainingBeforeUses = entry.isLimited() ? entry.getRemainingUses() : -1L;
        // 周期限购（每玩家独立计数，见 ShopPeriodLimiter）：与上面的永久总量是两套独立机制，同样先拦一次再记交易前剩余额度
        String periodKey = WalletAccountAPI.purchaseKey(entry.getGoodsId(), entry.getCategory());
        long remainingPeriodBefore = entry.isPeriodLimited()
                ? com.dishanhai.gt_shanhai.common.shop.ShopPeriodLimiter.remaining(player, entry, periodKey) : -1L;
        if (!cheat && entry.isPeriodLimited() && remainingPeriodBefore <= 0L) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 该商品本周期购买额度已用完，下个周期自动刷新"));
            sendCartResult(player, pkt, 0L, "本周期购买额度已用完");
            return;
        }
        if (!cheat && entry.hasPrerequisiteQuest()) {
            dev.ftb.mods.ftbquests.quest.Quest prereq = resolvePrerequisiteQuest(entry);
            if (prereq == null) {
                player.sendSystemMessage(Component.literal("§c[山海商店] 前置任务未找到（配置错误或 FTBQ 未加载），无法购买"));
                sendCartResult(player, pkt, 0L, "前置任务未找到");
                return;
            }
            dev.ftb.mods.ftbquests.quest.TeamData teamData =
                    dev.ftb.mods.ftbquests.quest.ServerQuestFile.INSTANCE.getOrCreateTeamData(player);
            if (teamData == null || !teamData.isCompleted(prereq)) {
                player.sendSystemMessage(Component.literal("§c[山海商店] 需要先完成前置任务才能购买: §f" + prereq.getRawTitle()));
                sendCartResult(player, pkt, 0L, "需要先完成前置任务: " + prereq.getRawTitle());
                return;
            }
        }
        ShopPurchase.BulkBuyResult r;
        switch (entry.getRewardMode()) {
            case CHOICE -> {
                var pool = entry.getRewardPool();
                if (chosenRewardIndex < 0 || chosenRewardIndex >= pool.size()) {
                    player.sendSystemMessage(Component.literal("§c[山海商店] 选择的奖励无效，请重新选择"));
                    sendCartResult(player, pkt, 0L, "选择的奖励无效");
                    return;
                }
                ShopEntry.RewardOption chosen = pool.get(chosenRewardIndex);
                r = cheat ? ShopPurchase.giveBulkChoice(player, entry, times, aeMode, backpackMode, chosen)
                          : ShopPurchase.buyBulkChoice(player, entry, times, aeMode, backpackMode, chosen);
            }
            case RANDOM -> r = cheat ? ShopPurchase.giveBulkRandom(player, entry, times, aeMode, backpackMode)
                                      : ShopPurchase.buyBulkRandom(player, entry, times, aeMode, backpackMode);
            case ALL -> r = cheat ? ShopPurchase.giveBulkAll(player, entry, times, aeMode, backpackMode)
                                   : ShopPurchase.buyBulkAll(player, entry, times, aeMode, backpackMode);
            case FTBQ -> {
                // FTBQ 表也支持自选/随机/全部三种子模式（见 ShopEntry#getFtbqSubMode），语义对齐本地奖励池，
                // 只是奖励来源换成表内容；自选沿用同一个 chosenRewardIndex 字段，服务端按表内「仅物品类奖励」
                // 子序列的下标重新解出实际物品（见 ShopPurchase#resolveFtbqItemRewardByIndex）。
                r = switch (entry.getFtbqSubMode()) {
                    case CHOICE -> cheat
                            ? ShopPurchase.giveBulkFtbqChoice(player, entry, times, aeMode, backpackMode, chosenRewardIndex)
                            : ShopPurchase.buyBulkFtbqChoice(player, entry, times, aeMode, backpackMode, chosenRewardIndex);
                    case ALL -> cheat
                            ? ShopPurchase.giveBulkFtbqAll(player, entry, times, aeMode, backpackMode)
                            : ShopPurchase.buyBulkFtbqAll(player, entry, times, aeMode, backpackMode);
                    default -> cheat
                            ? ShopPurchase.giveBulkFtbq(player, entry, times, aeMode, backpackMode)
                            : ShopPurchase.buyBulkFtbq(player, entry, times, aeMode, backpackMode);
                };
            }
            default -> r = cheat ? ShopPurchase.giveBulk(player, entry, times, aeMode, backpackMode)   // 作弊：不扣成本直取
                                  : ShopPurchase.buyBulk(player, entry, times, aeMode, backpackMode);
        }
        if (r.done() <= 0L) {
            player.sendSystemMessage(Component.literal(cheat
                    ? "§c[山海商店] 商品无效，无法获取"
                    : "§c[山海商店] 余额不足，先充值"));
            sendCartResult(player, pkt, 0L, cheat ? "商品无效，无法获取" : "余额不足");
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
        // 本次成交把周期限购额度用完了（不是永久总量、也不是余额不够），提示要单独区分，见 remainingPeriodBefore。
        boolean periodExhausted = !cheat && !limitExhausted && entry.isPeriodLimited()
                && com.dishanhai.gt_shanhai.common.shop.ShopPeriodLimiter.remaining(player, entry, periodKey) <= 0L;
        if (cheat) {
            player.sendSystemMessage(Component.literal("§d[山海商店·作弊] §a直接获取 §f" + amt + " §a次 " + entry.goodsDisplayName() + viaText));
            sendCartResult(player, pkt, r.done(), "作弊直接获取 " + amt + " 次");
        } else if (r.done() == times) {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a成功购买 §f" + amt + " §a次 " + entry.goodsDisplayName() + viaText));
            sendCartResult(player, pkt, r.done(), "购买成功");
        } else if (rollCapped) {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a本次抽取 §f" + amt + " §a次 " + entry.goodsDisplayName()
                    + " §7(单次最多抽 " + ShopPurchase.formatCount(ShopPurchase.rewardRollCap()) + " 次，再买一次继续)" + viaText));
            sendCartResult(player, pkt, r.done(), "已购 " + amt + " 次，单次抽取上限，再结算一次继续");
        } else if (limitExhausted) {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a达到购买次数 §f" + amt + " §a次后可购买次数不足§7，商品限制次数 §f"
                    + ShopPurchase.formatCount(remainingBeforeUses) + viaText));
            sendCartResult(player, pkt, r.done(), "已购 " + amt + " 次，限购次数已用完");
        } else if (periodExhausted) {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a购买 §f" + amt + " §a次后触发周期限购§7，本周期（每 "
                    + (entry.getPeriodTicks() / 20L) + " 秒）限 " + ShopPurchase.formatCount(entry.getPeriodLimit())
                    + " 次§7，下个周期自动刷新" + viaText));
            sendCartResult(player, pkt, r.done(), "已购 " + amt + " 次，触发周期限购");
        } else {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a购买 §f" + amt + "§7/§f" + ShopPurchase.formatCount(times) + " §a次后余额不足" + viaText));
            sendCartResult(player, pkt, r.done(), "已购 " + amt + " 次，余额不足，剩余未购部分保留在购物车");
        }
    }

    /** 购物车结算发起的 BUY 才回执（{@code pkt.fromCart}），把成交结果结构化推回客户端购物车 UI；非购物车来源/非 BUY 动作不发。 */
    private static void sendCartResult(ServerPlayer player, ShopActionPacket pkt, long done, String reason) {
        if (!pkt.fromCart || pkt.action != Action.BUY) return;
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShopCartPurchaseResultPacket(pkt.entryKey, pkt.times, done, reason));
    }

    /** 按 {@link ShopEntry#getPrerequisiteQuestId} 查服务端当前 FTBQ 任务数据（未配置/未同步/ID 非法返回 null）。 */
    private static dev.ftb.mods.ftbquests.quest.Quest resolvePrerequisiteQuest(ShopEntry entry) {
        if (!entry.hasPrerequisiteQuest()) return null;
        dev.ftb.mods.ftbquests.quest.ServerQuestFile file = dev.ftb.mods.ftbquests.quest.ServerQuestFile.INSTANCE;
        if (file == null) return null;
        long id = dev.ftb.mods.ftbquests.quest.QuestObjectBase.parseCodeString(entry.getPrerequisiteQuestId());
        return id == 0L ? null : file.getQuest(id);
    }

    private static void doSell(ServerPlayer player, ShopEntry entry, long times, boolean backpackMode) {
        if (entry.getRewardMode() != ShopEntry.RewardMode.NONE) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 自选/随机/全部奖励商品不支持出售"));
            return;
        }
        if (!entry.allowsSell()) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 该商品仅允许购买，不能出售"));
            return;
        }
        if (entry.isLimited() && entry.getRemainingUses() <= 0L) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 该商品限购次数已用完"));
            return;
        }
        if (entry.isPeriodLimited()) {
            String periodKey = WalletAccountAPI.purchaseKey(entry.getGoodsId(), entry.getCategory());
            if (com.dishanhai.gt_shanhai.common.shop.ShopPeriodLimiter.remaining(player, entry, periodKey) <= 0L) {
                player.sendSystemMessage(Component.literal("§c[山海商店] 该商品本周期出售额度已用完，下个周期自动刷新"));
                return;
            }
        }
        long sold = ShopPurchase.sellBulk(player, entry, times, backpackMode);
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

    /** 精确定位条目：版本过期或身份不存在时直接拒绝，不再回退到会碰撞的物品 ID。 */
    private static ShopEntry resolve(ShopActionPacket pkt) {
        return ShopConfig.resolve(pkt.catalogRevision, pkt.entryKey);
    }
}
