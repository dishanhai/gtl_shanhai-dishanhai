package com.dishanhai.gt_shanhai.common.debug;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.fluids.store.FluidStorageKey;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.utils.FormattingUtil;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeTypeNames;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 開發者用 Markdown 匯出：GT 相關物品/流體清單與配方清單。
 *
 * <p>只在 {@code developer.exportContents} / {@code developer.exportRecipes}
 * 開啟時，於伺服器啟動完成後執行。輸出落在 {@code config/gt_shanhai/dev-export/}，
 * 依模組分組、組內依 ID 字母排序；物品/流體表格含中文名、英文 ID 與前兩個標籤。</p>
 */
public final class DevContentExport {

    private static final Logger LOG = LogManager.getLogger("gt_shanhai-dev-export");

    /** 只匯出這些命名空間的物品與流體。 */
    private static final Set<String> TARGET_NAMESPACES = new LinkedHashSet<>(List.of(
            "gtceu", "kubejs", "gtlcore", "gtladditions", "gt_shanhai", "dishanhai"));

    /** {@code namespace -> (langKey -> 文字)}。伺服器端語言表不完整，因此直接讀 jar 內的中文 lang。 */
    private static final Map<String, Map<String, String>> ZH_CACHE = new ConcurrentHashMap<>();

    /** 語言優先序：繁體 → 簡體。 */
    private static final String[] LANG_FILES = {"zh_tw", "zh_cn"};

    private DevContentExport() {}

