package com.dishanhai.gt_shanhai.api;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

public final class DShanhaiRuntimeRecipeCache {

    private static final Map<Key, CachedRecipe> CACHE = new ConcurrentHashMap<>();

    // 运行期诊断计数器：仅在 DShanhaiConfig.COMMON.runtimeRecipeCacheDiagnostics 开启时自增，默认关闭不产生任何开销
    private static final LongAdder HIT_COUNT = new LongAdder();
    private static final LongAdder NEGATIVE_HIT_COUNT = new LongAdder();
    private static final LongAdder MISS_COUNT = new LongAdder();
    private static final LongAdder CLEAR_COUNT = new LongAdder();

    private DShanhaiRuntimeRecipeCache() {
    }

    private static boolean diagnosticsEnabled() {
        return com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.runtimeRecipeCacheDiagnostics.get();
    }

    public static Key key(String recipeTypeId, IRecipeCapabilityHolder holder, String scope) {
        return new Key(recipeTypeId, itemFingerprint(holder), fluidFingerprint(holder),
                DShanhaiRecipeModifierAPI.getPatternCacheRevision(), scope);
    }

    public static boolean contains(Key key) {
        return key != null && CACHE.containsKey(key);
    }

    public static Optional<GTRecipe> get(Key key) {
        CachedRecipe cached = CACHE.get(key);
        if (diagnosticsEnabled() && cached != null) {
            if (cached.recipe == null) {
                NEGATIVE_HIT_COUNT.increment();
            } else {
                HIT_COUNT.increment();
            }
        }
        return cached == null ? Optional.empty() : Optional.ofNullable(cached.recipe);
    }

    public static void put(Key key, GTRecipe recipe) {
        if (key != null) {
            CACHE.put(key, new CachedRecipe(recipe, null));
            if (diagnosticsEnabled()) {
                MISS_COUNT.increment();
            }
        }
    }

    public static void putCandidates(Key key, List<GTRecipe> candidates) {
        if (key == null) {
            return;
        }
        List<GTRecipe> copy = new ArrayList<>();
        if (candidates != null) {
            for (GTRecipe recipe : candidates) {
                if (recipe != null) {
                    copy.add(recipe);
                }
            }
        }
        CACHE.put(key, new CachedRecipe(null, Collections.unmodifiableList(copy)));
        if (diagnosticsEnabled()) {
            MISS_COUNT.increment();
        }
    }

    public static GTRecipe findFirstCandidate(Key key, Predicate<GTRecipe> predicate) {
        CachedRecipe cached = CACHE.get(key);
        if (cached == null || cached.candidates == null || predicate == null) {
            return null;
        }
        if (diagnosticsEnabled()) {
            HIT_COUNT.increment();
        }
        for (GTRecipe recipe : cached.candidates) {
            if (predicate.test(recipe)) {
                return recipe;
            }
        }
        return null;
    }

    public static void clear() {
        CACHE.clear();
        if (diagnosticsEnabled()) {
            CLEAR_COUNT.increment();
        }
    }

    public static int size() {
        return CACHE.size();
    }

    /** 运行期诊断统计快照：仅在诊断开关开启时才有意义（关闭时计数器恒为 0，不产生统计开销）。 */
    public static Map<String, Long> getDiagnosticsStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("hit", HIT_COUNT.sum());
        stats.put("negativeHit", NEGATIVE_HIT_COUNT.sum());
        stats.put("miss", MISS_COUNT.sum());
        stats.put("clear", CLEAR_COUNT.sum());
        stats.put("cacheSize", (long) CACHE.size());
        return stats;
    }

    /** 清零诊断计数器（不清空缓存本身）。 */
    public static void resetDiagnosticsStats() {
        HIT_COUNT.reset();
        NEGATIVE_HIT_COUNT.reset();
        MISS_COUNT.reset();
        CLEAR_COUNT.reset();
    }

    private static String itemFingerprint(IRecipeCapabilityHolder holder) {
        return capabilityFingerprint(holder, "item");
    }

    private static String fluidFingerprint(IRecipeCapabilityHolder holder) {
        return capabilityFingerprint(holder, "fluid");
    }

    private static String capabilityFingerprint(IRecipeCapabilityHolder holder, String capabilityName) {
        if (holder == null || holder.getCapabilitiesProxy() == null) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (Map.Entry<RecipeCapability<?>, List<IRecipeHandler<?>>> entry : holder.getCapabilitiesProxy().row(IO.IN).entrySet()) {
            RecipeCapability<?> capability = entry.getKey();
            if (capability == null || !capability.isRecipeSearchFilter() || !capabilityName.equals(capability.name)) {
                continue;
            }
            List<IRecipeHandler<?>> handlers = entry.getValue();
            if (handlers == null) {
                continue;
            }
            for (IRecipeHandler<?> handler : handlers) {
                if (handler == null || handler.isProxy()) {
                    continue;
                }
                List<Object> contents = handler.getContents();
                if (contents == null) {
                    continue;
                }
                for (Object content : contents) {
                    values.add(contentFingerprint(content));
                }
            }
        }
        values.sort(String::compareTo);
        return String.join("|", values);
    }

    private static String contentFingerprint(Object content) {
        if (content instanceof ItemStack stack) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return "item:" + (id == null ? "unknown" : id) + ':' + stack.getCount() + ':' + tagHash(stack.getTag());
        }
        if (content instanceof FluidStack stack) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
            return "fluid:" + (id == null ? "unknown" : id) + ':' + stack.getAmount() + ':' + tagHash(stack.getTag());
        }
        if (content == null) {
            return "null";
        }
        return content.getClass().getName() + ':' + content;
    }

    private static int tagHash(CompoundTag tag) {
        return tag == null ? 0 : tag.hashCode();
    }

    public static final class Key {
        private final String recipeTypeId;
        private final String itemFingerprint;
        private final String fluidFingerprint;
        private final long revision;
        private final String scope;

        private Key(String recipeTypeId, String itemFingerprint, String fluidFingerprint, long revision, String scope) {
            this.recipeTypeId = recipeTypeId;
            this.itemFingerprint = itemFingerprint;
            this.fluidFingerprint = fluidFingerprint;
            this.revision = revision;
            this.scope = scope;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Key other)) return false;
            return revision == other.revision
                    && Objects.equals(recipeTypeId, other.recipeTypeId)
                    && Objects.equals(itemFingerprint, other.itemFingerprint)
                    && Objects.equals(fluidFingerprint, other.fluidFingerprint)
                    && Objects.equals(scope, other.scope);
        }

        @Override
        public int hashCode() {
            return Objects.hash(recipeTypeId, itemFingerprint, fluidFingerprint, revision, scope);
        }
    }

    private static final class CachedRecipe {
        private final GTRecipe recipe;
        private final List<GTRecipe> candidates;

        private CachedRecipe(GTRecipe recipe, List<GTRecipe> candidates) {
            this.recipe = recipe;
            this.candidates = candidates;
        }
    }
}
