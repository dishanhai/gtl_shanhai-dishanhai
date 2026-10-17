package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Counts the currently loaded ShanHai data-pack recipes, including the generated cache pack. */
public final class DShanhaiJsonRecipeStats {
    private DShanhaiJsonRecipeStats() {}

    public record TypeCounts(long success, long failed, long disabled) {
        public long total() {
            return success + failed + disabled;
        }
    }

    public record Failure(String recipeId, String type, String hint) {}

    public record Snapshot(long success, long failed, long disabled, Map<String, TypeCounts> byType,
                           List<Failure> failures) {
        public Snapshot {
            if (failures == null) failures = List.of();
        }

        public long total() {
            return success + failed + disabled;
        }
    }

    private static volatile Snapshot cachedSnapshot;
    private static volatile MinecraftServer cachedServer;

    /** 配方重載後丟掉快照。登入橫幅和重複掃描都不要再走一遍 listResources。 */
    public static void invalidate() {
        cachedSnapshot = null;
        cachedServer = null;
    }

    public static Snapshot scan(MinecraftServer server) {
        if (server == null) return new Snapshot(0, 0, 0, Map.of(), List.of());
        Snapshot hit = cachedSnapshot;
        if (hit != null && cachedServer == server) return hit;
        Snapshot computed = scanUncached(server);
        cachedSnapshot = computed;
        cachedServer = server;
        return computed;
    }

    private static Snapshot scanUncached(MinecraftServer server) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        List<Failure> failures = new ArrayList<>();
        JsonObject config = DShanhaiRecipeEnabledCondition.readConfig();
        boolean includeCache = DShanhaiRecipeCache.isCacheValid();
        for (Map.Entry<ResourceLocation, Resource> entry : server.getResourceManager()
                .listResources("recipes", id -> id.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation file = entry.getKey();
            boolean bundled = GTDishanhaiMod.MOD_ID.equals(file.getNamespace())
                    || isForeignNamespaceResource(file, entry.getValue());
            boolean cached = includeCache && DShanhaiRecipePackFinder.PACK_ID.equals(entry.getValue().sourcePackId());
            if (!bundled && !cached) continue;

            String path = file.getPath();
            String recipePath = path.substring("recipes/".length(), path.length() - ".json".length());
            ResourceLocation recipeId;
            try {
                recipeId = new ResourceLocation(file.getNamespace(), recipePath);
            } catch (RuntimeException invalidId) {
                String brokenId = file.getNamespace() + ":" + recipePath;
                String hint = "配方 id 不合法，路徑只能用小寫、數字和 / . _ -：" + shortMessage(invalidId);
                GTDishanhaiMod.LOGGER.warn("[DRE] JSON 配方載入失敗: {} {}", brokenId, hint);
                add(counts, "unknown", 1);
                failures.add(new Failure(brokenId, "unknown", hint));
                continue;
            }
            JsonObject json;
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement element = JsonParser.parseReader(reader);
                json = element.getAsJsonObject();
            } catch (Exception e) {
                String hint = "JSON 無法讀取: " + shortMessage(e);
                GTDishanhaiMod.LOGGER.warn("[DRE] JSON 配方載入失敗: {} {}", recipeId, hint);
                add(counts, "unknown", 1);
                failures.add(new Failure(recipeId.toString(), "unknown", hint));
                continue;
            }

            String rawType = json.has("type") && json.get("type").isJsonPrimitive()
                    ? json.get("type").getAsString() : "unknown";
            String type = rawType;
            if (type.startsWith("gtceu:")) type = type.substring("gtceu:".length());
            if (server.getRecipeManager().byKey(recipeId).isPresent()
                    || isLoadedInGtRecipeLookup(recipeId, rawType)) {
                add(counts, type, 0);
            } else if (isDisabled(json, config)) {
                add(counts, type, 2);
            } else {
                String hint = explainUnload(server, recipeId.toString(), rawType, json);
                GTDishanhaiMod.LOGGER.warn("[DRE] JSON 配方載入失敗: {} [{}] {}", recipeId, rawType, hint);
                add(counts, type, 1);
                failures.add(new Failure(recipeId.toString(), rawType, hint));
            }
        }

