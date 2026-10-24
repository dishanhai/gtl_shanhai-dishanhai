package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopBranch;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogSnapshot;
import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEditMode;
import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;

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
 * 商店分支复制包（C→S，山海署名）：把一个分类节点的整棵子树连同商品复制到若干阶段之下，副本内容跟随主支同步，
 * 重复执行 = 重新同步。需要编辑权限并已开启编辑模式；目录版本过期时拒绝，避免对着旧目录复制。
 */
public final class ShopBranchCopyPacket {

    private static final int MAX_TARGETS = 64;
    private static final int MAX_PATH = 256;
    private static final int MAX_IDS = 256;

    private final long catalogRevision;
    private final String sourcePath;
    private final List<String> targets;
    /** 非空 = 商品模式：把这些商品各复制到 targets 里的每个分组（此时 sourcePath 不用）。 */
    private final List<String> stableIds;

    public ShopBranchCopyPacket(long catalogRevision, String sourcePath, Collection<String> targets) {
        this(catalogRevision, sourcePath, targets, List.of());
    }

    /** 商品模式：单个或批量商品复制到不同分组。 */
    public static ShopBranchCopyPacket forEntries(long catalogRevision, Collection<String> stableIds,
                                                  Collection<String> targetCategories) {
        return new ShopBranchCopyPacket(catalogRevision, "", targetCategories, stableIds);
    }

    private ShopBranchCopyPacket(long catalogRevision, String sourcePath, Collection<String> targets,
                                 Collection<String> stableIds) {
        this.catalogRevision = catalogRevision;
        this.sourcePath = sourcePath == null ? "" : sourcePath;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (stableIds != null) {
            for (String id : stableIds) {
                if (id != null && !id.isBlank() && ids.size() < MAX_IDS) ids.add(id);
            }
        }
        this.stableIds = List.copyOf(ids);
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (targets != null) {
            for (String t : targets) {
                if (t != null && !t.isBlank() && set.size() < MAX_TARGETS) set.add(t);
            }
        }
        this.targets = List.copyOf(set);
    }

    public ShopBranchCopyPacket(FriendlyByteBuf buf) {
        this.catalogRevision = buf.readLong();
        this.sourcePath = buf.readUtf(MAX_PATH);
        int n = Math.min(MAX_TARGETS, buf.readVarInt());
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readUtf(MAX_PATH));
        this.targets = List.copyOf(list);
        int m = Math.min(MAX_IDS, buf.readVarInt());
        List<String> ids = new ArrayList<>(m);
        for (int i = 0; i < m; i++) ids.add(buf.readUtf(128));
        this.stableIds = List.copyOf(ids);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(catalogRevision);
        buf.writeUtf(sourcePath, MAX_PATH);
        buf.writeVarInt(targets.size());
        for (String t : targets) buf.writeUtf(t, MAX_PATH);
        buf.writeVarInt(stableIds.size());
        for (String id : stableIds) buf.writeUtf(id, 128);
    }

    public static void handle(ShopBranchCopyPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopBranchCopyPacket pkt, ServerPlayer player) {
        if (!ShopEditPermission.canEdit(player)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 无编辑权限"));
            return;
        }
        if (!ShopEditMode.isEnabled(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 请先执行 /山海 商店 编辑 开启编辑模式"));
            return;
        }
        ShopCatalogSnapshot current = ShopConfig.snapshot();
        if (current.revision() != pkt.catalogRevision) {
            ShopCatalogManifestPacket.sendLatestIfAllowed(player, current.manifest(), pkt.catalogRevision);
            player.sendSystemMessage(Component.literal("§c[山海商店] 商品目录已更新，请重试"));
            return;
        }
        boolean entryMode = !pkt.stableIds.isEmpty();
        if ((!entryMode && pkt.sourcePath.isBlank()) || pkt.targets.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 请选择要复制到的"
                    + (entryMode ? "分组" : "阶段")));
            return;
        }
        ShopBranch.CopyResult result = entryMode
                ? ShopConfig.copyEntriesToGroups(pkt.stableIds, pkt.targets)
                : ShopConfig.copyBranch(pkt.sourcePath, pkt.targets);
        if (result.created() > 0) {
            // 新副本的存档余量按服务端次数起算，立刻登记，免得下次读档才补
            ShopConfig.syncLimitsFromSave(player.getServer());
        }
        player.sendSystemMessage(result.changed()
                ? Component.literal("§b[山海商店] §a" + (entryMode ? "§f" + pkt.stableIds.size() + " §a件商品" : "分支 §f" + pkt.sourcePath)
                + " §a已复制到 §f" + pkt.targets.size() + " §a个" + (entryMode ? "分组" : "阶段")
                + "：新增 §f" + result.created() + " §a项，重新同步 §f" + result.synced() + " §a项"
                + (result.skipped() > 0 ? " §e（跳过 " + result.skipped() + " 项：已在目标分组或超过 " + ShopBranch.MAX_DEPTH + " 层）" : ""))
                : Component.literal("§e[山海商店] 没有可复制的商品"
                + (result.skipped() > 0 ? "（跳过 " + result.skipped() + " 项：已在目标分组或超过 " + ShopBranch.MAX_DEPTH + " 层）" : "")));
    }
}
