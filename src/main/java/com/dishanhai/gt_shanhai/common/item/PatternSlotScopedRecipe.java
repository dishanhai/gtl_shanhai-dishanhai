package com.dishanhai.gt_shanhai.common.item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Keeps duplicate processing patterns independent while preserving the public recipe ID. */
public final class PatternSlotScopedRecipe extends GTRecipe {

    private static final String SCOPE_PATH = "/__gt_shanhai_pattern_slot/";

    private final ResourceLocation originalId;
    private final ResourceLocation scopedId;
    private final ResourceLocation sourceDimensionId;
    private final BlockPos sourceBufferPos;
    private final int sourceSlot;

    private PatternSlotScopedRecipe(GTRecipe source, ResourceLocation originalId,
                                    ResourceLocation scopedId, ResourceLocation sourceDimensionId,
                                    BlockPos sourceBufferPos, int sourceSlot,
                                    @Nullable ContentModifier modifier, boolean modifyDuration) {
        super(source.recipeType, scopedId,
                source.copyContents(source.inputs, modifier),
                source.copyContents(source.outputs, modifier),
                source.copyContents(source.tickInputs, modifier),
                source.copyContents(source.tickOutputs, modifier),
                new HashMap<>(source.inputChanceLogics),
                new HashMap<>(source.outputChanceLogics),
                new HashMap<>(source.tickInputChanceLogics),
                new HashMap<>(source.tickOutputChanceLogics),
                new ArrayList<>(source.conditions),
                new ArrayList<>(source.ingredientActions),
                source.data, source.duration, source.isFuel);
        this.originalId = originalId;
        this.scopedId = scopedId;
        this.sourceDimensionId = sourceDimensionId;
        this.sourceBufferPos = sourceBufferPos.immutable();
        this.sourceSlot = sourceSlot;
        this.parallels = source.parallels;
        this.ocTier = source.ocTier;
        if (modifier != null && modifyDuration) {
            this.duration = modifier.apply(Integer.valueOf(source.duration)).intValue();
        }
    }

    static GTRecipe scope(GTRecipe recipe, ResourceLocation dimensionId, BlockPos bufferPos, int slot) {
        if (recipe == null) return null;
        ScopeData encodedScope = decodeScope(recipe.id);
        ResourceLocation originalId = recipe instanceof PatternSlotScopedRecipe scoped
                ? scoped.originalId : encodedScope == null ? recipe.id : encodedScope.originalId;
        if (originalId == null) {
            originalId = new ResourceLocation("gt_shanhai", "anonymous_pattern_recipe");
        }
        ResourceLocation scopedId = createScopedId(originalId, dimensionId, bufferPos, slot);
        if (recipe instanceof PatternSlotScopedRecipe scoped && scoped.scopedId.equals(scopedId)) {
            return scoped;
        }
        ResourceLocation dimension = dimensionId == null
                ? new ResourceLocation("minecraft", "overworld") : dimensionId;
        BlockPos pos = bufferPos == null ? BlockPos.ZERO : bufferPos;
        return new PatternSlotScopedRecipe(recipe, originalId, scopedId,
                dimension, pos, Math.max(0, slot), null, false);
    }

    public static boolean matchesSource(GTRecipe recipe, ResourceLocation dimensionId,
                                        BlockPos bufferPos, int slot) {
        if (recipe instanceof PatternSlotScopedRecipe scoped) {
            return scoped.sourceSlot == slot
                    && Objects.equals(scoped.sourceDimensionId, dimensionId)
                    && Objects.equals(scoped.sourceBufferPos, bufferPos);
        }
        ScopeData encodedScope = recipe == null ? null : decodeScope(recipe.id);
        if (encodedScope == null) return true;
        ResourceLocation dimension = dimensionId == null
                ? new ResourceLocation("minecraft", "overworld") : dimensionId;
        BlockPos pos = bufferPos == null ? BlockPos.ZERO : bufferPos;
        return encodedScope.sourceSlot == slot
                && Objects.equals(encodedScope.sourceDimensionId, dimension)
                && Objects.equals(encodedScope.sourceBufferPos, pos);
    }

    public static boolean isScoped(GTRecipe recipe) {
        return recipe instanceof PatternSlotScopedRecipe
                || recipe != null && decodeScope(recipe.id) != null;
    }

    public record ShadowKey(GTRecipeType recipeType, ResourceLocation recipeId) {}

