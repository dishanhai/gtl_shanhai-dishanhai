package com.dishanhai.gt_shanhai.network;

import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.stacks.AEKey;
import appeng.api.crafting.IPatternDetails;
import appeng.menu.me.common.MEStorageMenu;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig.ConfigValues.JeiBookmarkMode;
import com.dishanhai.gt_shanhai.jei.JeiBookmarkAmountParser;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * JEI 收藏缺失项请求（客户端 -> 服务端）。
 *
 * 客户端只提交当前配方输入的 AE key 与单次需求量，库存判定必须在服务端当前样板终端所连接的
 * MEStorage 上进行，不能用客户端玩家背包替代。
 */
public final class JeiBookmarkMissingItemsRequestPacket {

    static final int MAX_RECIPE_ID_LENGTH = 512;
    static final int MAX_ENTRIES = 256;

    private final int menuId;
    private final String recipeId;
    private final JeiBookmarkMode mode;
    private final List<GenericStack> inputs;

    public JeiBookmarkMissingItemsRequestPacket(int menuId, String recipeId, List<GenericStack> inputs) {
        this(menuId, recipeId, JeiBookmarkMode.MISSING_ITEMS, inputs);
    }

    public JeiBookmarkMissingItemsRequestPacket(int menuId, String recipeId,
            JeiBookmarkMode mode, List<GenericStack> inputs) {
        this.menuId = menuId;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.mode = mode == null ? JeiBookmarkMode.MISSING_ITEMS : mode;
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }

    public JeiBookmarkMissingItemsRequestPacket(FriendlyByteBuf buffer) {
        this.menuId = buffer.readVarInt();
        this.recipeId = buffer.readUtf(MAX_RECIPE_ID_LENGTH);
        this.mode = buffer.readEnum(JeiBookmarkMode.class);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new io.netty.handler.codec.DecoderException(
                    "JeiBookmarkMissingItemsRequestPacket entry count out of range: " + count);
        }
        List<GenericStack> decoded = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            GenericStack stack = GenericStack.readBuffer(buffer);
            if (stack != null && stack.what() != null && stack.amount() > 0) {
                decoded.add(stack);
            }
        }
        this.inputs = List.copyOf(decoded);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(menuId);
        buffer.writeUtf(recipeId, MAX_RECIPE_ID_LENGTH);
        buffer.writeEnum(mode);
        buffer.writeVarInt(Math.min(MAX_ENTRIES, inputs.size()));
        for (int i = 0; i < inputs.size() && i < MAX_ENTRIES; i++) {
            GenericStack.writeBuffer(inputs.get(i), buffer);
        }
    }

    public static void handle(JeiBookmarkMissingItemsRequestPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> apply(packet, player));
        context.setPacketHandled(true);
    }

    private static void apply(JeiBookmarkMissingItemsRequestPacket packet, ServerPlayer player) {
        if (player == null || !(player.containerMenu instanceof MEStorageMenu menu)) {
            GTDishanhaiMod.LOGGER.warn("[JEIBookmarkDiag] request rejected: missing AE terminal player={} recipe={} mode={}",
                    player == null ? "null" : player.getGameProfile().getName(), packet.recipeId, packet.mode);
            return;
        }
        if (menu.containerId != packet.menuId || !menu.stillValid(player)) {
            GTDishanhaiMod.LOGGER.warn(
                    "[JEIBookmarkDiag] request rejected: stale menu player={} packetMenu={} currentMenu={} recipe={} mode={}",
                    player.getGameProfile().getName(), packet.menuId, menu.containerId, packet.recipeId, packet.mode);
            return;
        }

        KeyCounter required = new KeyCounter();
        for (GenericStack input : packet.inputs) {
            if (input != null && input.what() != null && input.amount() > 0) {
                required.add(input.what(), input.amount());
            }
        }

        var node = menu.getNetworkNode();
        var grid = node == null ? null : node.getGrid();
        var storage = grid == null ? null : grid.getStorageService().getInventory();
        KeyCounter available = storage == null ? null : storage.getAvailableStacks();
        List<GenericStack> missing = new ArrayList<>();
        for (var entry : required) {
            long have = available == null ? 0L : Math.max(0L, available.get(entry.getKey()));
            long needed = entry.getLongValue();
            boolean craftable = hasPrimaryPattern(grid, entry.getKey());
            boolean enough = JeiBookmarkAmountParser.isEffectivelyEnough(have, needed);
            boolean selected = switch (packet.mode) {
                case MISSING_ITEMS -> have < needed;
                case NO_RECIPE_ITEMS -> !craftable;
                case COMBINED -> !craftable && !enough;
            };
            GTDishanhaiMod.LOGGER.info(
                    "[JEIBookmarkDiag] recipe={} mode={} key={} required={} available={} enough={} craftable={} selected={}",
                    packet.recipeId, packet.mode, entry.getKey(), needed, have, enough, craftable, selected);
            if (selected) {
                missing.add(new GenericStack(entry.getKey(), needed));
            }
        }

        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new JeiBookmarkMissingItemsResponsePacket(packet.recipeId, missing));
    }

    private static boolean hasPrimaryPattern(appeng.api.networking.IGrid grid, AEKey target) {
        if (grid == null || target == null || grid.getCraftingService() == null) return false;
        for (IPatternDetails pattern : grid.getCraftingService().getCraftingFor(target)) {
            if (pattern == null) continue;
            GenericStack primary;
            try {
                primary = pattern.getPrimaryOutput();
            } catch (Throwable ignored) {
                continue;
            }
            if (primary != null && target.equals(primary.what())) return true;
        }
        return false;
    }
}
