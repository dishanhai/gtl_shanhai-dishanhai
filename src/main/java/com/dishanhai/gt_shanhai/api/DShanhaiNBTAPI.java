package com.dishanhai.gt_shanhai.api;

import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import dev.latvian.mods.kubejs.item.ItemStackJS;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 山海 NBT 标签统一管理 API。
 * 支持同物品 ID 多个 NBT 变体（通过 UID 区分）。
 *
 * 用法（KubeJS startup_scripts）:
 * <pre>
 * // 注册 NBT
 * DShanhaiNBTAPI.put("ae2wtlib:wireless_pattern_encoding_terminal", "uid1",
 *     "{\"tag\":{\"config\":\"A\",\"mode\":\"fast\"}}");
 * DShanhaiNBTAPI.put("ae2wtlib:wireless_pattern_encoding_terminal", "uid2",
 *     "{\"tag\":{\"config\":\"B\",\"mode\":\"safe\"}}");
 *
 * // 获取 NBT
 * var tag = DShanhaiNBTAPI.getTag("ae2wtlib:wireless_pattern_encoding_terminal", "uid1");
 * // → ,"tag":{"config":"A","mode":"fast"}
 * </pre>
 */
public class DShanhaiNBTAPI {

    // itemId → { uid → tagJson }
    private static final Map<String, Map<String, String>> TAG_MAP = new Object2ObjectOpenHashMap<>();

    // itemId → tagJson（无 uid 的简化模式）
    private static final Map<String, String> SIMPLE_MAP = new Object2ObjectOpenHashMap<>();

    /** 注册物品 NBT 标签（无 UID，简化模式） */
    public static void put(String itemId, String tagJson) {
        if (itemId != null && tagJson != null) {
            SIMPLE_MAP.put(itemId, tagJson);
        }
    }

    /** 注册 KubeJS 物品栈 NBT，支持 DShanhaiNBTAPI.putStack(Item.of(...)) 或字符串物品表达式 */
    public static void putStack(Object stackLike) {
        if (stackLike == null) return;
        if (stackLike instanceof String) return;
        registerStackTag(null, ItemStackJS.of(stackLike));
    }

    /** 注册物品 NBT 标签（带 UID 区分同 ID 不同 NBT） */
    public static void put(String itemId, String uid, String tagJson) {
        if (itemId == null || uid == null || tagJson == null) return;
        TAG_MAP.computeIfAbsent(itemId, k -> new Object2ObjectOpenHashMap<>())
               .put(uid, tagJson);
    }

    /** 注册 KubeJS 物品栈 NBT，并指定 UID 区分同 ID 多变体 */
    public static void putStack(Object stackLike, String uid) {
        if (uid == null || stackLike == null) return;
        if (stackLike instanceof String) return;
        registerStackTag(uid, ItemStackJS.of(stackLike));
    }

    /** 批量注册（无 UUID） */
    public static void putAll(String[] ids, String[] tags) {
        if (ids == null || tags == null) return;
        int len = Math.min(ids.length, tags.length);
        for (int i = 0; i < len; i++) {
            if (ids[i] != null && tags[i] != null) {
                SIMPLE_MAP.put(ids[i], tags[i]);
            }
        }
    }

    /** 获取物品 NBT 标签（优先按 uid 查找，无 uid 时回退到简化模式） */
    public static String getTag(String itemId, String uid) {
        if (itemId == null) return "";

        // 无限单元格：动态生成
        if (itemId.equals("expatternprovider:infinity_cell") && uid != null) {
            return infinityCell(uid);
        }

        // 按 uid 查找
        if (uid != null) {
            Map<String, String> uidMap = TAG_MAP.get(itemId);
            if (uidMap != null) {
                String tag = uidMap.get(uid);
                if (tag != null) return tag;
            }
        }

        // 回退到简化模式
        String tag = SIMPLE_MAP.get(itemId);
        if (tag != null) return tag.replace(", tag: ", ",tag:");

        // 别名查找
        if (itemId.equals("gtladditions:forge_of_the_antichrist")) return getSimple("weishen");
        if (itemId.equals("gtladditions:arcanic_astrograph")) return getSimple("hmoe");
        if (itemId.equals("gtladditions:thread_modifier_hatch")) return getSimple("Celestial_Rift_Engine");
        if (itemId.equals("gtladditions:macro_atomic_resonant_fragment_stripper")) return getSimple("macro");

        return "";
    }

