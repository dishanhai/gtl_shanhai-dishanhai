package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 商店条目排序包（C→S，编辑权专属）：前移 / 后移（与同分类最近相邻条目交换位置）/ 置顶（挪到同分类最前）/
 * TO_INDEX（拖拽挪到同分类下第 newIndex 位，见 {@link ShopConfig#moveEntryToIndex}）/
 * 撤销（把最近一次排序操作挪回移动前的原始位置，30 秒内有效，见 {@link ShopConfig#undoLastMove}——
 * 置顶没有对称的"反向"操作，必须靠这个记住原始下标才能挪回去，不能靠再点一次别的按钮凑出来）。
 * 排序结果 = shop.json 数组物理顺序，全服玩家看到同一顺序，不是玩家个人视图（见 {@link ShopConfig#moveEntry}）。
 */
public class ShopReorderPacket {

    public enum Action { UP, DOWN, TOP, TO_INDEX, UNDO }

    private final Action action;
    private final long catalogRevision;
    private final long entryKey;
    private final int newIndex; // 仅 TO_INDEX 有意义，其余 action 恒为 -1

    public ShopReorderPacket(Action action, long catalogRevision, long entryKey) {
        this(action, catalogRevision, entryKey, -1);
    }

    public ShopReorderPacket(Action action, long catalogRevision, long entryKey, int newIndex) {
        this.action = action;
        this.catalogRevision = catalogRevision;
        this.entryKey = entryKey;
        this.newIndex = newIndex;
    }

    public ShopReorderPacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
        this.catalogRevision = buf.readLong();
        this.entryKey = buf.readLong();
        this.newIndex = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeLong(catalogRevision);
        buf.writeLong(entryKey);
        buf.writeVarInt(newIndex);
    }

    public static void handle(ShopReorderPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopReorderPacket pkt, ServerPlayer player) {
        if (!ShopEditPermission.canEdit(player)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 无编辑权限"));
            return;
        }
        // 撤销不认 entryKey/catalogRevision（服务端记的是"最近移动的那个条目"，跟客户端目录版本是否过期无关，
        // 同 ShopActionPacket#UNDO_DELETE 的处理思路），必须在下面 resolve 校验之前单独分支掉。
        if (pkt.action == Action.UNDO) {
            boolean undone = ShopConfig.undoLastMove();
            player.sendSystemMessage(undone
                    ? Component.literal("§b[山海商店] §a已撤销排序")
                    : Component.literal("§c[山海商店] 没有可撤销的排序（已超时或已撤销过）"));
            return;
        }
        ShopEntry entry = ShopConfig.resolve(pkt.catalogRevision, pkt.entryKey);
        if (entry == null) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 商品目录已更新或商品不存在，请重新打开"));
            return;
        }
        boolean ok = switch (pkt.action) {
            case UP -> ShopConfig.moveEntry(entry, -1);
            case DOWN -> ShopConfig.moveEntry(entry, 1);
            case TOP -> ShopConfig.moveEntryToTop(entry);
            case TO_INDEX -> ShopConfig.moveEntryToIndex(entry, pkt.newIndex);
            case UNDO -> false; // 不可达：UNDO 已在上面提前返回
        };
        if (!ok) {
            player.sendSystemMessage(Component.literal("§c[山海商店] "
                    + (pkt.action == Action.TO_INDEX ? "落点无变化" : "已经在同分类" + (pkt.action == Action.TOP ? "最前" : "边界"))
                    + "，无法再移动"));
        }
    }
}
