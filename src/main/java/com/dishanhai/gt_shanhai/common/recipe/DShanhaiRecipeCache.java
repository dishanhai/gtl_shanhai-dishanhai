package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeEngine;
import com.dishanhai.gt_shanhai.common.misc.RecipeManagerReflectionUtil;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.fml.loading.FMLPaths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * "Recipe library cache" -- 山海的配方库.js stays untouched. First boot (or whenever the source file /
 * gtlcore config changes) runs Rhino registration as normal; afterwards every recipe DShanhaiRecipeEngine
 * tracked this run gets pulled back out of RecipeManager and encoded via GTRecipeSerializer.CODEC into
 * standard datapack json on disk. On a cache hit, 山海的配方库.js returns early and the json is loaded
 * by DShanhaiRecipePackFinder as a normal always-on datapack -- pure vanilla loading, no reflection.
 */
public final class DShanhaiRecipeCache {
    private static final Logger LOG = LoggerFactory.getLogger("DShanhaiRecipeCache");

    private static final Path EXPORT_ROOT = FMLPaths.GAMEDIR.get().resolve("dishanhai_recipe_cache");
    static final Path DATA_ROOT = EXPORT_ROOT.resolve("data");
    private static final Path MANIFEST_FILE = EXPORT_ROOT.resolve("manifest.json");
    private static final Path RECIPE_LIB_JS = FMLPaths.GAMEDIR.get().resolve("kubejs/server_scripts/山海的配方库.js");
    private static final Path GTLCORE_CONFIG = FMLPaths.GAMEDIR.get().resolve("config/gtlcore.yaml");

    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Boolean cacheValid;
    private static long cachedRecipeCount;

    private static final Map<String, GTRecipeType> TYPE_CACHE = new java.util.HashMap<>();
    private static java.util.Set<GTRecipeType> ownedTypesCache;

    // 匹配 gtr.<type>('<id>') / gtr.<type>("<id>")——源码里真正调用注册的确切类型和 id，
    // 写错了配方注册会直接失败，所以这个模式本身就是权威真值，供导出 Pass 3 兜底扫描用。
    private static final java.util.regex.Pattern GTR_CALL_PATTERN =
            java.util.regex.Pattern.compile("gtr\\.([a-zA-Z_][a-zA-Z0-9_]*)\\(\\s*(['\"])([^'\"]+)\\2");

    private DShanhaiRecipeCache() {}

    /** 启动期 KJS 配方库磁盘缓存总开关；配置未加载时按关闭处理。 */
    public static boolean isEnabled() {
        return DShanhaiConfig.COMMON_SPEC.isLoaded()
                && DShanhaiConfig.COMMON.kjsRecipeLibraryCacheEnabled.get();
    }

    /**
     * 山海自己在 Java 侧注册的全部自定义 GTRecipeType（反射读 DShanhaiRecipeTypes 里所有
     * public static GTRecipeType / GTRecipeType[] 字段）。这些类型从定义源头就只属于 gt_shanhai
     * 自己（不管注册表命名空间因为 GTCEu 内部帮助方法固定用 gtceu:，实际归属看的是"谁在 Java 里
     * new 出来的"），可以不管 KubeJS 那边有没有追踪到、声明的 id 对不对，直接整桶导出——
     * 从根上解决"裸调用 gtr.xxx() 不走 safeAddRecipe" / "声明 id 和实际 id 对不上" 这两类问题，
     * 不用每次手动排查漏了哪条。真正跨 mod 共用的类型（element_copying 之类）不在这份清单里，
     * 仍然走下面精确按 id 重建那条路径。
     */
    /** 公开供 DShanhaiRecipeEngine.getRecipeTypeCountsLive() 复用，避免重复反射同一份类型清单。 */
    public static java.util.Set<GTRecipeType> ownedRecipeTypes() {
        if (ownedTypesCache != null) return ownedTypesCache;
        java.util.Set<GTRecipeType> set = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        try {
            for (java.lang.reflect.Field f : com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes.class.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Object value = f.get(null);
                if (value instanceof GTRecipeType single) {
                    set.add(single);
                } else if (value instanceof GTRecipeType[] array) {
                    for (GTRecipeType t : array) if (t != null) set.add(t);
                }
            }
        } catch (Exception e) {
            LOG.warn("[DShanhaiRecipeCache] failed to reflect DShanhaiRecipeTypes fields, owned-type bulk export disabled this run", e);
        }
        ownedTypesCache = set;
        return set;
    }