    /** 向前兼容：单参数版本 */
    public static String getTag(String itemId) {
        return getTag(itemId, null);
    }

    /** 生成无限单元格 NBT */
    public static String infinityCell(String innerId) {
        return infinityCell(innerId, null);
    }

    /** 生成无限单元格 NBT，可指定类型 */
    public static String infinityCell(String innerId, String type) {
        if (innerId == null) return "";
        String itemType = (type != null) ? type : (isRegisteredFluid(innerId) ? "f" : "i");
        return ",\"tag\":{\"record\":{\"#c\":\"ae2:" + itemType + "\",\"id\":\"" + innerId + "\"}}";
    }

    public static void normalizeEaeInfinityCellRecord(CompoundTag tag) {
        if (tag == null || !tag.contains("record", Tag.TAG_COMPOUND)) return;
        CompoundTag record = tag.getCompound("record");
        if (record.contains("tag", Tag.TAG_COMPOUND) && record.getCompound("tag").isEmpty()) {
            record.remove("tag");
        }
    }

    private static String getSimple(String key) {
        String v = SIMPLE_MAP.get(key);
        return v != null ? v : "";
    }

    private static void registerStackTag(String uid, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return;
        String tagJson = stack.hasTag() ? ",tag:" + stack.getTag().toString() : "";
        if (uid == null || uid.isEmpty()) {
            put(id.toString(), tagJson);
        } else {
            put(id.toString(), uid, tagJson);
        }
    }

    private static ResourceLocation parseId(String id) {
        return id != null ? ResourceLocation.tryParse(id.trim()) : null;
    }

    public static boolean isModLoadedForId(String id) {
        ResourceLocation resourceLocation = parseId(id);
        if (resourceLocation == null) return false;
        String namespace = resourceLocation.getNamespace();
        return "minecraft".equals(namespace) || ModList.get().isLoaded(namespace) || isRegisteredItem(id) || isRegisteredFluid(id);
    }

    public static boolean isRegisteredItem(String id) {
        ResourceLocation resourceLocation = parseId(id);
        return resourceLocation != null && ForgeRegistries.ITEMS.containsKey(resourceLocation);
    }

    public static boolean isRegisteredFluid(String id) {
        ResourceLocation resourceLocation = parseId(id);
        if (resourceLocation == null) return false;
        if (ForgeRegistries.FLUIDS.containsKey(resourceLocation)) return true;
        if (ForgeRegistries.ITEMS.containsKey(resourceLocation)) return false;
        String namespace = resourceLocation.getNamespace();
        return ModList.get().isLoaded(namespace);
    }

    public static boolean hasCustomNbt(String itemId, String uid) {
        if (itemId == null) return false;
        if ("expatternprovider:infinity_cell".equals(itemId) && uid != null) return true;
        if (uid != null) {
            Map<String, String> uidMap = TAG_MAP.get(itemId);
            if (uidMap != null && uidMap.containsKey(uid)) return true;
        }
        return SIMPLE_MAP.containsKey(itemId) ||
                itemId.equals("gtladditions:forge_of_the_antichrist") ||
                itemId.equals("gtladditions:arcanic_astrograph") ||
                itemId.equals("gtladditions:thread_modifier_hatch") ||
                itemId.equals("gtladditions:macro_atomic_resonant_fragment_stripper");
    }

    public static boolean isPackEntryAvailable(String itemId, String innerId) {
        if (!isLazyPackIdAvailable(itemId, false)) return false;
        if (innerId == null || innerId.isEmpty()) return true;
        if (hasCustomNbt(itemId, innerId)) return true;
        return isLazyPackIdAvailable(innerId, true);
    }

