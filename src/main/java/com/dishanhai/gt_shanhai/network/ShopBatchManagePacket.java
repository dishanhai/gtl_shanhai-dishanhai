package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogSnapshot;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * 商店批量管理包（C→S）：同一目录 revision 下按 stableId 一次处理隐藏、排序、分组、交易方向与删除。
 *
 * <p>收藏仍走客户端现有个人收藏包；查看详情、跳转、任务与指南也不进入本包。</p>
 */
public final class ShopBatchManagePacket {

    public enum Action { HIDE, UNHIDE, UP, DOWN, TOP, REGROUP, SET_TRADE_MODE, DELETE }

    private static final int MAX_IDS = 256;
    private final Action action;
    private final long catalogRevision;
    private final List<String> stableIds;
    private final String category;
    private final ShopEntry.TradeMode tradeMode;

    public ShopBatchManagePacket(Action action, long catalogRevision,
                                 Collection<String> stableIds, String category) {
        this(action, catalogRevision, stableIds, category, ShopEntry.TradeMode.BOTH);
    }

    public ShopBatchManagePacket(Action action, long catalogRevision,
                                 Collection<String> stableIds, String category,
                                 ShopEntry.TradeMode tradeMode) {
        this.action = action == null ? Action.HIDE : action;
        this.catalogRevision = catalogRevision;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (stableIds != null) {
            for (String stableId : stableIds) {
                if (stableId != null && !stableId.isBlank() && ids.size() < MAX_IDS) ids.add(stableId);
            }
        }
        this.stableIds = List.copyOf(ids);
        this.category = category == null ? "" : category;
        this.tradeMode = tradeMode == null ? ShopEntry.TradeMode.BOTH : tradeMode;
    }

    public ShopBatchManagePacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
        this.catalogRevision = buf.readLong();
        int size = Math.min(MAX_IDS, buf.readVarInt());
        List<String> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) ids.add(buf.readUtf(128));
        this.stableIds = List.copyOf(ids);
        this.category = buf.readUtf(256);
        this.tradeMode = buf.readEnum(ShopEntry.TradeMode.class);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeLong(catalogRevision);
        buf.writeVarInt(stableIds.size());
        for (String stableId : stableIds) buf.writeUtf(stableId, 128);
        buf.writeUtf(category, 256);
        buf.writeEnum(tradeMode);
    }

    public static void handle(ShopBatchManagePacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopBatchManagePacket pkt, ServerPlayer player) {
        if (!ShopEditPermission.canEdit(player)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 无编辑权限"));
            return;
        }
        ShopCatalogSnapshot current = ShopConfig.snapshot();
        if (current.revision() != pkt.catalogRevision) {
            ShopCatalogManifestPacket.sendLatestIfAllowed(player, current.manifest(), pkt.catalogRevision);
            player.sendSystemMessage(Component.literal("§c[山海商店] 商品目录已更新，请重试"));
            return;
        }
        if (pkt.stableIds.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 批量名单为空"));
            return;
        }
        int changed;
        String verb;
        switch (pkt.action) {
            case HIDE -> { changed = ShopConfig.batchSetHidden(pkt.stableIds, true); verb = "设为隐藏"; }
            case UNHIDE -> { changed = ShopConfig.batchSetHidden(pkt.stableIds, false); verb = "取消隐藏"; }
            case UP -> { changed = ShopConfig.batchMove(pkt.stableIds, -1); verb = "批量前移"; }
            case DOWN -> { changed = ShopConfig.batchMove(pkt.stableIds, 1); verb = "批量后移"; }
            case TOP -> { changed = ShopConfig.batchMove(pkt.stableIds, 0); verb = "批量置顶"; }
            case REGROUP -> {
                if (!com.dishanhai.gt_shanhai.common.shop.ShopEditMode.isEnabled(player.getUUID())) {
                    player.sendSystemMessage(Component.literal("§c[山海商店] 请先开启编辑模式后再批量分组"));
                    return;
                }
                changed = ShopConfig.batchRegroup(pkt.stableIds, pkt.category);
                verb = "批量分组";
            }
            case SET_TRADE_MODE -> {
                if (!com.dishanhai.gt_shanhai.common.shop.ShopEditMode.isEnabled(player.getUUID())) {
                    player.sendSystemMessage(Component.literal("§c[山海商店] 请先开启编辑模式后再批量修改交易方向"));
                    return;
                }
                changed = ShopConfig.batchSetTradeMode(pkt.stableIds, pkt.tradeMode);
                verb = "批量修改交易方向";
            }
            case DELETE -> {
                if (!com.dishanhai.gt_shanhai.common.shop.ShopEditMode.isEnabled(player.getUUID())) {
                    player.sendSystemMessage(Component.literal("§c[山海商店] 请先开启编辑模式后再批量删除"));
                    return;
                }
                changed = ShopConfig.removeEntriesByStableIds(pkt.stableIds);
                verb = "批量删除";
            }
            default -> { changed = 0; verb = "批量操作"; }
        }
        player.sendSystemMessage(changed > 0
                ? Component.literal("§b[山海商店] §a" + verb + " §f" + changed + " §a项")
                : Component.literal("§e[山海商店] " + verb + "没有可变更的项目"));
    }
}