    // ==================== recipe type resolution (moved from the now-deleted DShanhaiJavaRecipeRegistrar) ====================

    private static GTRecipeType resolveRecipeType(String bareNameOrId) {
        return TYPE_CACHE.computeIfAbsent(bareNameOrId, key -> {
            if (key.indexOf(':') >= 0) {
                GTRecipeType direct = com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES.get(new ResourceLocation(key));
                if (direct != null) return direct;
            }
            for (Map.Entry<ResourceLocation, GTRecipeType> e : com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES.entries()) {
                if (e.getKey().getPath().equals(key)) return e.getValue();
            }
            return null;
        });
    }

    /** Called from 山海的配方库.js at the very top of ServerEvents.recipes: cache valid -> return early, skip the whole file. */
    public static boolean isCacheValid() {
        if (!isEnabled()) return false;
        if (cacheValid == null) {
            cacheValid = computeCacheValid();
        }
        return cacheValid;
    }

    /** 缓存命中时清单里记的配方数；供 DShanhaiRecipeEngine 在 RECIPE_TOTAL==0（被跳过）时兜底展示。 */
    public static long getCachedRecipeCount() {
        isCacheValid();
        return cachedRecipeCount;
    }

    private static boolean computeCacheValid() {
        try {
            String currentHash = computeSourceHash();
            if (currentHash == null || !Files.exists(MANIFEST_FILE) || !Files.isDirectory(DATA_ROOT)) {
                LOG.info("[DShanhaiRecipeCache] no usable cache found, will rebuild this boot");
                return false;
            }
            JsonObject manifest = GSON.fromJson(Files.readString(MANIFEST_FILE, StandardCharsets.UTF_8), JsonObject.class);
            String storedHash = manifest != null && manifest.has("sourceHash") ? manifest.get("sourceHash").getAsString() : null;
            boolean valid = currentHash.equals(storedHash);
            if (valid && manifest != null && manifest.has("recipeCount")) {
                cachedRecipeCount = manifest.get("recipeCount").getAsLong();
            }
            LOG.info("[DShanhaiRecipeCache] cache {} (hash={})", valid ? "HIT" : "MISS/stale", currentHash);
            return valid;
        } catch (Exception e) {
            LOG.warn("[DShanhaiRecipeCache] cache validity check failed, forcing rebuild", e);
            return false;
        }
    }