    public static ShadowKey shadowKeyForScoped(GTRecipe recipe) {
        if (recipe instanceof PatternSlotScopedRecipe scoped) {
            return scoped.originalId == null
                    ? null : new ShadowKey(scoped.recipeType, scoped.originalId);
        }
        ScopeData encodedScope = recipe == null ? null : decodeScope(recipe.id);
        if (recipe == null || encodedScope == null || encodedScope.originalId == null) {
            return null;
        }
        return new ShadowKey(recipe.recipeType, encodedScope.originalId);
    }

    public static ShadowKey unscopedShadowKey(GTRecipe recipe) {
        if (recipe == null || recipe.id == null || isScoped(recipe)) {
            return null;
        }
        return new ShadowKey(recipe.recipeType, recipe.id);
    }

    public static boolean represents(GTRecipe scopedRecipe, GTRecipe candidate) {
        ShadowKey scoped = shadowKeyForScoped(scopedRecipe);
        return scoped != null && scoped.equals(unscopedShadowKey(candidate));
    }

    private static ResourceLocation createScopedId(ResourceLocation originalId, ResourceLocation dimensionId,
                                                   BlockPos bufferPos, int slot) {
        ResourceLocation dimension = dimensionId == null
                ? new ResourceLocation("minecraft", "overworld") : dimensionId;
        BlockPos pos = bufferPos == null ? BlockPos.ZERO : bufferPos;
        String path = originalId.getPath() + SCOPE_PATH
                + dimension.getNamespace() + "/" + dimension.getPath() + "/"
                + pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + "/" + Math.max(0, slot);
        return new ResourceLocation(originalId.getNamespace(), path);
    }

    private static ScopeData decodeScope(ResourceLocation id) {
        if (id == null) return null;
        String path = id.getPath();
        int markerIndex = path.lastIndexOf(SCOPE_PATH);
        if (markerIndex < 0) return null;
        String suffix = path.substring(markerIndex + SCOPE_PATH.length());
        int slotSeparator = suffix.lastIndexOf('/');
        int posSeparator = slotSeparator < 0 ? -1 : suffix.lastIndexOf('/', slotSeparator - 1);
        int dimensionSeparator = suffix.indexOf('/');
        if (dimensionSeparator <= 0 || posSeparator <= dimensionSeparator || slotSeparator <= posSeparator) {
            return null;
        }
        try {
            ResourceLocation originalId = new ResourceLocation(id.getNamespace(), path.substring(0, markerIndex));
            ResourceLocation dimensionId = new ResourceLocation(
                    suffix.substring(0, dimensionSeparator),
                    suffix.substring(dimensionSeparator + 1, posSeparator));
            String[] coordinates = suffix.substring(posSeparator + 1, slotSeparator).split("_", -1);
            if (coordinates.length != 3) return null;
            BlockPos sourcePos = new BlockPos(
                    Integer.parseInt(coordinates[0]),
                    Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]));
            int sourceSlot = Integer.parseInt(suffix.substring(slotSeparator + 1));
            return new ScopeData(originalId, dimensionId, sourcePos, sourceSlot);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static final class ScopeData {
        private final ResourceLocation originalId;
        private final ResourceLocation sourceDimensionId;
        private final BlockPos sourceBufferPos;
        private final int sourceSlot;

        private ScopeData(ResourceLocation originalId, ResourceLocation sourceDimensionId,
                          BlockPos sourceBufferPos, int sourceSlot) {
            this.originalId = originalId;
            this.sourceDimensionId = sourceDimensionId;
            this.sourceBufferPos = sourceBufferPos;
            this.sourceSlot = sourceSlot;
        }
    }

    @Override
    public ResourceLocation getId() {
        return originalId;
    }

    @Override
    public GTRecipe copy() {
        return new PatternSlotScopedRecipe(this, originalId, scopedId,
                sourceDimensionId, sourceBufferPos, sourceSlot, null, false);
    }

    @Override
    public GTRecipe copy(ContentModifier modifier) {
        return copy(modifier, true);
    }

    @Override
    public GTRecipe copy(ContentModifier modifier, boolean modifyDuration) {
        return new PatternSlotScopedRecipe(this, originalId, scopedId,
                sourceDimensionId, sourceBufferPos, sourceSlot, modifier, modifyDuration);
    }
}