    private static boolean isLazyPackIdAvailable(String id, boolean allowFluid) {
        ResourceLocation resourceLocation = parseId(id);
        if (resourceLocation == null) return false;
        if (isRegisteredItem(id)) return true;
        if (allowFluid && isRegisteredFluid(id)) return true;
        String namespace = resourceLocation.getNamespace();
        return "minecraft".equals(namespace) || ModList.get().isLoaded(namespace);
    }

    private static PackEntry parsePackEntry(String entry) {
        if (entry == null || entry.isBlank()) return null;
        String trimmed = entry.trim();
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("^(\\d+)\\s*x\\s*([^@]+)(?:@(.+))?$").matcher(trimmed);
        long amount;
        String itemId;
        String innerId;
        if (match.find()) {
            amount = Long.parseLong(match.group(1));
            itemId = match.group(2).trim();
            innerId = match.group(3) != null ? match.group(3).trim() : null;
        } else {
            int atIndex = trimmed.indexOf('@');
            amount = 1;
            itemId = atIndex >= 0 ? trimmed.substring(0, atIndex).trim() : trimmed;
            innerId = atIndex >= 0 ? trimmed.substring(atIndex + 1).trim() : null;
        }
        if (itemId.isEmpty()) return null;
        return new PackEntry(amount, itemId, innerId);
    }

    private record PackEntry(long amount, String itemId, String innerId) {}

    /** 清空注册表（重载时使用） */
    public static void clear() {
        TAG_MAP.clear();
        SIMPLE_MAP.clear();
    }

    // ========== AE2 元件包 NBT 构建 ==========

    /** 构建 AE2 便携元件 NBT，接受 JS 数组（List），专供 Rhino 调用（处理 ConsString） */
    public static String buildAECellNBTFromList(java.util.List<String> items, String displayName, java.util.List<String> lore) {
        String[] itemArr = new String[items.size()];
        int i = 0;
        for (Object item : items) itemArr[i++] = item != null ? item.toString() : "";
        String[] loreArr = null;
        if (lore != null) {
            loreArr = new String[lore.size()];
            i = 0;
            for (Object line : lore) loreArr[i++] = line != null ? line.toString() : "";
        }
        return buildAECellNBT(itemArr, displayName, loreArr);
    }

