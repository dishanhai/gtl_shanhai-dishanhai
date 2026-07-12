package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ExchangeConfig;
import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 兑换条目编辑包（C→S，山海署名）：新增 / 编辑 / 删除。服务端 {@link ShopEditPermission#canEdit} 强校验，
 * 落地到 {@link ExchangeConfig}（写 exchanges.json）。EDIT/DELETE 按 {@code targetId} 定位旧条目。
 */
public class ExchangeEditPacket {

    public enum Action { ADD, EDIT, DELETE }

    private final Action action;
    private final String targetId;         // EDIT/DELETE 定位旧条目（ADD 忽略）
    private final boolean hasNew;
    private final ExchangeEntry newEntry;  // ADD/EDIT 的新数据

    /** 便捷构造：target 提供旧 id（可空），newEntry 为新数据（DELETE 可空）。 */
    public ExchangeEditPacket(Action action, ExchangeEntry target, ExchangeEntry newEntry) {
        this.action = action;
        this.targetId = target == null ? "" : target.getId();
        this.hasNew = newEntry != null;
        this.newEntry = newEntry;
    }

    public ExchangeEditPacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
        this.targetId = buf.readUtf();
        this.hasNew = buf.readBoolean();
        this.newEntry = hasNew ? readEntry(buf) : null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(targetId);
        buf.writeBoolean(hasNew);
        if (hasNew) writeEntry(buf, newEntry);
    }

    // ===== ExchangeEntry 编解码 =====

    private static void writeEntry(FriendlyByteBuf buf, ExchangeEntry e) {
        buf.writeUtf(e.getId());
        buf.writeUtf(e.getCategory());
        buf.writeUtf(e.getName());
        writeSide(buf, e.getCost());
        writeSide(buf, e.getResult());
    }

    private static ExchangeEntry readEntry(FriendlyByteBuf buf) {
        String id = buf.readUtf();
        String category = buf.readUtf();
        String name = buf.readUtf();
        ExchangeEntry.Side cost = readSide(buf);
        ExchangeEntry.Side result = readSide(buf);
        return new ExchangeEntry(id, category, name, cost, result);
    }

    private static void writeSide(FriendlyByteBuf buf, ExchangeEntry.Side side) {
        buf.writeByteArray(side.spark.toByteArray());
        buf.writeVarInt(side.ingredients.size());
        for (ExchangeEntry.Ingredient in : side.ingredients) {
            buf.writeResourceLocation(in.id == null ? new ResourceLocation("minecraft:air") : in.id);
            buf.writeBoolean(in.isFluid);
            buf.writeVarLong(in.count);
        }
    }

    private static ExchangeEntry.Side readSide(FriendlyByteBuf buf) {
        byte[] sparkBytes = buf.readByteArray();
        BigInteger spark = sparkBytes.length == 0 ? BigInteger.ZERO : new BigInteger(sparkBytes);
        int n = buf.readVarInt();
        List<ExchangeEntry.Ingredient> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ResourceLocation id = buf.readResourceLocation();
            boolean fluid = buf.readBoolean();
            long count = buf.readVarLong();
            items.add(new ExchangeEntry.Ingredient(id, fluid, count));
        }
        return new ExchangeEntry.Side(spark, items);
    }

    // ===== 处理 =====

    public static void handle(ExchangeEditPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ExchangeEditPacket pkt, ServerPlayer player) {
        if (!ShopEditPermission.canEdit(player)) {
            player.sendSystemMessage(Component.literal("§c[兑换] 无编辑权限"));
            return;
        }
        switch (pkt.action) {
            case ADD -> {
                if (pkt.newEntry == null || !pkt.newEntry.isValid()) {
                    player.sendSystemMessage(Component.literal("§c[兑换] 条目无效（两侧不能为空）"));
                    return;
                }
                ExchangeConfig.addEntry(pkt.newEntry);
                player.sendSystemMessage(Component.literal("§b[兑换] §a已新增 §f" + pkt.newEntry.displayName()));
            }
            case EDIT -> {
                ExchangeEntry old = ExchangeConfig.byId(pkt.targetId);
                if (old == null) { player.sendSystemMessage(Component.literal("§c[兑换] 旧条目不存在")); return; }
                if (pkt.newEntry == null || !pkt.newEntry.isValid()) {
                    player.sendSystemMessage(Component.literal("§c[兑换] 新条目无效"));
                    return;
                }
                ExchangeConfig.replaceEntry(old, pkt.newEntry);
                player.sendSystemMessage(Component.literal("§b[兑换] §a已保存 §f" + pkt.newEntry.displayName()));
            }
            case DELETE -> {
                ExchangeEntry old = ExchangeConfig.byId(pkt.targetId);
                if (old == null) { player.sendSystemMessage(Component.literal("§c[兑换] 条目不存在")); return; }
                boolean ok = ExchangeConfig.removeEntry(old);
                player.sendSystemMessage(ok
                        ? Component.literal("§b[兑换] §a已删除 §f" + old.displayName())
                        : Component.literal("§c[兑换] 删除失败"));
            }
        }
        ShopRefreshPacket.sendTo(player); // 增/删/改后回推刷新界面（实时）
    }
}
