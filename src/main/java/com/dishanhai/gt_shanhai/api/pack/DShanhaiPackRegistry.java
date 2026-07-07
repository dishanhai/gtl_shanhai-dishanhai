package com.dishanhai.gt_shanhai.api.pack;

import com.google.gson.*;
import com.gregtechceu.gtceu.api.GTValues;
import com.dishanhai.gt_shanhai.api.DShanhaiNBTAPI;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 山海包注册器 — 一键注册 AE 包，自动模组过滤、去重、缓存、JEI 同步。
 *
 * KubeJS 用法 (startup_scripts):
 * <pre>
 * // 基础注册
 * DShanhaiPackRegistry.create("superAE", [
 *     "64x minecraft:diamond",
 *     "1x expatternprovider:infinity_cell@gtceu:hydrogen"
 * ], "超级AE包", ["§7描述1", "§7描述2"])
 *     .filterByMod("gtceu", "ae2")
 *     .lock("v1.2");
 *
 * // 获取
 * var pack = DShanhaiPackRegistry.get("superAE");
 * pack.nbt();     // → NBT 字符串
 * pack.typeCount();
 * </pre>
 */
public class DShanhaiPackRegistry {

    private static final Map<String, DShanhaiPack> PACKS = new LinkedHashMap<>();
    private static final Path CACHE_DIR = Path.of("kubejs/data/packs/");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ========== 创建 ==========

    /** 创建包并注册 */
    public static DShanhaiPackBuilder create(String id, String[] items, String name, String[] lore) {
        return new DShanhaiPackBuilder(id, items, name, lore);
    }

    /** 获取已注册的包 */
    public static DShanhaiPack get(String id) {
        return PACKS.get(id);
    }

    /** 获取所有包 */
    public static Collection<DShanhaiPack> getAll() {
        return PACKS.values();
    }

    /** 获取所有包内物品（用于 JEI 注册） */
    public static List<net.minecraft.world.item.ItemStack> getAllItemStacks() {
        return PACKS.values().stream()
                .flatMap(p -> p.toItemStacks().stream())
                .collect(Collectors.toList());
    }

    // ========== 构建器 ==========

    public static class DShanhaiPackBuilder {
        private final String id;
        private final String[] rawItems;
        private final String name;
        private final String[] lore;
        private String cellType = "ae2:portable_item_cell_256k";
        private String version = null;
        private List<String> filterMods = null;      // null = 不过滤
        private List<String> filterTags = null;
        private List<Integer> filterTiers = null;

        DShanhaiPackBuilder(String id, String[] items, String name, String[] lore) {
            this.id = id;
            this.rawItems = items;
            this.name = name;
            this.lore = lore;
        }

        public DShanhaiPackBuilder cellType(String ct) { this.cellType = ct; return this; }
        public DShanhaiPackBuilder lock(String v) { this.version = v; return this; }

        /** 只包含指定模组的物品 */
        public DShanhaiPackBuilder filterByMod(String... mods) {
            this.filterMods = Arrays.asList(mods);
            return this;
        }

        /** 只包含指定标签的物品 */
        public DShanhaiPackBuilder filterByTag(String... tags) {
            this.filterTags = Arrays.asList(tags);
            return this;
        }

        /** 只包含 >= 指定电压等级的机器 */
        public DShanhaiPackBuilder filterByTier(int... tiers) {
            this.filterTiers = Arrays.stream(tiers).boxed().collect(Collectors.toList());
            return this;
        }