    private static String computeSourceHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            boolean any = false;
            for (Path p : List.of(RECIPE_LIB_JS, GTLCORE_CONFIG)) {
                if (Files.exists(p)) {
                    digest.update(Files.readAllBytes(p));
                    any = true;
                }
            }
            if (!any) return null;
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            LOG.warn("[DShanhaiRecipeCache] failed to hash source files", e);
            return null;
        }
    }

    /** Hooked to ServerAboutToStartEvent, after KubeJS's ServerEvents.recipes has fully run. */
    public static void exportIfNeeded(MinecraftServer server) {
        if (!isEnabled() || server == null) return;
        if (isCacheValid()) {
            LOG.info("[DShanhaiRecipeCache] cache hit this boot, skip export (山海的配方库.js / 山海的gtceujs优化.js 应该也跳过了注册)");
            return;
        }

        RecipeManager manager = server.getRecipeManager();
        RecipeManagerReflectionUtil.RecipeManagerMaps maps = RecipeManagerReflectionUtil.resolve(manager);
        if (maps == null) {
            LOG.warn("[DShanhaiRecipeCache] could not locate RecipeManager recipe-map fields, export skipped");
            return;
        }
        Map<ResourceLocation, Recipe<?>> byName = maps.byNameMap();
        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType = maps.recipesMap();

        cleanOldExport();

        int written = 0, missing = 0;
        java.util.Set<ResourceLocation> exportedIds = new java.util.HashSet<>();

        // Pass 1：山海 Java 侧自己注册的自定义类型（DShanhaiRecipeTypes 反射得到），不管有没有
        // 被 safeAddRecipe 追踪到，整桶无条件导出——从根上兜住裸调用 gtr.xxx() 和声明id对不上的情况。
        java.util.Set<GTRecipeType> ownedTypes = ownedRecipeTypes();
        java.util.Set<GTRecipeType> handledTypes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (GTRecipeType ownedType : ownedTypes) {
            Map<ResourceLocation, Recipe<?>> ofThisType = byType.get(ownedType);
            if (ofThisType == null || ofThisType.isEmpty()) continue;
            handledTypes.add(ownedType);
            for (Recipe<?> recipe : ofThisType.values()) {
                if (recipe instanceof GTRecipe gtRecipe && exportOne(gtRecipe)) {
                    written++;
                    exportedIds.add(gtRecipe.getId());
                } else missing++;
            }
        }
        LOG.info("[DShanhaiRecipeCache] owned-type bulk export: {} types, {} recipes written so far", handledTypes.size(), written);

        // Pass 2：山海的配方库.js 走 safeAddRecipe(type, id, callback) 追踪到、但类型不在上面
        // owned 清单里的（真正跨 mod 共用的类型，比如 element_copying），精确按 (rawId -> type) 补齐。
        Map<String, String> tracked = DShanhaiRecipeEngine.getTrackedRecipesForCache();
        if (tracked.isEmpty()) {
            LOG.info("[DShanhaiRecipeCache] no recipes tracked via safeAddRecipe this run (山海的配方库.js didn't run or everything was skipped)");
        } else {
            // group tracked (rawId -> type) by type so exclusively-ours-this-run types can be dumped
            // whole too, sidestepping the odd handful of recipes where the id passed to safeAddRecipe()
            // doesn't match the id actually used inside the callback's gtr[type](...) call.
            Map<String, List<String>> idsByType = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : tracked.entrySet()) {
                idsByType.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
            }

            for (Map.Entry<String, List<String>> typeEntry : idsByType.entrySet()) {
                String type = typeEntry.getKey();
                List<String> rawIds = typeEntry.getValue();
                GTRecipeType recipeType = resolveRecipeType(type);
                if (recipeType == null) {
                    LOG.warn("[DShanhaiRecipeCache] recipe type '{}' not found ({} tracked recipes), export skipped for this type", type, rawIds.size());
                    missing += rawIds.size();
                    continue;
                }
                if (handledTypes.contains(recipeType)) continue; // already bulk-exported in pass 1

                Map<ResourceLocation, Recipe<?>> ofThisType = byType.get(recipeType);
                if (ofThisType != null && ofThisType.size() == rawIds.size()) {
                    // exclusively ours this run -- export every recipe RecipeManager has for it
                    // directly, no id reconstruction needed (robust against declared-id != actual-id typos).
                    for (Recipe<?> recipe : ofThisType.values()) {
                        if (recipe instanceof GTRecipe gtRecipe && exportOne(gtRecipe)) {
                            written++;
                            exportedIds.add(gtRecipe.getId());
                        } else missing++;
                    }
                    continue;
                }

                // shared type (e.g. also used by gtceu.js) -- must match by reconstructed id precisely.
                for (String rawId : rawIds) {
                    ResourceLocation baseId = baseResourceLocation(rawId);
                    ResourceLocation finalId = new ResourceLocation(baseId.getNamespace(),
                            recipeType.registryName.getPath() + "/" + baseId.getPath());
                    Recipe<?> recipe = byName.get(finalId);
                    if (recipe instanceof GTRecipe gtRecipe && exportOne(gtRecipe)) {
                        written++;
                        exportedIds.add(gtRecipe.getId());
                    } else {
                        LOG.warn("[DShanhaiRecipeCache] {} not found in RecipeManager (tracked id={}, type={}), export skipped", finalId, rawId, type);
                        missing++;
                    }
                }
            }
        }

        // Pass 3：兜底扫描 山海的配方库.js 源码文本里所有 gtr.<type>('<id>') 调用——这个模式本身
        // 就是真正注册时用的确切类型和 id（写错了配方注册会直接失败/炸掉，不可能悄悄错着还能跑），
        // 比 safeAddRecipe 声明的 bookkeeping 参数、比"独占类型"清单都更权威。专门补前两遍漏掉的：
        // 完全没走 safeAddRecipe 的裸调用、或者类型既不在山海自定义清单里也不在本次 tracked 表里的。
        int fallbackWritten = 0, fallbackMissing = 0;
        try {
            String source = Files.readString(RECIPE_LIB_JS, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = GTR_CALL_PATTERN.matcher(source);
            java.util.Set<String> seenThisPass = new java.util.HashSet<>();
            while (m.find()) {
                String type = m.group(1);
                String rawId = m.group(3);
                if (!seenThisPass.add(type + '|' + rawId)) continue; // 去重（同一配方可能被匹配多次）
                GTRecipeType recipeType = resolveRecipeType(type);
                if (recipeType == null) {
                    LOG.warn("[DShanhaiRecipeCache] source-scan: gtr.{}('{}') -- recipe type '{}' not found in GTRegistries.RECIPE_TYPES", type, rawId, type);
                    fallbackMissing++;
                    continue;
                }
                // Pass 1 已经把这个类型的整桶都无条件导出过了（不管 id 猜没猜对），这里再按
                // baseResourceLocation() 猜的命名空间去比对只会因为猜错命名空间（比如裸 id
                // 默认猜 dishanhai:，但 GTCEu 实际给的是 gtceu:）而误报"缺失"，直接跳过。
                if (handledTypes.contains(recipeType)) continue;
                ResourceLocation baseId = baseResourceLocation(rawId);
                ResourceLocation finalId = new ResourceLocation(baseId.getNamespace(),
                        recipeType.registryName.getPath() + "/" + baseId.getPath());
                if (exportedIds.contains(finalId)) continue; // 前两遍已经导出过
                Recipe<?> recipe = byName.get(finalId);
                if (recipe instanceof GTRecipe gtRecipe && exportOne(gtRecipe)) {
                    exportedIds.add(finalId);
                    fallbackWritten++;
                } else {
                    LOG.warn("[DShanhaiRecipeCache] source-scan: gtr.{}('{}') -> expected {} NOT found in RecipeManager (registration likely failed or was filtered out this run, see server log around this recipe id for the real cause)", type, rawId, finalId);
                    fallbackMissing++;
                }
            }
        } catch (IOException e) {
            LOG.warn("[DShanhaiRecipeCache] source-scan fallback failed to read 山海的配方库.js", e);
        }
        written += fallbackWritten;
        missing += fallbackMissing;
        LOG.info("[DShanhaiRecipeCache] source-scan fallback: written={} still-missing={}", fallbackWritten, fallbackMissing);

        // 注：山海的gtceujs优化.js（gtceu.js 的接管副本）不接这套缓存——它混了 gtr.xxx()（GTRecipe，
        // 能靠 GTRecipeSerializer.CODEC 导出）和 event.shapeless/shaped()（原版 ShapedRecipe/
        // ShapelessRecipe，导不出来）两种配方类型，还夹了 kubejs: 等非 gtceu: 命名空间，兜底导出
        // 做不到不漏，所以它自己每次都完整跑一遍，不指望这里的缓存。

        writePackMcmeta();
        writeManifest(written);
        LOG.info("[DShanhaiRecipeCache] export done: written={} missing={} -> {}", written, missing, DATA_ROOT);
    }

    private static boolean exportOne(GTRecipe recipe) {
        try {
            ResourceLocation id = recipe.getId();
            JsonElement json = GTRecipeSerializer.CODEC.encodeStart(JsonOps.INSTANCE, recipe)
                    .getOrThrow(false, LOG::warn);
            Path file = DATA_ROOT.resolve(id.getNamespace()).resolve("recipes").resolve(id.getPath() + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY_GSON.toJson(json), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            LOG.warn("[DShanhaiRecipeCache] export failed for {}", recipe.getId(), e);
            return false;
        }
    }

    private static ResourceLocation baseResourceLocation(String rawId) {
        int idx = rawId.indexOf(':');
        return idx >= 0 ? new ResourceLocation(rawId.substring(0, idx), rawId.substring(idx + 1))
                : new ResourceLocation("dishanhai", rawId);
    }

    private static void cleanOldExport() {
        if (!Files.isDirectory(DATA_ROOT)) return;
        try (Stream<Path> walk = Files.walk(DATA_ROOT)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            LOG.warn("[DShanhaiRecipeCache] failed to clean old export directory", e);
        }
    }

    private static void writePackMcmeta() {
        try {
            Files.createDirectories(EXPORT_ROOT);
            String mcmeta = "{\"pack\":{\"pack_format\":15,\"description\":\"dishanhai recipe cache (auto-generated, do not edit)\"}}";
            Files.writeString(EXPORT_ROOT.resolve("pack.mcmeta"), mcmeta, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("[DShanhaiRecipeCache] failed to write pack.mcmeta", e);
        }
    }

    private static void writeManifest(int count) {
        try {
            String hash = computeSourceHash();
            JsonObject manifest = new JsonObject();
            manifest.addProperty("sourceHash", hash);
            manifest.addProperty("recipeCount", count);
            Files.writeString(MANIFEST_FILE, manifest.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("[DShanhaiRecipeCache] failed to write manifest", e);
        }
    }
}