    /**
     * 构建 AE2 便携元件 NBT（替代 KubeJS 侧的 packed_cell_nbt2）。
     *
     * @param items 物品数组，格式 ["64x mod:id@innerId", "32x mod:id2", ...]
     * @param displayName 显示名称（可选，传 null 跳过）
     * @param lore 提示文本行（可选，传 null 跳过）
     * @return NBT 字符串，适用于 Item.of(id, nbt)
     */
    public static String buildAECellNBT(String[] items, String displayName, String[] lore) {
        // 解析并合并重复物品
        java.util.LinkedHashMap<String, Long> merged = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> innerIds = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> itemIds = new java.util.LinkedHashMap<>();

        for (String entry : items) {
            PackEntry parsedEntry = parsePackEntry(entry);
            if (parsedEntry == null || !isPackEntryAvailable(parsedEntry.itemId, parsedEntry.innerId)) continue;
            long amount = parsedEntry.amount;
            String id = parsedEntry.itemId;
            String innerId = parsedEntry.innerId;

            String key = id + (innerId != null ? "@" + innerId : "");
            if ("expatternprovider:infinity_cell".equals(id) && innerId != null) {
                merged.putIfAbsent(key, 1L);
            } else {
                Long current = merged.get(key);
                merged.put(key, (current != null ? current : 0L) + amount);
            }
            innerIds.put(key, innerId);
            itemIds.put(key, id);
        }

        // keys: ListTag
        ListTag keysList = new ListTag();
        java.util.List<String> orderedKeys = new java.util.ArrayList<>(merged.keySet());
        for (String entry : orderedKeys) {
            String id = itemIds.get(entry);
            String innerId = innerIds.get(entry);
            CompoundTag keyTag = new CompoundTag();
            keyTag.putString("#c", "ae2:i");
            keyTag.putString("id", id);

            if (innerId != null && "expatternprovider:infinity_cell".equals(id)) {
                CompoundTag recordTag = new CompoundTag();
                boolean isFluid = isRegisteredFluid(innerId);
                recordTag.putString("#c", "ae2:" + (isFluid ? "f" : "i"));
                recordTag.putString("id", innerId);
                CompoundTag innerTag = new CompoundTag();
                innerTag.put("record", recordTag);
                keyTag.put("tag", innerTag);
            }

            // 物品特定 NBT（从注册表读取）；infinity_cell@id 已在上方直接写入 record，避免重复 parse。
            if (!("expatternprovider:infinity_cell".equals(id) && innerId != null)) {
                String tagStr = getTag(id, innerId);
                if (tagStr != null && !tagStr.isEmpty()) {
                    // tagStr 格式: ',tag:{...}' → 提取 JSON 部分
                    String jsonPart = tagStr.startsWith(",tag:") ? tagStr.substring(5) : tagStr;
                    try {
                        CompoundTag parsed = TagParser.parseTag(jsonPart);
                        if ("expatternprovider:infinity_cell".equals(id)) normalizeEaeInfinityCellRecord(parsed);
                        keyTag.put("tag", parsed);
                    } catch (Exception ignored) {}
                }
            }

            keysList.add(keyTag);
        }

        // amts: long[]
        long[] amts = new long[orderedKeys.size()];
        int idx = 0;
        for (String entry : orderedKeys) {
            Long value = merged.get(entry);
            amts[idx++] = value != null ? value : 1L;
        }

        // Root
        CompoundTag root = new CompoundTag();
        root.putInt("RepairCost", 0);
        root.put("keys", keysList);
        root.putLongArray("amts", amts);
        root.putLong("ic", merged.size());
        root.putDouble("internalCurrentPower", 20000.0);

        // display
        if (displayName != null && !displayName.isEmpty()) {
            CompoundTag displayTag = new CompoundTag();
            displayTag.putString("Name", toDisplayJson(displayName));
            if (lore != null && lore.length > 0) {
                ListTag loreList = new ListTag();
                for (String line : lore) {
                    loreList.add(StringTag.valueOf(toDisplayJson(line)));
                }
                displayTag.put("Lore", loreList);
            }
            root.put("display", displayTag);
        }

        return root.toString();
    }

    /** 支持普通 § 文本和 {style}...{/} 行内 FCS 标记 */
    private static String toDisplayJson(String text) {
        if (text == null) return Component.Serializer.toJson(Component.literal(""));
        try {
            if (text.contains("{/}")) {
                return Component.Serializer.toJson(ShanhaiTextAPI.inline(text));
            }
        } catch (Exception ignored) {}
        return Component.Serializer.toJson(Component.literal(text));
    }

    // ========== 超级磁盘阵列（SDA）NBT 构建 ==========

    /** 创建 SDA 链式构建器，供 KubeJS 直接调用 */
    public static DShanhaiSDA sda(String displayName) {
        return DShanhaiSDA.create(displayName);
    }

    /** 创建 SDA 链式构建器，别名 */
    public static DShanhaiSDA createSDA(String displayName) {
        return DShanhaiSDA.create(displayName);
    }

    /** 超级磁盘阵列专用链式 NBT 构建器 */
    public static class DShanhaiSDA {
        private final List<String> items = new ArrayList<>();
        private final List<String> virtualCells = new ArrayList<>();
        private final List<String> lore = new ArrayList<>();
        private String displayName;

        private DShanhaiSDA(String displayName) {
            this.displayName = displayName != null ? displayName : "超级磁盘阵列";
        }

        public static DShanhaiSDA create(String displayName) {
            return new DShanhaiSDA(displayName);
        }

        public DShanhaiSDA name(String displayName) {
            this.displayName = displayName != null ? displayName : "超级磁盘阵列";
            return this;
        }

        /** 添加 SDA 便携终端内部物品，格式同 buildAECellNBT："64x mod:id@innerId" */
        public DShanhaiSDA itemOutput(String item) {
            if (item != null && !item.isEmpty()) this.items.add(item);
            return this;
        }