        /** 构建并注册 */
        public DShanhaiPack build() {
            // 1. 解析 + 去重
            var merged = new LinkedHashMap<String, DShanhaiPack.PackItemEntry>();
            for (String raw : rawItems) {
                if (raw == null || raw.isEmpty()) continue;
                var entry = DShanhaiPack.parseEntry(raw);
                if (!DShanhaiNBTAPI.isPackEntryAvailable(entry.itemId, entry.innerId)) continue;
                String key = entry.key();

				// 3. 模组过滤（可选，仅在显式设置时额外限制）
				if (filterMods != null && !filterMods.contains(entry.modId)) continue;
                // 4. 标签过滤
                if (filterTags != null) {
                    var builtInTag = net.minecraft.tags.ItemTags.create(new ResourceLocation(entry.itemId));
                    boolean tagMatch = filterTags.stream().anyMatch(t ->
                            builtInTag.equals(net.minecraft.tags.ItemTags.create(new ResourceLocation(t))));
                    if (!tagMatch) continue;
                }

                // 6. 电压等级过滤
                if (filterTiers != null) {
                    int tier = guessTier(entry.itemId);
                    if (!filterTiers.contains(tier)) continue;
                }

                // 去重合并
                if (merged.containsKey(key)) {
                    merged.put(key, merged.get(key).merge(entry.amount));
                } else {
                    merged.put(key, entry);
                }
            }

            var entries = new ArrayList<>(merged.values());

            // 7. 缓存检查
            String cacheKey = buildCacheKey(id, version, entries);
            Path cacheFile = CACHE_DIR.resolve(id.toLowerCase(Locale.ROOT) + ".json");
            boolean fromCache = false;

            // 尝试读取缓存
            if (version != null && cacheFile.toFile().exists()) {
                try {
                    String cached = new String(Files.readAllBytes(cacheFile), StandardCharsets.UTF_8);
                    var obj = JsonParser.parseString(cached).getAsJsonObject();
                    if (cacheKey.equals(obj.get("hash").getAsString())) {
                        String nbt = obj.get("nbt").getAsString();
                        var pack = new DShanhaiPack(id, name, Arrays.asList(lore), cellType, entries, nbt, true);
                        PACKS.put(id, pack);
                        return pack;
                    }
                } catch (Exception ignored) {}
            }

            // 8. 构建 NBT
            String nbt = buildNBT(entries, name, lore);
            var pack = new DShanhaiPack(id, name, Arrays.asList(lore), cellType, entries, nbt, false);
            PACKS.put(id, pack);

            // 9. 写入缓存
            if (version != null) {
                try {
                    Files.createDirectories(CACHE_DIR);
                    var obj = new JsonObject();
                    obj.addProperty("hash", cacheKey);
                    obj.addProperty("nbt", nbt);
                    obj.addProperty("version", version);
                    obj.addProperty("items", entries.size());
                    Files.write(cacheFile, GSON.toJson(obj).getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }

            return pack;
        }
    }

    // ========== NBT 构建 ==========

    static String buildNBT(List<DShanhaiPack.PackItemEntry> entries, String name, String[] lore) {
        CompoundTag root = new CompoundTag();
        root.putInt("RepairCost", 0);
        root.putDouble("internalCurrentPower", 20000.0);
        root.putLong("ic", entries.size());

        // keys
        ListTag keysList = new ListTag();
        long[] amts = new long[entries.size()];
        int idx = 0;
        for (var entry : entries) {
            CompoundTag keyTag = new CompoundTag();
            keyTag.putString("#c", "ae2:i");
            keyTag.putString("id", entry.itemId);

            if (entry.innerId != null && "expatternprovider:infinity_cell".equals(entry.itemId)) {
                CompoundTag recordTag = new CompoundTag();
                boolean isFluid = DShanhaiNBTAPI.isRegisteredFluid(entry.innerId);
                recordTag.putString("#c", "ae2:" + (isFluid ? "f" : "i"));
                recordTag.putString("id", entry.innerId);
                CompoundTag inner = new CompoundTag();
                inner.put("record", recordTag);
                keyTag.put("tag", inner);
            }
            String customNbt = DShanhaiNBTAPI.getTag(entry.itemId, entry.innerId);
            if (customNbt != null && !customNbt.isEmpty()) {
                    String trimmed = customNbt.trim();
                    String jsonPart = trimmed.startsWith(",tag:") ? trimmed.substring(5)
                            : trimmed.startsWith(", tag:") ? trimmed.substring(6).trim()
                            : trimmed;
                try {
                    CompoundTag parsed = TagParser.parseTag(jsonPart);
                    if ("expatternprovider:infinity_cell".equals(entry.itemId)) {
                        DShanhaiNBTAPI.normalizeEaeInfinityCellRecord(parsed);
                    }
                    keyTag.put("tag", parsed);
                } catch (Exception ignored) {}
            }
            keysList.add(keyTag);
            amts[idx++] = entry.amount;
        }
        root.put("keys", keysList);
        root.putLongArray("amts", amts);

        // display
        if (name != null && !name.isEmpty()) {
            CompoundTag display = new CompoundTag();
            display.putString("Name", "{\"text\":\"" + name + "\"}");
            if (lore != null && lore.length > 0) {
                ListTag loreList = new ListTag();
                for (String line : lore) {
                    String resolved = line.replace("%count%", String.valueOf(entries.size()));
                    loreList.add(StringTag.valueOf("{\"text\":\"" + resolved + "\"}"));
                }
                display.put("Lore", loreList);
            }
            root.put("display", display);
        }

        if (!root.hasUUID(SuperDiskArrayInventory.TAG_UUID)) {
            root.putUUID(SuperDiskArrayInventory.TAG_UUID, SuperDiskArrayInventory.generateDeterministicUUID(root));
        }

        return root.toString();
    }

    // ========== 内部重建 ==========

    static DShanhaiPack rebuild(String id, String name, List<String> lore,
                                  String cellType, List<DShanhaiPack.PackItemEntry> entries) {
        String[] loreArr = lore.toArray(new String[0]);
        String nbt = buildNBT(entries, name, loreArr);
        return new DShanhaiPack(id, name, lore, cellType, entries, nbt, false);
    }

    // ========== 工具 ==========

    private static String buildCacheKey(String id, String version, List<DShanhaiPack.PackItemEntry> entries) {
        var sb = new StringBuilder();
        sb.append(id).append("|").append(version != null ? version : "noversion").append("|");
        for (var e : entries) {
            sb.append(e.key()).append(":").append(e.amount).append(":")
                    .append(DShanhaiNBTAPI.getTag(e.itemId, e.innerId)).append(",");
        }
        return DShanhaiPack.hash(sb.toString());
    }

    /** 从物品 ID 猜测电压等级 */
    private static int guessTier(String itemId) {
        String path = itemId.contains(":") ? itemId.split(":", 2)[1] : itemId;
        // GTCEu 机器命名模式: tier_name (如 uv_fusion_reactor)
        for (int i = GTValues.V.length - 2; i >= 0; i--) {
            String tierName = GTValues.VN[i].toLowerCase(java.util.Locale.ROOT);
            if (path.startsWith(tierName + "_") || path.contains("_" + tierName + "_")) {
                return i;
            }
        }
        return -1; // 无法识别
    }

    /** 清除缓存 */
    public static void clearCache() {
        PACKS.clear();
        try (var files = Files.list(CACHE_DIR)) {
            files.forEach(f -> { try { Files.delete(f); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }
}