    /**
     * 匯出物品/流體清單與配方清單。
     *
     * @param outputDir 輸出目錄（{@code config/gt_shanhai/dev-export}）
     * @param contents  是否匯出物品/流體清單
     * @param recipes   是否匯出配方清單
     */
    public static void run(Path outputDir, boolean contents, boolean recipes) {
        if (!contents && !recipes) return;
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOG.warn("[dev-export] 建立输出目录失败 {}: {}", outputDir, e.getMessage());
            return;
        }
        if (contents) {
            writeSafely(outputDir.resolve("gt-contents.md"), DevContentExport::buildContentMarkdown);
        }
        if (recipes) {
            writeSafely(outputDir.resolve("gt-recipes.md"), DevContentExport::buildRecipeMarkdown);
        }
    }

    private interface MarkdownSupplier {
        String get();
    }

    private static void writeSafely(Path file, MarkdownSupplier supplier) {
        try {
            String markdown = supplier.get();
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            LOG.info("[dev-export] 已写入 {} ({} 字元)", file, markdown.length());
        } catch (Exception e) {
            LOG.warn("[dev-export] 汇出 {} 失败: {}", file.getFileName(), e.toString());
        }
    }

    // ====== 物品 / 流體 ======

    private static String buildContentMarkdown() {
        Map<String, List<ItemRow>> items = new TreeMap<>();
        Map<String, List<FluidRow>> fluids = new TreeMap<>();

        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null || !TARGET_NAMESPACES.contains(id.getNamespace())) continue;
            ItemStack stack = new ItemStack(item);
            items.computeIfAbsent(id.getNamespace(), k -> new ArrayList<>())
                    .add(new ItemRow(id, safeItemName(id, stack), tagsOf(stack)));
        }

        for (Fluid fluid : ForgeRegistries.FLUIDS) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
            if (id == null || !TARGET_NAMESPACES.contains(id.getNamespace())) continue;
            fluids.computeIfAbsent(id.getNamespace(), k -> new ArrayList<>())
                    .add(new FluidRow(id, safeFluidName(id, fluid), tagsOf(fluid)));
        }

        return renderContents(items, fluids);
    }

    /** 純格式化：輸入已收集的分組資料，輸出 Markdown。可在無 Forge 註冊表的測試中直接呼叫。 */
    static String renderContents(Map<String, List<ItemRow>> items, Map<String, List<FluidRow>> fluids) {
        int itemTotal = total(items);
        int fluidTotal = total(fluids);

        StringBuilder out = new StringBuilder(128 * 1024);
        out.append("# GT 物品与流体 ID 清单\n\n");
        out.append("> 由 gt_shanhai 开发者选项汇出；分组为模组命名空间，组内按 ID 字母排序。\n");
        out.append("> 物品 ").append(itemTotal).append(" 项，流体 ").append(fluidTotal).append(" 项。\n\n");

        out.append("## 物品\n\n");
        for (Map.Entry<String, List<ItemRow>> group : items.entrySet()) {
            List<ItemRow> rows = group.getValue();
            rows.sort(Comparator.comparing(row -> row.id.toString()));
            out.append("### ").append(group.getKey()).append("（").append(rows.size()).append("）\n\n");
            out.append("| 中文名 | 英文 ID | 标签 1 | 标签 2 |\n");
            out.append("| --- | --- | --- | --- |\n");
            for (ItemRow row : rows) {
                out.append("| ").append(cell(row.name)).append(" | ").append(row.id).append(" | ")
                        .append(cell(row.tag1())).append(" | ").append(cell(row.tag2())).append(" |\n");
            }
            out.append('\n');
        }

        out.append("## 流体\n\n");
        for (Map.Entry<String, List<FluidRow>> group : fluids.entrySet()) {
            List<FluidRow> rows = group.getValue();
            rows.sort(Comparator.comparing(row -> row.id.toString()));
            out.append("### ").append(group.getKey()).append("（").append(rows.size()).append("）\n\n");
            out.append("| 中文名 | 英文 ID | 标签 1 | 标签 2 |\n");
            out.append("| --- | --- | --- | --- |\n");
            for (FluidRow row : rows) {
                out.append("| ").append(cell(row.name)).append(" | ").append(row.id).append(" | ")
                        .append(cell(row.tag1())).append(" | ").append(cell(row.tag2())).append(" |\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static int total(Map<String, ? extends List<?>> groups) {
        int total = 0;
        for (List<?> rows : groups.values()) total += rows.size();
        return total;
    }

    static final class ItemRow {
        final ResourceLocation id;
        final String name;
        final List<String> tags;

        ItemRow(ResourceLocation id, String name, List<String> tags) {
            this.id = id;
            this.name = name;
            this.tags = tags;
        }

        String tag1() {
            return tags.isEmpty() ? "" : tags.get(0);
        }

        String tag2() {
            return tags.size() < 2 ? "" : tags.get(1);
        }
    }

    static final class FluidRow {
        final ResourceLocation id;
        final String name;
        final List<String> tags;

        FluidRow(ResourceLocation id, String name, List<String> tags) {
            this.id = id;
            this.name = name;
            this.tags = tags;
        }

        String tag1() {
            return tags.isEmpty() ? "" : tags.get(0);
        }

        String tag2() {
            return tags.size() < 2 ? "" : tags.get(1);
        }
    }

    /**
     * 物品名。GT 的 {@code TagPrefixItem} 走 {@code tagprefix.*} + {@code material.*} 組合鍵，
     * 不可能靠單一路徑查表得到，因此優先採用執行時翻譯（含 GT 自帶的動態名稱解析）。
     */
    private static String safeItemName(ResourceLocation id, ItemStack stack) {
        String materialName = gtMaterialItemName(stack);
        if (!materialName.isEmpty()) return materialName;
        try {
            String live = stripStyle(stack.getHoverName().getString());
            if (!live.isBlank() && !live.equals(defaultItemName(id))) return live;
        } catch (Exception ignored) {
        }
        String fromLang = lookupLang(id.getNamespace(),
                "item." + id.getNamespace() + "." + id.getPath(),
                "block." + id.getNamespace() + "." + id.getPath());
        if (!fromLang.isEmpty()) return fromLang;
        String fallback = materialFallback(id);
        if (!fallback.isEmpty()) return fallback;
        try {
            return stripStyle(stack.getHoverName().getString());
        } catch (Exception e) {
            return "";
        }
    }

    /** GT 材料物品：{@code tagprefix.<prefix>} 模板 + {@code material.<modid>.<name>}。 */
    private static String gtMaterialItemName(ItemStack stack) {
        try {
            var entry = ChemicalHelper.getUnificationEntry(stack.getItem());
            if (entry == null || entry.tagPrefix == null || entry.material == null) return "";
            return gtPrefixedName(entry.tagPrefix, entry.material);
        } catch (Exception e) {
            return "";
        }
    }

    private static String gtPrefixedName(TagPrefix prefix, Material material) {
        String prefixKey = "tagprefix." + FormattingUtil.toLowerCaseUnderscore(prefix.name);
        String materialName = lookupLang(material.getModid(),
                "material." + material.getModid() + "." + material.getName());
        String prefixText = lookupLang("gtceu", prefixKey);
        if (materialName.isEmpty() || prefixText.isEmpty()) return "";
        return prefixText.replace("%s", materialName);
    }

    private static String defaultItemName(ResourceLocation id) {
        return id.getPath().replace('_', ' ');
    }

    /**
     * GT 的 {@code TagPrefixItem} 名稱由「形狀前綴 + 材料名」兩個鍵拼成，
     * 例如 {@code tagprefix.ingot} + {@code material.gtceu.iron} → 「鐵錠」。
     * 執行期翻譯不可用時，依 {@code gtceu:iron_ingot} 反推同樣的結果。
     */
    private static String materialFallback(ResourceLocation id) {
        if (!"gtceu".equals(id.getNamespace())) return "";
        String path = id.getPath();
        int split = path.lastIndexOf('_');
        while (split > 0) {
            String materialId = path.substring(0, split);
            String prefixId = path.substring(split + 1);
            String material = lookupLang("gtceu", "material.gtceu." + materialId);
            String prefix = lookupLang("gtceu", "tagprefix." + prefixId);
            if (!material.isEmpty() && !prefix.isEmpty()) {
                return prefix.replace("%s", material);
            }
            split = path.lastIndexOf('_', split - 1);
        }
        return "";
    }

    private static String safeFluidName(ResourceLocation id, Fluid fluid) {
        String materialName = gtFluidName(id, fluid);
        if (!materialName.isEmpty()) return materialName;
        try {
            String live = stripStyle(fluid.getFluidType().getDescription().getString());
            if (!live.isBlank() && !live.equals(defaultItemName(id))) return live;
        } catch (Exception ignored) {
        }
        try {
            String live = stripStyle(new net.minecraftforge.fluids.FluidStack(fluid, 1000)
                    .getDisplayName().getString());
            if (!live.isBlank() && !live.equals(defaultItemName(id))) return live;
        } catch (Exception ignored) {
        }
        return lookupLang(id.getNamespace(),
                "fluid." + id.getNamespace() + "." + id.getPath(),
                "fluid_type." + id.getNamespace() + "." + id.getPath());
    }

    /**
     * GT 材料流體：由註冊名反推 {@link FluidStorageKey}，
     * 再用 {@code gtceu.fluid.*} 模板 + 材料中文名組合，避免伺服器端只有英文。
     */
    private static String gtFluidName(ResourceLocation id, Fluid fluid) {
        try {
            Material material = ChemicalHelper.getMaterial(fluid);
            if (material == null) return "";
            for (FluidStorageKey key : FluidStorageKey.allKeys()) {
                if (!key.getRegistryNameFor(material).equals(id.getPath())) continue;
                String format = lookupLang("gtceu", key.getTranslationKeyFor(material));
                String materialName = lookupLang(material.getModid(),
                        "material." + material.getModid() + "." + material.getName());
                if (format.isEmpty() || materialName.isEmpty()) continue;
                return format.replace("%s", materialName);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String lookupLang(String namespace, String... keys) {
        Map<String, String> values = ZH_CACHE.computeIfAbsent(namespace, DevContentExport::loadZh);
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return stripStyle(value);
        }
        return "";
    }

    private static Map<String, String> loadZh(String namespace) {
        for (String lang : LANG_FILES) {
            String resource = "assets/" + namespace + "/lang/" + lang + ".json";
            try (InputStream stream = DevContentExport.class.getClassLoader().getResourceAsStream(resource)) {
                if (stream == null) continue;
                JsonObject root = JsonParser.parseReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, String> values = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        values.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                return values;
            } catch (Exception ignored) {
            }
        }
        return Map.of();
    }

    private static List<String> tagsOf(ItemStack stack) {
        List<String> result = new ArrayList<>(2);
        try {
            stack.getTags()
                    .map(tag -> tag.location().toString())
                    .sorted()
                    .limit(2)
                    .forEach(result::add);
        } catch (Exception ignored) {
        }
        return result;
    }

    private static List<String> tagsOf(Fluid fluid) {
        List<String> result = new ArrayList<>(2);
        try {
            fluid.defaultFluidState().getTags()
                    .map(tag -> tag.location().toString())
                    .sorted()
                    .limit(2)
                    .forEach(result::add);
        } catch (Exception ignored) {
        }
        return result;
    }

    // ====== 配方 ======

    private static String buildRecipeMarkdown() {
        Map<String, List<RecipeRow>> groups = new TreeMap<>();
        // 通用/代理配方類型會共用同一份 lookup，依 (類型, 配方) 去重避免同一筆重複列出。
        Set<String> seen = new java.util.HashSet<>();
        int total = 0;

        for (GTRecipeType type : GTRegistries.RECIPE_TYPES) {
            if (type == null || type.registryName == null) continue;
            var lookup = type.getLookup();
            if (lookup == null || lookup.getLookup() == null) continue;
            String typeId = type.registryName.toString();
            try {
                for (GTRecipe recipe : lookup.getLookup().getRecipes(true).toList()) {
                    if (recipe == null || recipe.getId() == null) continue;
                    if (!seen.add(typeId + "|" + recipe.getId())) continue;
                    RecipeRow row = new RecipeRow(recipe.getId(), typeId, recipe);
                    groups.computeIfAbsent(recipe.getId().getNamespace(), k -> new ArrayList<>()).add(row);
                    total++;
                }
            } catch (Exception e) {
                LOG.warn("[dev-export] 遍历配方类型 {} 失败: {}", typeId, e.getMessage());
            }
        }

        return renderRecipes(groups, total);
    }

    /** 純格式化：輸入已收集的配方分組，輸出 Markdown。 */
    static String renderRecipes(Map<String, List<RecipeRow>> groups, int total) {
        StringBuilder out = new StringBuilder(512 * 1024);
        out.append("# GT 配方清单\n\n");
        out.append("> 由 gt_shanhai 开发者选项汇出；分组为配方 ID 的命名空间，组内按配方 ID 字母排序。\n");
        out.append("> 共 ").append(total).append(" 个配方。每栏只列前两项输入/输出，物品与流体合并计数。\n\n");

        for (Map.Entry<String, List<RecipeRow>> group : groups.entrySet()) {
            List<RecipeRow> rows = group.getValue();
            rows.sort(Comparator.comparing(row -> row.recipeId.toString()));
            out.append("### ").append(group.getKey()).append("（").append(rows.size()).append("）\n\n");
            out.append("| 配方 ID | 机器类型 | 输入 1 | 输入 2 | 输出 1 | 输出 2 |\n");
            out.append("| --- | --- | --- | --- | --- | --- |\n");
            for (RecipeRow row : rows) {
                out.append("| ").append(row.recipeId).append(" | ").append(cell(row.typeId)).append(" | ")
                        .append(cell(row.input(0))).append(" | ").append(cell(row.input(1))).append(" | ")
                        .append(cell(row.output(0))).append(" | ").append(cell(row.output(1))).append(" |\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    static final class RecipeRow {
        final ResourceLocation recipeId;
        final String typeId;
        final List<String> inputs;
        final List<String> outputs;

        RecipeRow(ResourceLocation recipeId, String typeId, GTRecipe recipe) {
            this.recipeId = recipeId;
            this.typeId = ShanhaiRecipeTypeNames.display(recipeIdOfType(typeId));
            this.inputs = flatten(recipe.inputs);
            this.outputs = flatten(recipe.outputs);
        }

        /** 測試用：直接給定已展開的輸入輸出。 */
        RecipeRow(ResourceLocation recipeId, String typeId, List<String> inputs, List<String> outputs) {
            this.recipeId = recipeId;
            this.typeId = typeId;
            this.inputs = inputs;
            this.outputs = outputs;
        }

        private static ResourceLocation recipeIdOfType(String typeId) {
            try {
                return new ResourceLocation(typeId);
            } catch (Exception e) {
                return null;
            }
        }

        String input(int index) {
            return index < inputs.size() ? inputs.get(index) : "";
        }

        String output(int index) {
            return index < outputs.size() ? outputs.get(index) : "";
        }

        private static List<String> flatten(Map<RecipeCapability<?>, List<Content>> contents) {
            List<String> result = new ArrayList<>();
            if (contents == null) return result;
            appendItems(result, contents.get(ItemRecipeCapability.CAP));
            appendFluids(result, contents.get(FluidRecipeCapability.CAP));
            return result;
        }

        private static void appendItems(List<String> result, List<Content> list) {
            if (list == null) return;
            for (Content content : list) {
                if (content == null || content.content == null) continue;
                if (content.content instanceof SizedIngredient sized) {
                    ItemStack[] stacks = sized.getItems();
                    if (stacks.length > 0) result.add(itemLabel(stacks[0], sized.getAmount()));
                } else if (content.content instanceof ItemStack stack) {
                    result.add(itemLabel(stack, stack.getCount()));
                } else if (content.content instanceof Ingredient ingredient) {
                    ItemStack[] stacks = ingredient.getItems();
                    if (stacks.length > 0) result.add(itemLabel(stacks[0], stacks[0].getCount()));
                }
            }
        }

        private static void appendFluids(List<String> result, List<Content> list) {
            if (list == null) return;
            for (Content content : list) {
                if (content == null || content.content == null) continue;
                if (content.content instanceof FluidIngredient ingredient) {
                    FluidStack[] stacks = ingredient.getStacks();
                    if (stacks.length > 0) {
                        result.add(fluidLabel(stacks[0].getFluid(), ingredient.getAmount()));
                    }
                } else if (content.content instanceof FluidStack stack) {
                    result.add(fluidLabel(stack.getFluid(), stack.getAmount()));
                } else if (content.content instanceof net.minecraftforge.fluids.FluidStack stack) {
                    ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
                    result.add(stack.getAmount() + "mb " + (id != null ? id : "unknown"));
                }
            }
        }

        private static String itemLabel(ItemStack stack, int count) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id == null) return Math.max(count, 0) + "x unknown";
            String name = lookupLang(id.getNamespace(),
                    "item." + id.getNamespace() + "." + id.getPath(),
                    "block." + id.getNamespace() + "." + id.getPath());
            return Math.max(count, 0) + "x " + id + (name.isEmpty() ? "" : "（" + name + "）");
        }

        private static String fluidLabel(Fluid fluid, long amount) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
            if (id == null) return amount + "mb unknown";
            String name = lookupLang(id.getNamespace(),
                    "fluid." + id.getNamespace() + "." + id.getPath(),
                    "fluid_type." + id.getNamespace() + "." + id.getPath());
            return amount + "mb " + id + (name.isEmpty() ? "" : "（" + name + "）");
        }
    }

    // ====== 共用 ======

    private static String cell(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private static String stripStyle(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\u00a7' && i + 1 < value.length()) {
                i++;
                continue;
            }
            result.append(current);
        }
        return result.toString().trim();
    }
}