        /** 按物品/流体 ID 自动生成内联 infinity_cell 并写入 SDA */
        public DShanhaiSDA infinityOutput(String innerId) {
            String entry = buildInfinityCellEntry(innerId);
            if (!entry.isEmpty()) this.items.add(entry);
            return this;
        }

        public DShanhaiSDA infinityOutputsList(List<String> ids) {
            if (ids == null) return this;
            for (Object id : ids) infinityOutput(id != null ? id.toString() : "");
            return this;
        }

        public DShanhaiSDA infinityOutputsArray(String[] ids) {
            if (ids == null) return this;
            for (String id : ids) infinityOutput(id);
            return this;
        }

        /** 批量添加 SDA 便携终端内部物品，避免 Rhino 与 itemOutput(String) 重载歧义 */
        public DShanhaiSDA itemOutputsList(List<String> items) {
            if (items == null) return this;
            for (Object item : items) itemOutput(item != null ? item.toString() : "");
            return this;
        }

        public DShanhaiSDA itemOutputsArray(String[] items) {
            if (items == null) return this;
            for (String item : items) itemOutput(item);
            return this;
        }

        public DShanhaiSDA loreLine(String line) {
            if (line != null && !line.isEmpty()) this.lore.add(line);
            return this;
        }

        public DShanhaiSDA loreLines(List<String> lines) {
            if (lines == null) return this;
            for (Object line : lines) loreLine(line != null ? line.toString() : "");
            return this;
        }

        public DShanhaiSDA loreLinesArray(String[] lines) {
            if (lines == null) return this;
            for (String line : lines) loreLine(line);
            return this;
        }

        /** 添加虚拟磁盘，itemsNbt 格式如：{gtceu:naquadah_dust:100L} */
        public DShanhaiSDA virtualCell(String type, long bytes, String itemsNbt) {
            this.virtualCells.add(buildVirtualCell(type, bytes, itemsNbt));
            return this;
        }

        public DShanhaiSDA virtualCell(String type, long bytes) {
            return virtualCell(type, bytes, null);
        }

        public DShanhaiSDA itemVirtualCell(long bytes, String itemsNbt) {
            return virtualCell("item", bytes, itemsNbt);
        }

        public DShanhaiSDA itemVirtualCell(long bytes) {
            return virtualCell("item", bytes, null);
        }

        public DShanhaiSDA fluidVirtualCell(long bytes, String itemsNbt) {
            return virtualCell("fluid", bytes, itemsNbt);
        }

        public DShanhaiSDA fluidVirtualCell(long bytes) {
            return virtualCell("fluid", bytes, null);
        }

        /** 直接添加完整虚拟磁盘 NBT 字符串 */
        public DShanhaiSDA virtualCellNBT(String virtualCellNbt) {
            if (virtualCellNbt != null && !virtualCellNbt.isEmpty()) this.virtualCells.add(virtualCellNbt);
            return this;
        }

        /**
         * 将 AE2 处理样板封存进 virtual_cell（物品类型）。
         * 自动用 NBTBuilder 构建样板 NBT，避免硬编码。
         *
         * @param bytes   虚拟磁盘容量（如 26214400L）
         * @param encodePlayer 编码玩家名（可 null）
         * @param inputs  输入条目数组 ["14x kubejs:hv_universal_circuit", ...]
         * @param outputs 输出条目数组 ["1x dishanhai:wl_board_hv", ...]
         */
        public DShanhaiSDA patternCell(long bytes, String encodePlayer, String[] inputs, String[] outputs) {
            String patternNbt = NBTBuilder.buildProcessingPattern(encodePlayer, inputs, outputs);
            String vcNbt = buildVirtualCell("item", bytes, patternNbt);
            this.virtualCells.add(vcNbt);
            return this;
        }

        /** 直接从样板 NBT 字符串封存进 virtual_cell */
        public DShanhaiSDA patternCellFromNBT(long bytes, String patternNbt) {
            String vcNbt = buildVirtualCell("item", bytes, patternNbt);
            this.virtualCells.add(vcNbt);
            return this;
        }

        public String buildNBT() {
            return buildSDAFromList(items, displayName, lore, virtualCells);
        }

