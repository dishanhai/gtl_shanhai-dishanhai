package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 山海配方的重複警告。只報告，不刪配方，也不寫入配方錯誤表。
 * 同一 ID 的後載入資料包蓋掉先載入的；{@code getResourceStack} 的第一份就是留下的那份。
 */
public final class DShanhaiDuplicateRecipeWarnings {
    private static final Logger LOG = LogManager.getLogger();
    public static final String CACHE_PACK = DShanhaiRecipePackFinder.PACK_ID;

    public record Warning(String recipeId, String hint) {
        public String line() {
            return recipeId + " | " + hint;
        }
    }

    public record PackGroup(String recipeId, List<String> packsHighestFirst) {}

    public record PrintedRecipe(String recipeId, String fingerprint) {}

    private static final List<Warning> REPEATED_REGISTRATIONS = Collections.synchronizedList(new ArrayList<>());
    private static volatile List<Warning> scanCache;

    private DShanhaiDuplicateRecipeWarnings() {}

    public static void clear() {
        REPEATED_REGISTRATIONS.clear();
        scanCache = null;
    }

    public static void noteRepeatedRegistration(String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) return;
        Warning warning = new Warning(recipeId, "KJS 再次註冊，後寫的覆蓋先寫的");
        REPEATED_REGISTRATIONS.add(warning);
        LOG.warn("[DRE] 配方重複: {}", warning.line());
    }

    public static List<Warning> repeatedRegistrations() {
        synchronized (REPEATED_REGISTRATIONS) {
            return List.copyOf(REPEATED_REGISTRATIONS);
        }
    }

    /** 資料包棧第一份留下，後面的被蓋掉。不足兩份時沒有衝突。 */
    public static Warning packShadow(String recipeId, List<String> packsHighestFirst) {
        if (recipeId == null || recipeId.isEmpty() || packsHighestFirst == null || packsHighestFirst.size() < 2) {
            return null;
        }
        return new Warning(recipeId, "保留 " + packsHighestFirst.get(0) + " | 被蓋掉 " + join(packsHighestFirst, 1));
    }

    /**
     * KJS 在資料包之後寫入。快取包是腳本自己的導出，不算被蓋掉的另一份來源。
     */
    public static Warning kjsShadowsPacks(String recipeId, List<String> packsHighestFirst) {
        if (recipeId == null || recipeId.isEmpty() || packsHighestFirst == null || packsHighestFirst.isEmpty()) {
            return null;
        }
        List<String> others = new ArrayList<>();
        for (String pack : packsHighestFirst) {
            if (pack != null && !pack.isEmpty() && !CACHE_PACK.equals(pack) && !others.contains(pack)) {
                others.add(pack);
            }
        }
        if (others.isEmpty()) return null;
        return new Warning(recipeId, "保留 KJS | 被蓋掉 " + String.join(", ", others));
    }

    public static String fingerprint(String type, int duration, List<String> lines) {
        List<String> sorted = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                if (line != null && !line.isEmpty()) sorted.add(line);
            }
        }
        Collections.sort(sorted);
        return (type == null ? "" : type) + "\n" + duration + "\n" + String.join("\n", sorted);
    }

    public static List<Warning> contentDuplicates(List<PrintedRecipe> recipes) {
        if (recipes == null || recipes.isEmpty()) return List.of();
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (PrintedRecipe recipe : recipes) {
            if (recipe == null || recipe.recipeId() == null || recipe.recipeId().isEmpty()) continue;
            if (recipe.fingerprint() == null || recipe.fingerprint().isEmpty()) continue;
            grouped.computeIfAbsent(recipe.fingerprint(), key -> new ArrayList<>()).add(recipe.recipeId());
        }
        List<Warning> warnings = new ArrayList<>();
        for (List<String> ids : grouped.values()) {
            if (ids.size() < 2) continue;
            List<String> sorted = new ArrayList<>(ids);
            Collections.sort(sorted);
            warnings.add(new Warning(sorted.get(0), "與 " + String.join(", ", sorted.subList(1, sorted.size())) + " 內容相同"));
        }
        return warnings;
    }

    public static List<Warning> assemble(List<PackGroup> groups, Set<String> kjsIds, boolean cacheHit,
                                         List<PrintedRecipe> loaded) {
        List<Warning> warnings = new ArrayList<>();
        Set<String> registered = kjsIds == null ? Set.of() : kjsIds;
        if (groups != null) {
            for (PackGroup group : groups) {
                if (group == null) continue;
                Warning shadow = packShadow(group.recipeId(), group.packsHighestFirst());
                if (shadow != null) warnings.add(shadow);
                if (!cacheHit && registered.contains(group.recipeId())) {
                    Warning kjs = kjsShadowsPacks(group.recipeId(), group.packsHighestFirst());
                    if (kjs != null) warnings.add(kjs);
                }
            }
        }
        warnings.addAll(contentDuplicates(loaded));
        return warnings;
    }

    public static List<String> warningLines(MinecraftServer server, Set<String> kjsIds, boolean cacheHit) {
        List<Warning> warnings = new ArrayList<>(repeatedRegistrations());
        warnings.addAll(scan(server, kjsIds, cacheHit));
        List<String> lines = new ArrayList<>(warnings.size());
        for (Warning warning : warnings) lines.add(warning.line());
        return lines;
    }

    private static List<Warning> scan(MinecraftServer server, Set<String> kjsIds, boolean cacheHit) {
        if (server == null) return List.of();
        List<Warning> cached = scanCache;
        if (cached != null) return cached;
        List<Warning> scanned;
        try {
            scanned = scanServer(server, kjsIds, cacheHit);
        } catch (RuntimeException e) {
            LOG.warn("[DRE] 配方重複掃描失敗: {}", e.toString());
            scanned = List.of();
        }
        scanCache = scanned;
        for (Warning warning : scanned) {
            LOG.warn("[DRE] 配方重複: {}", warning.line());
        }
        return scanned;
    }

    private static List<Warning> scanServer(MinecraftServer server, Set<String> kjsIds, boolean cacheHit) {
        if (server == null) return List.of();
        List<PackGroup> groups = new ArrayList<>();
        List<PrintedRecipe> loaded = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map.Entry<ResourceLocation, Resource> entry : server.getResourceManager()
                .listResources("recipes", id -> id.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation file = entry.getKey();
            if (!isShanhaiFile(file, entry.getValue(), cacheHit || cachePackPresent(entry.getValue()))) continue;
            String recipeId = recipeIdOf(file);
            if (recipeId == null || !seen.add(recipeId)) continue;
            List<String> packs = packIds(server, file);
            groups.add(new PackGroup(recipeId, packs));
            PrintedRecipe printed = printLoaded(server, recipeId);
            if (printed != null) loaded.add(printed);
        }
        if (!cacheHit && kjsIds != null) {
            for (String recipeId : kjsIds) {
                if (recipeId == null || recipeId.isEmpty() || !seen.add(recipeId)) continue;
                PrintedRecipe printed = printLoaded(server, recipeId);
                if (printed != null) loaded.add(printed);
            }
        }
        return assemble(groups, cacheHit ? Set.of() : kjsIds, cacheHit, loaded);
    }

    private static boolean cachePackPresent(Resource resource) {
        return resource != null && CACHE_PACK.equals(resource.sourcePackId());
    }

    private static boolean isShanhaiFile(ResourceLocation file, Resource resource, boolean includeCache) {
        if (file == null) return false;
        if ("gt_shanhai".equals(file.getNamespace()) || "dishanhai".equals(file.getNamespace())) return true;
        String pack = resource == null ? null : resource.sourcePackId();
        if (includeCache && CACHE_PACK.equals(pack)) return true;
        return "gtceu".equals(file.getNamespace()) && pack != null && pack.contains(GTDishanhaiMod.MOD_ID);
    }

    private static String recipeIdOf(ResourceLocation file) {
        String path = file.getPath();
        if (!path.startsWith("recipes/") || !path.endsWith(".json")) return null;
        return file.getNamespace() + ":" + path.substring("recipes/".length(), path.length() - ".json".length());
    }

    private static List<String> packIds(MinecraftServer server, ResourceLocation file) {
        List<String> packs = new ArrayList<>();
        for (Resource resource : server.getResourceManager().getResourceStack(file)) {
            String pack = resource.sourcePackId();
            if (pack != null && !pack.isEmpty() && !packs.contains(pack)) packs.add(pack);
        }
        return packs;
    }

    private static PrintedRecipe printLoaded(MinecraftServer server, String recipeId) {
        ResourceLocation parsed = ResourceLocation.tryParse(recipeId);
        if (parsed == null) return null;
        Recipe<?> recipe = server.getRecipeManager().byKey(parsed).orElse(null);
        if (!(recipe instanceof GTRecipe gt)) return null;
        return new PrintedRecipe(recipeId, fingerprint(typeId(gt), gt.duration, linesOf(gt)));
    }

    private static String typeId(GTRecipe recipe) {
        if (recipe.recipeType == null || recipe.recipeType.registryName == null) return "";
        return recipe.recipeType.registryName.toString();
    }

    static List<String> linesOf(GTRecipe recipe) {
        List<String> lines = new ArrayList<>();
        addContents(lines, "in", recipe.inputs);
        addContents(lines, "out", recipe.outputs);
        addContents(lines, "tickIn", recipe.tickInputs);
        addContents(lines, "tickOut", recipe.tickOutputs);
        addChances(lines, "chanceIn", recipe.inputChanceLogics);
        addChances(lines, "chanceOut", recipe.outputChanceLogics);
        addChances(lines, "chanceTickIn", recipe.tickInputChanceLogics);
        addChances(lines, "chanceTickOut", recipe.tickOutputChanceLogics);
        if (recipe.conditions != null) {
            for (RecipeCondition condition : recipe.conditions) {
                if (condition == null) continue;
                lines.add("cond " + condition.getType() + " " + condition.serialize());
            }
        }
        if (recipe.isFuel) lines.add("fuel");
        return lines;
    }

    private static void addContents(List<String> lines, String role, Map<?, List<Content>> contents) {
        if (contents == null) return;
        for (Map.Entry<?, List<Content>> entry : contents.entrySet()) {
            if (entry.getValue() == null) continue;
            for (Content content : entry.getValue()) {
                if (content == null) continue;
                lines.add(role + " " + entry.getKey() + " " + describeContent(content));
            }
        }
    }

    private static void addChances(List<String> lines, String role, Map<?, ?> logics) {
        if (logics == null || logics.isEmpty()) return;
        for (Map.Entry<?, ?> entry : logics.entrySet()) {
            lines.add(role + " " + entry.getKey() + "=" + entry.getValue());
        }
    }

    static String describeContent(Content content) {
        String body;
        try {
            body = describeBody(content.content);
        } catch (RuntimeException e) {
            body = String.valueOf(content.content);
        }
        return body
                + "#c" + content.chance + "/" + content.maxChance
                + "#b" + content.tierChanceBoost
                + (content.slotName == null ? "" : "#s" + content.slotName);
    }

    private static String describeBody(Object content) {
        if (content instanceof Ingredient ingredient) {
            List<String> parts = new ArrayList<>();
            for (ItemStack stack : ingredient.getItems()) {
                if (stack == null || stack.isEmpty()) continue;
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                String nbt = stack.getTag() == null ? "" : stack.getTag().toString();
                parts.add(stack.getCount() + "x " + id + nbt);
            }
            Collections.sort(parts);
            return String.join(",", parts);
        }
        if (content instanceof FluidIngredient fluid) {
            List<String> parts = new ArrayList<>();
            for (FluidStack stack : fluid.getStacks()) {
                if (stack == null || stack.isEmpty()) continue;
                ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
                String nbt = stack.getTag() == null ? "" : stack.getTag().toString();
                parts.add(stack.getAmount() + "x " + id + nbt);
            }
            Collections.sort(parts);
            return String.join(",", parts);
        }
        return String.valueOf(content);
    }

    private static String join(List<String> values, int from) {
        return String.join(", ", values.subList(from, values.size()));
    }
}