        Map<String, TypeCounts> byType = new LinkedHashMap<>();
        long success = 0, failed = 0, disabled = 0;
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            long[] values = entry.getValue();
            byType.put(entry.getKey(), new TypeCounts(values[0], values[1], values[2]));
            success += values[0];
            failed += values[1];
            disabled += values[2];
        }
        return new Snapshot(success, failed, disabled, byType, List.copyOf(failures));
    }

    /**
     * Recipes the datapack left unloaded because {@code recipe_enabled} is off,
     * or because a required mod is missing. File id is the datapack id. A different
     * condition {@code recipeId} is appended so {@code /配方开关} can target it.
     */
    public static List<String> disabledRecipeIds(MinecraftServer server) {
        if (server == null) return List.of();
        JsonObject config = DShanhaiRecipeEnabledCondition.readConfig();
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<ResourceLocation, Resource> entry : server.getResourceManager()
                .listResources("recipes", id -> id.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation file = entry.getKey();
            String path = file.getPath();
            if (!path.startsWith("recipes/") || path.length() <= "recipes/.json".length()) continue;
            String recipePath = path.substring("recipes/".length(), path.length() - ".json".length());
            String fileId;
            try {
                fileId = new ResourceLocation(file.getNamespace(), recipePath).toString();
            } catch (RuntimeException invalidId) {
                continue;
            }
            JsonObject json;
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element == null || !element.isJsonObject()) continue;
                json = element.getAsJsonObject();
            } catch (Exception ignored) {
                continue;
            }
            if (!isDisabled(json, config)) continue;
            String label = disabledLabel(fileId, json);
            if (label.isEmpty() || !seen.add(label)) continue;
            ids.add(label);
        }
        return ids;
    }

    static String disabledLabel(String fileId, JsonObject json) {
        String file = fileId == null ? "" : fileId;
        String written = enabledRecipeId(json);
        if (written == null || written.isEmpty() || written.equals(file)) return file;
        return file + " [" + written + "]";
    }

    private static String enabledRecipeId(JsonObject json) {
        if (json == null || !json.has("conditions") || !json.get("conditions").isJsonArray()) return null;
        for (JsonElement element : json.getAsJsonArray("conditions")) {
            if (!element.isJsonObject()) continue;
            JsonObject condition = element.getAsJsonObject();
            if (!condition.has("type") || !condition.get("type").isJsonPrimitive()) continue;
            if (!DShanhaiRecipeEnabledCondition.ID.toString().equals(condition.get("type").getAsString())) continue;
            if (!condition.has("recipeId") || !condition.get("recipeId").isJsonPrimitive()) return null;
            return condition.get("recipeId").getAsString();
        }
        return null;
    }

    /**
     * 配方沒進 RecipeManager 時，從 JSON 裡找出未註冊的物品、流體和配方類型。
     * 查詢由呼叫端提供，方便在沒有註冊表的測試裡驗證提示文案。
     */
    static String explainLoadFailure(JsonObject json, String rawType,
                                     Predicate<String> itemExists,
                                     Predicate<String> fluidExists,
                                     Predicate<String> recipeTypeExists) {
        return explainLoadFailure(null, json, rawType, itemExists, fluidExists, recipeTypeExists, null);
    }

    /**
     * 配方沒進 RecipeManager 時，從 JSON 裡找出未註冊的物品、流體、配方類型，
     * 以及萬數量仍寫成 {@code gtceu:sized}、{@code gtceu:long} 缺 {@code actualAmount}、
     * distort 缺 {@code ebf_temp}、{@code recipe_enabled} 的 id 和檔案 id 不一致。
     */
    static String explainLoadFailure(String recipeId, JsonObject json, String rawType,
                                     Predicate<String> itemExists,
                                     Predicate<String> fluidExists,
                                     Predicate<String> recipeTypeExists) {
        return explainLoadFailure(recipeId, json, rawType, itemExists, fluidExists, recipeTypeExists, null);
    }

    /**
     * {@code duplicateIds} 是已經在配方表裡、輸入（含 chance 為 0 的不消耗材料）和產物都相同的配方。
     * 只是消耗部分相同、但多了模具或電路時，不算重複。沒有對上時仍用通用提示。
     */
    static String explainLoadFailure(String recipeId, JsonObject json, String rawType,
                                     Predicate<String> itemExists,
                                     Predicate<String> fluidExists,
                                     Predicate<String> recipeTypeExists,
                                     List<String> duplicateIds) {
        Set<String> unknownItems = new LinkedHashSet<>();
        Set<String> unknownFluids = new LinkedHashSet<>();
        Set<String> illegalIds = new LinkedHashSet<>();
        ShapeNotes shape = new ShapeNotes();
        if (json != null) {
            collectMissing(json, itemExists, fluidExists, unknownItems, unknownFluids, illegalIds);
            collectShape(json, shape);
        }
        StringBuilder hint = new StringBuilder();
        appendIds(hint, "非法 id", illegalIds);
        appendIds(hint, "未知物品", unknownItems);
        appendIds(hint, "未知流體", unknownFluids);
        if (rawType != null && rawType.indexOf(':') >= 0
                && recipeTypeExists != null && !recipeTypeExists.test(rawType)) {
            appendPart(hint, "未知配方類型 " + rawType);
        }
        if ("gtceu:distort".equals(rawType) && !hasEbfTemp(json)) {
            shape.lines.add("gtceu:distort 缺少 data.ebf_temp");
        }
        String mismatch = enabledIdMismatch(json, recipeId);
        if (mismatch != null) shape.lines.add(mismatch);
        String unloaded = duplicateRemovalHint(duplicateIds);
        if (unloaded == null) unloaded = "已寫入資料包，但沒有進入配方表";
        if (hint.length() == 0 && shape.lines.isEmpty()) return unloaded;
        if (hint.length() == 0) appendPart(hint, unloaded);
        int shown = 0;
        for (String line : shape.lines) {
            if (shown == 4) {
                appendPart(hint, "另有 " + (shape.lines.size() - shown) + " 條形狀問題");
                break;
            }
            appendPart(hint, line);
            shown++;
        }
        return hint.toString();
    }

    private static String explainLoadFailure(String recipeId, JsonObject json, String rawType, List<String> duplicateIds) {
        return explainLoadFailure(recipeId, json, rawType,
                DShanhaiJsonRecipeStats::itemExists,
                DShanhaiJsonRecipeStats::fluidExists,
                DShanhaiJsonRecipeStats::recipeTypeExists,
                duplicateIds);
    }

    /** 全部輸入（含 chance 為 0 的不消耗材料）和全部產物。 */
    static String consumedBody(JsonObject json) {
        if (json == null) return "";
        List<String> lines = new ArrayList<>();
        addJsonSide(lines, "in", json.get("inputs"), false);
        addJsonSide(lines, "out", json.get("outputs"), false);
        addJsonSide(lines, "tickOut", json.get("tickOutputs"), false);
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    static String duplicateRemovalHint(List<String> duplicateIds) {
        if (duplicateIds == null || duplicateIds.isEmpty()) return null;
        StringBuilder names = new StringBuilder();
        int shown = 0;
        for (String id : duplicateIds) {
            if (id == null || id.isEmpty()) continue;
            if (shown == 3) {
                names.append(" 等");
                break;
            }
            if (shown > 0) names.append('、');
            names.append(id);
            shown++;
        }
        if (shown == 0) return null;
        return "與 " + names + " 輸入（含不消耗材料）和產物相同，被當成重複配方移除";
    }

    private static String explainUnload(MinecraftServer server, String selfId, String rawType, JsonObject json) {
        List<String> exact = new ArrayList<>();
        String full = consumedBody(json);
        if (!full.isEmpty() && server != null && rawType != null) {
            GTRecipeType type = recipeType(rawType);
            if (type != null) {
                try {
                    for (Recipe<?> recipe : server.getRecipeManager().getAllRecipesFor(type)) {
                        if (!(recipe instanceof GTRecipe gt) || gt.getId() == null) continue;
                        String id = gt.getId().toString();
                        if (id.equals(selfId) || exact.contains(id) || exact.size() == 3) continue;
                        if (full.equals(consumedBody(gt))) exact.add(id);
                    }
                } catch (RuntimeException e) {
                    GTDishanhaiMod.LOGGER.debug("Unable to compare recipe body for {}: {}", selfId, e.getMessage());
                }
            }
        }
        return explainLoadFailure(selfId, json, rawType, exact);
    }

    private static GTRecipeType recipeType(String rawType) {
        ResourceLocation parsed = ResourceLocation.tryParse(rawType);
        if (parsed == null) return null;
        try {
            return GTRegistries.RECIPE_TYPES.get(parsed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String consumedBody(GTRecipe recipe) {
        List<String> lines = new ArrayList<>();
        addGtSide(lines, "in", recipe.inputs, false);
        addGtSide(lines, "out", recipe.outputs, false);
        addGtSide(lines, "tickOut", recipe.tickOutputs, false);
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    private static void addGtSide(List<String> lines, String role, Map<?, List<Content>> contents, boolean consumedOnly) {
        if (contents == null) return;
        addGtContents(lines, role, contents.get(ItemRecipeCapability.CAP), consumedOnly);
        addGtContents(lines, role, contents.get(FluidRecipeCapability.CAP), consumedOnly);
    }

    private static void addGtContents(List<String> lines, String role, List<Content> contents, boolean consumedOnly) {
        if (contents == null) return;
        for (Content content : contents) {
            if (content == null) continue;
            if (consumedOnly && content.chance == 0) continue;
            Object inner = content.content;
            if (inner instanceof com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient ingredient) {
                lines.add(role + " " + circuitAtom(ingredient) + "#c" + content.chance);
            } else if (inner instanceof Ingredient ingredient) {
                for (ItemStack stack : ingredient.getItems()) {
                    if (stack == null || stack.isEmpty()) continue;
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (id == null) continue;
                    String nbt = stack.getTag() == null ? "" : stack.getTag().toString();
                    lines.add(role + " " + stack.getCount() + "x " + id + nbt + "#c" + content.chance);
                }
            } else if (inner instanceof FluidIngredient fluid) {
                for (FluidStack stack : fluid.getStacks()) {
                    if (stack == null || stack.isEmpty()) continue;
                    ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
                    if (id == null) continue;
                    lines.add(role + " " + stack.getAmount() + "x " + id + "#c" + content.chance);
                }
            }
        }
    }

    private static String circuitAtom(com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient ingredient) {
        ItemStack[] stacks = ingredient.getItems();
        if (stacks.length > 0 && stacks[0] != null && stacks[0].getTag() != null
                && stacks[0].getTag().contains("Configuration")) {
            return "circuit " + stacks[0].getTag().getInt("Configuration");
        }
        return "circuit ?";
    }

    private static void addJsonSide(List<String> lines, String role, JsonElement side, boolean consumedOnly) {
        if (side == null || !side.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : side.getAsJsonObject().entrySet()) {
            if ("eu".equals(entry.getKey()) || !entry.getValue().isJsonArray()) continue;
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                if (element.isJsonObject()) addJsonEntry(lines, role, element.getAsJsonObject(), consumedOnly);
            }
        }
    }

    private static void addJsonEntry(List<String> lines, String role, JsonObject entry, boolean consumedOnly) {
        int chance = 10000;
        if (entry.has("chance") && entry.get("chance").isJsonPrimitive()) {
            try {
                chance = entry.get("chance").getAsInt();
            } catch (RuntimeException ignored) {
                chance = 10000;
            }
        }
        if (consumedOnly && chance == 0) return;
        if (!entry.has("content") || !entry.get("content").isJsonObject()) return;
        for (String atom : jsonAtoms(entry.getAsJsonObject("content"))) {
            lines.add(role + " " + atom + "#c" + chance);
        }
    }

    private static List<String> jsonAtoms(JsonObject content) {
        List<String> atoms = new ArrayList<>();
        long count = jsonCount(content);
        if (content.has("type") && content.get("type").isJsonPrimitive()
                && "gtceu:circuit".equals(content.get("type").getAsString())) {
            String configuration = content.has("configuration") && content.get("configuration").isJsonPrimitive()
                    ? content.get("configuration").getAsString() : "?";
            atoms.add("circuit " + configuration);
            return atoms;
        }
        if (content.has("ingredient") && content.get("ingredient").isJsonObject()) {
            addItemAtom(atoms, count, content.getAsJsonObject("ingredient"));
            return atoms;
        }
        addItemAtom(atoms, count, content);
        if (content.has("value") && content.get("value").isJsonArray()) {
            for (JsonElement value : content.getAsJsonArray("value")) {
                if (!value.isJsonObject()) continue;
                JsonObject one = value.getAsJsonObject();
                if (one.has("fluid") && one.get("fluid").isJsonPrimitive()) {
                    atoms.add(count + "x " + one.get("fluid").getAsString());
                } else if (one.has("tag") && one.get("tag").isJsonPrimitive()) {
                    atoms.add(count + "x #" + one.get("tag").getAsString());
                }
            }
        }
        return atoms;
    }

    private static void addItemAtom(List<String> atoms, long count, JsonObject ingredient) {
        if (ingredient.has("item") && ingredient.get("item").isJsonPrimitive()) {
            atoms.add(count + "x " + ingredient.get("item").getAsString());
        } else if (ingredient.has("tag") && ingredient.get("tag").isJsonPrimitive()) {
            atoms.add(count + "x #" + ingredient.get("tag").getAsString());
        } else if (ingredient.has("fluid") && ingredient.get("fluid").isJsonPrimitive()) {
            atoms.add(count + "x " + ingredient.get("fluid").getAsString());
        }
    }

    private static long jsonCount(JsonObject content) {
        for (String key : new String[] {"count", "actualAmount", "amount"}) {
            if (!content.has(key) || !content.get(key).isJsonPrimitive()) continue;
            try {
                return content.get(key).getAsLong();
            } catch (RuntimeException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private static final class ShapeNotes {
        private final List<String> bigSized = new ArrayList<>();
        private final List<String> missingLong = new ArrayList<>();
        private final List<String> missingCount = new ArrayList<>();
        private final List<String> unknownTypes = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();

        private void finish() {
            if (!bigSized.isEmpty()) {
                lines.add("萬數量用了 gtceu:sized：" + joinSamples(bigSized)
                        + "，應改 gtceu:long 的 actualAmount");
            }
            if (!missingLong.isEmpty()) {
                lines.add("gtceu:long 缺少 actualAmount：" + joinSamples(missingLong));
            }
            if (!missingCount.isEmpty()) {
                lines.add("gtceu:sized 缺少 count：" + joinSamples(missingCount));
            }
            if (!unknownTypes.isEmpty()) {
                lines.add("未知原料類型 " + joinSamples(unknownTypes)
                        + "，物品數量要用 gtceu:sized 或 gtceu:long");
            }
        }

        private static String joinSamples(List<String> values) {
            StringBuilder part = new StringBuilder();
            int shown = 0;
            for (String value : values) {
                if (shown == 3) {
                    part.append(" 等");
                    break;
                }
                if (shown > 0) part.append(", ");
                part.append(value);
                shown++;
            }
            return part.toString();
        }
    }

    private static final Set<String> KNOWN_INGREDIENT_TYPES = Set.of(
            "gtceu:sized", "gtceu:long", "gtceu:circuit", "gtceu:int_provider");
    private static final long LONG_ITEM_AMOUNT = 10000L;

    private static void collectShape(JsonElement element, ShapeNotes shape) {
        walkShape(element, shape);
        shape.finish();
    }

    private static void walkShape(JsonElement element, ShapeNotes shape) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) walkShape(child, shape);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        if (object.has("type") && object.get("type").isJsonPrimitive()) {
            noteIngredient(object, object.get("type").getAsString(), shape);
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonArray() || value.isJsonObject()) walkShape(value, shape);
        }
    }

    private static void noteIngredient(JsonObject content, String type, ShapeNotes shape) {
        boolean ingredientShape = content.has("ingredient") || content.has("count")
                || content.has("actualAmount") || content.has("configuration");
        if (!ingredientShape) return;
        String label = ingredientLabel(content);
        if ("gtceu:sized".equals(type)) {
            if (!content.has("count") || !content.get("count").isJsonPrimitive()) {
                shape.missingCount.add(label.isEmpty() ? "gtceu:sized" : label);
                return;
            }
            try {
                if (content.get("count").getAsLong() >= LONG_ITEM_AMOUNT) {
                    shape.bigSized.add("count=" + content.get("count").getAsLong()
                            + (label.isEmpty() ? "" : " " + label));
                }
            } catch (RuntimeException ignored) {
                shape.missingCount.add(label.isEmpty() ? "gtceu:sized" : label);
            }
            return;
        }
        if ("gtceu:long".equals(type)) {
            if (!content.has("actualAmount") || !content.get("actualAmount").isJsonPrimitive()) {
                shape.missingLong.add(label.isEmpty() ? "gtceu:long" : label);
            }
            return;
        }
        if (type.startsWith("gtceu:") && !KNOWN_INGREDIENT_TYPES.contains(type)) {
            shape.unknownTypes.add(type + (label.isEmpty() ? "" : " " + label));
        }
    }

    private static String ingredientLabel(JsonObject content) {
        if (!content.has("ingredient") || !content.get("ingredient").isJsonObject()) return "";
        JsonObject ingredient = content.getAsJsonObject("ingredient");
        if (ingredient.has("item") && ingredient.get("item").isJsonPrimitive()) {
            return ingredient.get("item").getAsString();
        }
        if (ingredient.has("tag") && ingredient.get("tag").isJsonPrimitive()) {
            return "#" + ingredient.get("tag").getAsString();
        }
        if (ingredient.has("type") && ingredient.get("type").isJsonPrimitive()
                && "gtceu:circuit".equals(ingredient.get("type").getAsString())) {
            String configuration = ingredient.has("configuration") && ingredient.get("configuration").isJsonPrimitive()
                    ? ingredient.get("configuration").getAsString() : "?";
            return "電路 " + configuration;
        }
        return "";
    }

    private static boolean hasEbfTemp(JsonObject json) {
        if (json == null || !json.has("data") || !json.get("data").isJsonObject()) return false;
        JsonObject data = json.getAsJsonObject("data");
        return data.has("ebf_temp") && data.get("ebf_temp").isJsonPrimitive();
    }

    private static String enabledIdMismatch(JsonObject json, String recipeId) {
        if (json == null || recipeId == null || recipeId.isEmpty()) return null;
        if (!json.has("conditions") || !json.get("conditions").isJsonArray()) return null;
        for (JsonElement element : json.getAsJsonArray("conditions")) {
            if (!element.isJsonObject()) continue;
            JsonObject condition = element.getAsJsonObject();
            if (!condition.has("type") || !condition.get("type").isJsonPrimitive()) continue;
            if (!DShanhaiRecipeEnabledCondition.ID.toString().equals(condition.get("type").getAsString())) continue;
            if (!condition.has("recipeId") || !condition.get("recipeId").isJsonPrimitive()) {
                return "recipe_enabled 缺少 recipeId，檔案 id 是 " + recipeId;
            }
            String written = condition.get("recipeId").getAsString();
            if (!recipeId.equals(written)) {
                return "recipe_enabled 寫成 " + written + "，檔案 id 是 " + recipeId;
            }
        }
        return null;
    }

    private static void collectMissing(JsonElement element,
                                       Predicate<String> itemExists,
                                       Predicate<String> fluidExists,
                                       Set<String> unknownItems,
                                       Set<String> unknownFluids,
                                       Set<String> illegalIds) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectMissing(child, itemExists, fluidExists, unknownItems, unknownFluids, illegalIds);
            }
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        takeIds(object.get("item"), itemExists, unknownItems, illegalIds);
        takeIds(object.get("fluid"), fluidExists, unknownFluids, illegalIds);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonArray() || value.isJsonObject()) {
                collectMissing(value, itemExists, fluidExists, unknownItems, unknownFluids, illegalIds);
            }
        }
    }

    private static void takeIds(JsonElement element, Predicate<String> exists, Set<String> missing, Set<String> illegalIds) {
        if (element == null || element.isJsonNull() || exists == null) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) takeIds(child, exists, missing, illegalIds);
            return;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return;
        String id = stripCountPrefix(element.getAsString());
        if (id.indexOf(':') < 0) return;
        if (!legalResourceId(id)) {
            if (illegalIds != null) illegalIds.add(id);
            return;
        }
        if (!exists.test(id)) missing.add(id);
    }

    static boolean legalResourceId(String id) {
        if (id == null) return false;
        int colon = id.indexOf(':');
        if (colon <= 0 || colon >= id.length() - 1) return false;
        return legalNamespace(id.substring(0, colon)) && legalPath(id.substring(colon + 1));
    }

    private static boolean legalNamespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-') continue;
            return false;
        }
        return true;
    }

    private static boolean legalPath(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '/') continue;
            return false;
        }
        return true;
    }

    static String stripCountPrefix(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        int marker = trimmed.indexOf('x');
        if (marker <= 0 || marker >= trimmed.length() - 2 || trimmed.charAt(marker + 1) != ' ') return trimmed;
        for (int i = 0; i < marker; i++) {
            if (!Character.isDigit(trimmed.charAt(i))) return trimmed;
        }
        return trimmed.substring(marker + 2).trim();
    }

    private static void appendIds(StringBuilder hint, String label, Set<String> ids) {
        if (ids.isEmpty()) return;
        StringBuilder part = new StringBuilder(label);
        int shown = 0;
        for (String id : ids) {
            if (shown == 4) {
                part.append(" 等");
                break;
            }
            part.append(shown == 0 ? ' ' : ',');
            if (shown > 0) part.append(' ');
            part.append(id);
            shown++;
        }
        appendPart(hint, part.toString());
    }

    private static void appendPart(StringBuilder hint, String part) {
        if (hint.length() > 0) hint.append('；');
        hint.append(part);
    }

    private static boolean itemExists(String id) {
        return registryContains(id, parsed -> ForgeRegistries.ITEMS.containsKey(parsed));
    }

    private static boolean fluidExists(String id) {
        return registryContains(id, parsed -> ForgeRegistries.FLUIDS.containsKey(parsed));
    }

    private static boolean registryContains(String id, Predicate<ResourceLocation> contains) {
        try {
            ResourceLocation parsed = ResourceLocation.tryParse(id);
            return parsed != null && contains.test(parsed);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean recipeTypeExists(String typeId) {
        if (typeId == null || !typeId.startsWith("gtceu:")) return true;
        try {
            ResourceLocation parsed = ResourceLocation.tryParse(typeId);
            return parsed != null && GTRegistries.RECIPE_TYPES.get(parsed) != null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) message = e.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }

    private static void add(Map<String, long[]> counts, String type, int status) {
        counts.computeIfAbsent(type, key -> new long[3])[status]++;
    }

    private static boolean isForeignNamespaceResource(ResourceLocation file, Resource resource) {
        if (!"gtceu".equals(file.getNamespace())) return false;
        String sourcePack = resource.sourcePackId();
        return sourcePack != null && sourcePack.contains(GTDishanhaiMod.MOD_ID);
    }

    /**
     * GTCEu recipes are indexed by GTRecipeType lookup trees.  KubeJS may warn while
     * trying to reinterpret their native fluid JSON, even though GTCEu loaded them
     * successfully into this lookup.  Use that authoritative index as a fallback.
     */
    private static boolean isLoadedInGtRecipeLookup(ResourceLocation recipeId, String typeId) {
        if (typeId == null || typeId.indexOf(':') < 0) return false;
        try {
            GTRecipeType type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(typeId));
            if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) return false;
            return type.getLookup().getLookup().getRecipes(false)
                    .anyMatch(recipe -> recipeId.equals(recipe.getId()));
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.debug("Unable to inspect GTCEu recipe lookup for {}: {}", recipeId, e.getMessage());
            return false;
        }
    }

    static boolean isDisabled(JsonObject json, JsonObject config) {
        if (!json.has("conditions") || !json.get("conditions").isJsonArray()) return false;
        for (JsonElement element : json.getAsJsonArray("conditions")) {
            if (!element.isJsonObject()) continue;
            JsonObject condition = element.getAsJsonObject();
            if (!condition.has("type")) continue;
            String conditionType = condition.get("type").getAsString();
            if ("forge:mod_loaded".equals(conditionType) && condition.has("modid")
                    && !ModList.get().isLoaded(condition.get("modid").getAsString())) return true;
            if (!DShanhaiRecipeEnabledCondition.ID.toString().equals(conditionType)) continue;
            try {
                if (!DShanhaiRecipeEnabledCondition.Serializer.INSTANCE.read(condition).isEnabled(config)) return true;
            } catch (Exception e) {
                GTDishanhaiMod.LOGGER.warn("Invalid ShanHai recipe_enabled condition: {}", condition, e);
            }
        }
        return false;
    }
}
