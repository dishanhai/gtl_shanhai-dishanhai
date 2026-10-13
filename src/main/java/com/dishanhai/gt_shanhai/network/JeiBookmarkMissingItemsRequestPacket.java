package com.dishanhai.gt_shanhai.network;

import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.crafting.IPatternDetails;
import appeng.menu.me.common.MEStorageMenu;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig.ConfigValues.JeiBookmarkMode;
import com.dishanhai.gt_shanhai.jei.JeiBookmarkAmountParser;
import com.dishanhai.gt_shanhai.jei.JeiBookmarkExclusions;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public static final int MAX_TAGS = 32;
    static final int MAX_EXCLUSIONS = 64;
    static final int MAX_EXCLUSION_LENGTH = 1024;

    private final int menuId;
    private final String recipeId;
    private final JeiBookmarkMode mode;
    private final List<TaggedInput> inputs;
    private final List<String> exclusions;

    /** 配方输入。tags 是该槽位等价的物品/流体标签，空列表表示只认展示物本身。 */
    public record TaggedInput(GenericStack stack, List<ResourceLocation> tags) {
        public TaggedInput {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    public JeiBookmarkMissingItemsRequestPacket(int menuId, String recipeId, List<GenericStack> inputs) {
        this(menuId, recipeId, JeiBookmarkMode.MISSING_ITEMS, tagless(inputs), List.of());
    }

    public JeiBookmarkMissingItemsRequestPacket(int menuId, String recipeId,
            JeiBookmarkMode mode, List<TaggedInput> inputs) {
        this(menuId, recipeId, mode, inputs, List.of());
    }

    public JeiBookmarkMissingItemsRequestPacket(int menuId, String recipeId,
            JeiBookmarkMode mode, List<TaggedInput> inputs, List<? extends String> exclusions) {
        this.menuId = menuId;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.mode = mode == null ? JeiBookmarkMode.MISSING_ITEMS : mode;
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
        this.exclusions = copyExclusions(exclusions);
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
        List<TaggedInput> decoded = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            GenericStack stack = GenericStack.readBuffer(buffer);
            List<ResourceLocation> tags = readTags(buffer);
            if (stack != null && stack.what() != null && stack.amount() > 0) {
                decoded.add(new TaggedInput(stack, tags));
            }
        }
        this.inputs = List.copyOf(decoded);
        this.exclusions = readExclusions(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(menuId);
        buffer.writeUtf(recipeId, MAX_RECIPE_ID_LENGTH);
        buffer.writeEnum(mode);
        buffer.writeVarInt(Math.min(MAX_ENTRIES, inputs.size()));
        for (int i = 0; i < inputs.size() && i < MAX_ENTRIES; i++) {
            TaggedInput input = inputs.get(i);
            GenericStack.writeBuffer(input.stack(), buffer);
            List<ResourceLocation> tags = input.tags();
            int tagCount = Math.min(MAX_TAGS, tags.size());
            buffer.writeVarInt(tagCount);
            for (int t = 0; t < tagCount; t++) {
                buffer.writeUtf(tags.get(t).toString(), 256);
            }
        }
        buffer.writeVarInt(exclusions.size());
        for (String exclusion : exclusions) {
            buffer.writeUtf(exclusion, MAX_EXCLUSION_LENGTH);
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

        List<JeiBookmarkExclusions.Rule> exclusionRules = JeiBookmarkExclusions.parse(packet.exclusions);
        KeyCounter required = new KeyCounter();
        Map<AEKey, List<ResourceLocation>> tagsByKey = new LinkedHashMap<>();
        for (TaggedInput input : packet.inputs) {
            GenericStack stack = input == null ? null : input.stack();
            if (stack == null || stack.what() == null || stack.amount() <= 0) continue;
            AEKey key = stack.what();
            if (isExcluded(key, exclusionRules)) {
                GTDishanhaiMod.LOGGER.info(
                        "[JEIBookmarkDiag] recipe={} mode={} key={} required={} available={} enough={} craftable={} selected={} reason=excluded",
                        packet.recipeId, packet.mode, key, stack.amount(), 0L, false, false, false);
                continue;
            }
            required.add(key, stack.amount());
            if (input.tags().isEmpty()) continue;
            List<ResourceLocation> tags = tagsByKey.computeIfAbsent(key, ignored -> new ArrayList<>());
            for (ResourceLocation id : input.tags()) {
                if (id != null && !tags.contains(id) && tags.size() < MAX_TAGS) tags.add(id);
            }
        }

        var node = menu.getNetworkNode();
        var grid = node == null ? null : node.getGrid();
        var storage = grid == null ? null : grid.getStorageService().getInventory();
        KeyCounter available = storage == null ? null : storage.getAvailableStacks();
        List<GenericStack> missing = new ArrayList<>();
        for (var entry : required) {
            AEKey key = entry.getKey();
            long needed = entry.getLongValue();
            long have = available == null ? 0L : Math.max(0L, available.get(key));
            boolean craftable = hasPrimaryPattern(grid, key);
            boolean enough = JeiBookmarkAmountParser.isEffectivelyEnough(have, needed);
            boolean selected = needsBookmark(packet.mode, have, needed, craftable);
            String reason = "exact";
            if (selected && coveredByAeTag(packet.mode, key, needed,
                    tagsByKey.getOrDefault(key, List.of()), available, grid, exclusionRules)) {
                selected = false;
                reason = "tag";
            }
            GTDishanhaiMod.LOGGER.info(
                    "[JEIBookmarkDiag] recipe={} mode={} key={} required={} available={} enough={} craftable={} selected={} reason={}",
                    packet.recipeId, packet.mode, key, needed, have, enough, craftable, selected, reason);
            if (selected) {
                missing.add(new GenericStack(key, needed));
            }
        }

        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new JeiBookmarkMissingItemsResponsePacket(packet.recipeId, missing));
    }

    private static List<TaggedInput> tagless(List<GenericStack> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        List<TaggedInput> tagged = new ArrayList<>(inputs.size());
        for (GenericStack stack : inputs) {
            if (stack != null && stack.what() != null && stack.amount() > 0) {
                tagged.add(new TaggedInput(stack, List.of()));
            }
        }
        return List.copyOf(tagged);
    }

    private static List<ResourceLocation> readTags(FriendlyByteBuf buffer) {
        int tagCount = buffer.readVarInt();
        if (tagCount < 0 || tagCount > MAX_TAGS) {
            throw new io.netty.handler.codec.DecoderException(
                    "JeiBookmarkMissingItemsRequestPacket tag count out of range: " + tagCount);
        }
        if (tagCount == 0) return List.of();
        List<ResourceLocation> tags = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) {
            ResourceLocation id = ResourceLocation.tryParse(buffer.readUtf(256));
            if (id != null) tags.add(id);
        }
        return tags;
    }

    private static List<String> copyExclusions(List<? extends String> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) return List.of();
        List<String> copied = new ArrayList<>();
        for (String line : exclusions) {
            if (line == null || line.isBlank()) continue;
            copied.add(line.length() > MAX_EXCLUSION_LENGTH ? line.substring(0, MAX_EXCLUSION_LENGTH) : line);
            if (copied.size() >= MAX_EXCLUSIONS) break;
        }
        return List.copyOf(copied);
    }

    private static List<String> readExclusions(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_EXCLUSIONS) {
            throw new io.netty.handler.codec.DecoderException(
                    "JeiBookmarkMissingItemsRequestPacket exclusion count out of range: " + count);
        }
        if (count == 0) return List.of();
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(buffer.readUtf(MAX_EXCLUSION_LENGTH));
        }
        return List.copyOf(lines);
    }

    private static boolean isExcluded(AEKey key, List<JeiBookmarkExclusions.Rule> rules) {
        if (!(key instanceof AEItemKey itemKey)) return false;
        return JeiBookmarkExclusions.matches(itemKey.getId(), itemKey.getTag(), rules);
    }

    private static boolean needsBookmark(JeiBookmarkMode mode, long have, long needed, boolean craftable) {
        boolean enough = JeiBookmarkAmountParser.isEffectivelyEnough(have, needed);
        return switch (mode) {
            case MISSING_ITEMS -> have < needed;
            case NO_RECIPE_ITEMS -> !craftable;
            case COMBINED -> !craftable && !enough;
        };
    }

    /**
     * 只遍历 AE 库存返回值。某个返回物品命中槽位标签，且按当前模式已足够或已有样板，就不再收藏展示物。
     * 不展开标签成员表去逐个查询。
     */
    private static boolean coveredByAeTag(JeiBookmarkMode mode, AEKey displayed, long needed,
            List<ResourceLocation> tags, KeyCounter available, appeng.api.networking.IGrid grid,
            List<JeiBookmarkExclusions.Rule> exclusionRules) {
        if (tags == null || tags.isEmpty() || available == null || displayed == null) return false;
        boolean item = displayed instanceof AEItemKey;
        if (!item && !(displayed instanceof appeng.api.stacks.AEFluidKey)) return false;
        List<TagKey<?>> parsed = new ArrayList<>(tags.size());
        for (ResourceLocation id : tags) {
            if (id == null) continue;
            parsed.add(item ? ItemTags.create(id) : FluidTags.create(id));
        }
        if (parsed.isEmpty()) return false;
        for (var entry : available) {
            AEKey other = entry.getKey();
            if (other == null || other.equals(displayed) || isExcluded(other, exclusionRules)) continue;
            if (!matchesAnyTag(other, parsed)) continue;
            long have = Math.max(0L, entry.getLongValue());
            if (!substituteNeedsBookmark(mode, other, have, needed, grid)) return true;
        }
        return false;
    }

    private static boolean matchesAnyTag(AEKey key, List<TagKey<?>> tags) {
        for (TagKey<?> tag : tags) {
            if (key.isTagged(tag)) return true;
        }
        return false;
    }

    /** 替代物自己还不满足收藏条件时返回 true，调用方继续看下一个 AE 返回。 */
    private static boolean substituteNeedsBookmark(JeiBookmarkMode mode, AEKey key, long have, long needed,
            appeng.api.networking.IGrid grid) {
        if (mode == JeiBookmarkMode.MISSING_ITEMS) return have < needed;
        if (mode == JeiBookmarkMode.COMBINED
                && JeiBookmarkAmountParser.isEffectivelyEnough(have, needed)) {
            return false;
        }
        return !hasPrimaryPattern(grid, key);
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
