package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「任务 → 商店商品」手动导航绑定（山海署名，服务端权威）。
 *
 * <p>跟 {@link ShopEntry#getPrerequisiteQuestId} 是<b>两回事</b>，别混：前者是购买门槛
 * （「买这件商品得先做完那个任务」，服务端在 {@code ShopActionPacket#doBuy} 里拦结算），
 * 本表是纯导航（「这个任务可以去商店买这些东西」），不参与任何结算，只决定任务详情页上
 * 那条跳转入口显不显示、跳去哪。因此商品没配前置也能被任务指过来。</p>
 *
 * <p>存 {@code config/gt_shanhai/shop_quest_links.json}：key=FTBQ 任务 ID（十六进制，
 * 见 {@code QuestObjectBase#getCodeString}），value=商品 {@link ShopEntry#getStableId} 列表。
 * 用 stableId 而不是 entryKey——后者是快照下标，商店增删一条就集体错位。</p>
 */
public final class ShopQuestLinkConfig {

    private static final String CONFIG_DIR = "config/gt_shanhai";
    private static final File LINKS_FILE = new File(CONFIG_DIR, "shop_quest_links.json");
    private static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** questHexId → stableId 列表（保持添加顺序）。 */
    private static volatile Map<String, List<String>> links = new LinkedHashMap<>();
    private static volatile boolean loaded = false;

    private ShopQuestLinkConfig() {}

    /** 全量快照（只读副本，供同步包编码用）。 */
    public static synchronized Map<String, List<String>> all() {
        ensureLoaded();
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : links.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return copy;
    }

    /** 追加一条绑定；已存在返回 false（不重复添加）。 */
    public static synchronized boolean add(String questHexId, String stableId) {
        ensureLoaded();
        if (questHexId == null || questHexId.isBlank() || stableId == null || stableId.isBlank()) return false;
        List<String> list = links.computeIfAbsent(questHexId.trim(), ignored -> new ArrayList<>());
        if (list.contains(stableId.trim())) return false;
        list.add(stableId.trim());
        save();
        return true;
    }

    /** 移除一条绑定；不存在返回 false。移除后该任务没有任何绑定就把 key 一并删掉，避免文件里留空数组。 */
    public static synchronized boolean remove(String questHexId, String stableId) {
        ensureLoaded();
        if (questHexId == null || questHexId.isBlank() || stableId == null || stableId.isBlank()) return false;
        String quest = questHexId.trim();
        List<String> list = links.get(quest);
        if (list == null || !list.remove(stableId.trim())) return false;
        if (list.isEmpty()) links.remove(quest);
        save();
        return true;
    }

    private static void ensureLoaded() {
        if (!loaded) reload();
    }

    public static synchronized void reload() {
        Map<String, List<String>> next = new LinkedHashMap<>();
        loaded = true;
        if (!LINKS_FILE.exists()) {
            links = next;
            return;
        }
        try (java.io.Reader r = new java.io.InputStreamReader(
                new java.io.FileInputStream(LINKS_FILE), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (!e.getValue().isJsonArray()) continue;
                List<String> values = new ArrayList<>();
                for (JsonElement el : e.getValue().getAsJsonArray()) {
                    String stableId = el.getAsString();
                    if (stableId != null && !stableId.isBlank() && !values.contains(stableId)) values.add(stableId);
                }
                if (!values.isEmpty()) next.put(e.getKey(), values);
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 读取 shop_quest_links.json 失败: {}", e.getMessage());
        }
        links = next;
    }

    private static void save() {
        try {
            new File(CONFIG_DIR).mkdirs();
            JsonObject root = new JsonObject();
            for (Map.Entry<String, List<String>> e : links.entrySet()) {
                JsonArray arr = new JsonArray();
                for (String v : e.getValue()) arr.add(v);
                root.add(e.getKey(), arr);
            }
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(LINKS_FILE), StandardCharsets.UTF_8)) {
                w.write(GSON.toJson(root));
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 保存 shop_quest_links.json 失败: {}", e.getMessage());
        }
    }
}