        public String build() {
            return buildNBT();
        }
    }

    /** 构建单个虚拟磁盘 NBT 字符串 */
    public static String buildVirtualCell(String type, long bytes, String itemsNbt) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type);
        tag.putLong("bytes", bytes);
        if (itemsNbt != null && !itemsNbt.isEmpty()) {
            try {
                tag.put("items", TagParser.parseTag(itemsNbt));
            } catch (Exception ignored) {}
        }
        return tag.toString();
    }

    /** 将物品/流体 ID 转成 AE2 无限元件条目，供 SDA/AE 元件包内联使用 */
    public static String buildInfinityCellEntry(String innerId) {
        if (innerId == null || innerId.isEmpty()) return "";
        ResourceLocation id = ResourceLocation.tryParse(innerId);
        if (id == null) return "";
        boolean isItem = ForgeRegistries.ITEMS.containsKey(id);
        boolean isFluid = ForgeRegistries.FLUIDS.containsKey(id);
        if (!isItem && !isFluid) return "";
        return "1x expatternprovider:infinity_cell@" + innerId;
    }

    /** 构建 SDA 完整 NBT（便携终端物品 + virtual_cells），接受 JS 数组 */
    public static String buildSDAFromList(java.util.List<String> items, String displayName,
                                           java.util.List<String> lore, java.util.List<String> virtualCells) {
        if (items == null) items = java.util.Collections.emptyList();
        String[] itemArr = new String[items.size()];
        int i = 0;
        for (Object item : items) itemArr[i++] = item != null ? item.toString() : "";
        String[] loreArr = null;
        if (lore != null) {
            loreArr = new String[lore.size()];
            i = 0;
            for (Object line : lore) loreArr[i++] = line != null ? line.toString() : "";
        }
        String[] vcArr = null;
        if (virtualCells != null) {
            vcArr = new String[virtualCells.size()];
            i = 0;
            for (Object vc : virtualCells) vcArr[i++] = vc != null ? vc.toString() : "";
        }
        return buildSDA(itemArr, displayName, loreArr, vcArr);
    }

    /**
     * 构建 SDA 完整 NBT（便携终端物品 + virtual_cells）。
     * items/displayName/lore 格式同 buildAECellNBT。
     * virtualCells 为虚拟磁盘 NBT 字符串数组，可用 buildVirtualCell() 生成。
     */
    public static String buildSDA(String[] items, String displayName, String[] lore, String[] virtualCells) {
        String baseNbt = buildAECellNBT(items != null ? items : new String[0], displayName, null);
        CompoundTag root;
        try {
            root = TagParser.parseTag(baseNbt);
        } catch (Exception e) {
            return baseNbt;
        }
        attachSdaDynamicLore(root, lore);

        if (virtualCells != null && virtualCells.length > 0) {
            ListTag vcList = new ListTag();
            for (String vc : virtualCells) {
                if (vc == null || vc.isEmpty()) continue;
                try {
                    vcList.add(TagParser.parseTag(vc));
                } catch (Exception ignored) {}
            }
            if (!vcList.isEmpty()) {
                root.put("virtual_cells", vcList);
            }
        }
        if (!root.hasUUID(SuperDiskArrayInventory.TAG_UUID)) {
            root.putUUID(SuperDiskArrayInventory.TAG_UUID, SuperDiskArrayInventory.generateDeterministicUUID(root));
        }
        return root.toString();
    }

    /** SDA 的 lore 需要客户端每帧重建，不能只依赖静态 display.Lore JSON */
    private static void attachSdaDynamicLore(CompoundTag root, String[] lore) {
        if (lore == null || lore.length == 0) return;
        ListTag rawLore = new ListTag();
        for (String line : lore) {
            if (line != null && !line.isEmpty()) rawLore.add(StringTag.valueOf(line));
        }
        if (rawLore.isEmpty()) return;
        root.put("shanhai_fcs_lore", rawLore);
        if (root.contains("display", Tag.TAG_COMPOUND)) {
            root.getCompound("display").remove("Lore");
        }
    }
}
