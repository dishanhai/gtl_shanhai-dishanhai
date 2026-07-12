package com.dishanhai.gt_shanhai.api.ae;

import com.dishanhai.gt_shanhai.api.ae.DShanhaiAEKeyCodec;

import appeng.api.stacks.AEKey;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.nbt.TagParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SDA 内容持久化存储层。
 *
 * <p>将 SDA 的内容（AEKey → BigInteger 数量）以 UUID 为 key 持久化到
 * {@code kubejs/data/sda_uuid_store/<uuid>.json}，使内容跟随整合包分发，
 * 独立于存档 SavedData（world/data）。
 *
 * <p>写入时机：{@link com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory#persist()} 调用时。
 * 读取时机：{@code load()} 发现 SavedData 为空时自动兜底还原。
 *
 * <p>文件格式（GSON，UTF-8）：
 * <pre>
 * {
 *   "uuid": "...",
 *   "entries": [
 *     { "key": "{#c:\"ae2:i\",id:\"minecraft:diamond\"}", "amount": "1000" },
 *     ...
 *   ]
 * }
 * </pre>
 */
public final class DShanhaiSdaContentStore {

    private static final String STORE_DIR = "kubejs/data/sda_uuid_store";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DShanhaiSdaContentStore() {}

    // ============ 写入 ============

    /**
     * 把 SDA 内容持久化到 kubejs/data/sda_uuid_store/<uuid>.json。
     * 内容为空时删除对应文件（避免留下空壳）。
     * 静默忽略 IO 异常，不阻断游戏流程。
     */
    public static void persist(UUID uuid, Map<AEKey, BigInteger> amounts) {
        if (uuid == null) return;
        try {
            Path dir = Path.of(STORE_DIR);
            File file = dir.resolve(uuid.toString() + ".json").toFile();

            // 内容为空 → 删除文件（清理，避免领取者还原出空盘覆盖已有内容）
            if (amounts == null || amounts.isEmpty()) {
                if (file.exists()) file.delete();
                return;
            }

            Files.createDirectories(dir);

            JsonObject root = new JsonObject();
            root.addProperty("uuid", uuid.toString());
            JsonArray entries = new JsonArray();
            for (Map.Entry<AEKey, BigInteger> entry : amounts.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null
                        || entry.getValue().signum() <= 0) continue;
                JsonObject e = new JsonObject();
                // 用 SNBT 字符串序列化 AEKey tag，与现有 codec 保持对称
                e.addProperty("key", DShanhaiAEKeyCodec.toNormalizedTag(entry.getKey()).toString());
                e.addProperty("amount", entry.getValue().toString());
                entries.add(e);
            }
            root.add("entries", entries);

            try (FileWriter w = new FileWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (Exception ignored) {
            // 持久化失败不影响游戏，内容仍在 world/data
        }
    }

    // ============ 读取 ============

    /**
     * 从 kubejs/data/sda_uuid_store/<uuid>.json 读回内容。
     * 文件不存在或解析失败时返回空 map，不抛异常。
     */
    public static Map<AEKey, BigInteger> restore(UUID uuid) {
        Map<AEKey, BigInteger> result = new LinkedHashMap<>();
        if (uuid == null) return result;
        try {
            File file = Path.of(STORE_DIR).resolve(uuid.toString() + ".json").toFile();
            if (!file.exists()) return result;

            JsonObject root;
            try (FileReader r = new FileReader(file, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(r).getAsJsonObject();
            }
            if (!root.has("entries")) return result;

            for (var elem : root.getAsJsonArray("entries")) {
                JsonObject e = elem.getAsJsonObject();
                String keySnbt = e.get("key").getAsString();
                String amtStr = e.get("amount").getAsString();
                AEKey key = DShanhaiAEKeyCodec.fromNormalizedTag(TagParser.parseTag(keySnbt));
                if (key == null) continue;
                BigInteger amt = new BigInteger(amtStr);
                if (amt.signum() <= 0) continue;
                result.merge(key, amt, BigInteger::add);
            }
        } catch (Exception ignored) {
            // 解析失败返回已读到的部分，不抛
        }
        return result;
    }

    /** 判断 kubejs/data 里是否存有该 UUID 的内容文件。 */
    public static boolean hasStored(UUID uuid) {
        if (uuid == null) return false;
        return Path.of(STORE_DIR).resolve(uuid.toString() + ".json").toFile().exists();
    }

    /**
     * {@code shanhai_sda_uuid} 的 int 数组正则：{@code [I;a,b,c,d]} / {@code [I; a b c d]} 都能匹配
     * （FTBQ 手写 snbt 常见空格分隔，{@link net.minecraft.nbt.CompoundTag#toString()} 自动生成的是逗号分隔，
     * 分隔符统一放宽成"逗号和/或空格皆可、可重复"）。
     */
    private static final java.util.regex.Pattern SDA_UUID_PATTERN = java.util.regex.Pattern.compile(
            "shanhai_sda_uuid:\\s*\\[I;\\s*(-?\\d+)[,\\s]+(-?\\d+)[,\\s]+(-?\\d+)[,\\s]+(-?\\d+)\\s*\\]");

    /**
     * 在一段文本里扫描所有 shanhai_sda_uuid 出现，解出的 UUID 追加进 out（去重由调用方的 Set 保证）。
     * Minecraft 以 int 数组形式存储 UUID：msb = ((long)a << 32) | (b & 0xFFFFFFFFL)，lsb 同理。
     */
    private static void scanUuidsInText(String text, java.util.Set<UUID> out) {
        java.util.regex.Matcher m = SDA_UUID_PATTERN.matcher(text);
        while (m.find()) {
            try {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                int c = Integer.parseInt(m.group(3));
                int d = Integer.parseInt(m.group(4));
                long msb = ((long) a << 32) | (b & 0xFFFFFFFFL);
                long lsb = ((long) c << 32) | (d & 0xFFFFFFFFL);
                out.add(new UUID(msb, lsb));
            } catch (Exception ignored) {}
        }
    }

    /**
     * 扫描 config/ftbquests/quests/chapters/ 下所有 .snbt 文件，
     * 提取其中引用的 shanhai_sda_uuid 字段，返回去重后的 UUID 集合。
     */
    public static java.util.Set<UUID> scanQuestSnbtUuids() {
        java.util.Set<UUID> result = new java.util.LinkedHashSet<>();
        java.io.File dir = new java.io.File("config/ftbquests/quests/chapters");
        if (!dir.isDirectory()) return result;
        java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".snbt"));
        if (files == null) return result;
        for (java.io.File f : files) {
            try {
                String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                scanUuidsInText(text, result);
            } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * 扫描 config/gt_shanhai/shop.json，提取商品 goodsNbt 模板里写死的 shanhai_sda_uuid——
     * 商品本体就是提前捕获的固定 SDA（管理员在编辑器里选中一个已有 SDA 作为「商品」）时会带这个字段，
     * 与任务奖励同构（UUID 写死在可分发的配置文件里），复用同一套正则扫描。
     *
     * <p>注意：现场打包的动态 SDA（{@link com.dishanhai.gt_shanhai.common.shop.ShopPurchase#deliverItems}
     * 超量阈值触发的 {@code packAsSda}）UUID 是运行时随机生成，不落在 shop.json 里，这里扫不到；
     * 那类 SDA 靠 {@code /山海 SDA 全部导出}（直接读 world/data）兜底覆盖。</p>
     */
    public static java.util.Set<UUID> scanShopJsonUuids() {
        java.util.Set<UUID> result = new java.util.LinkedHashSet<>();
        java.io.File file = new java.io.File("config/gt_shanhai", "shop.json");
        if (!file.isFile()) return result;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            scanUuidsInText(text, result);
        } catch (Exception ignored) {}
        return result;
    }
}
