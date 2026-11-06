package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.RecipeTypeSharedSearchSets;
import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.dishanhai.gt_shanhai.common.item.WildcardPatternBridge;
import com.dishanhai.gt_shanhai.common.item.WildcardPatternRecipeTypeBinding;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 山海：只編譯庫原件的派生視圖，原件與其倍率／配方身份保持不變。 */
public final class StellarCloudPatterns {
    private StellarCloudPatterns() {}
    public record Template(ItemStack stack, IPatternDetails pattern, GTRecipe recipe) {}
    public static List<Template> compile(StellarCloudSource.Entry entry, Level level, Set<String> hostTypes) {
        if (hostTypes.isEmpty()) return List.of();
        List<Template> result = new ArrayList<>();
        ItemStack original = entry.original();
        if (SelectedRecipePattern.isPattern(original)) {
            for (var child : SelectedRecipePattern.expand(original, level))
                append(result, child.stack(), child.pattern(), child.recipe(), hostTypes, level);
        } else if (WildcardPatternBridge.isWildcardPattern(original)) {
            for (var pattern : WildcardPatternBridge.expandPatterns(original, level)) {
                ItemStack stack = pattern.getDefinition().toStack();
                append(result, stack, pattern, resolve(stack, pattern, level, entry, hostTypes), hostTypes, level);
            }
        } else {
            IPatternDetails pattern = PatternDetailsHelper.decodePattern(original, level);
            if (pattern != null) append(result, original, pattern, resolve(original, pattern, level, entry, hostTypes), hostTypes, level);
        }
        return result;
    }
    private static GTRecipe resolve(ItemStack stack, IPatternDetails pattern, Level level,
            StellarCloudSource.Entry entry, Set<String> hostTypes) {
        String type = PatternRecipeTypeHelper.readRecipeTypeId(stack), id = PatternRecipeTypeHelper.readRecipeId(stack);
        if (!id.isEmpty()) return PatternRecipeTypeHelper.resolveRecipeForPattern(level, type, id);
        if (!(pattern instanceof AEProcessingPattern processing)) return null;
        List<GenericStack> catalysts = new ArrayList<>();
        var rows = entry.configuration().getList("stellarCloudCatalysts", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.getCompound(i); AEKey key = AEKey.fromTagGeneric(row.getCompound("key"));
            if (key != null && row.getLong("amount") > 0) catalysts.add(new GenericStack(key, row.getLong("amount")));
        }
        if (entry.configuration().contains("circuit")) {
            ItemStack circuit = IntCircuitBehaviour.stack(entry.configuration().getInt("circuit"));
            if (!circuit.isEmpty()) catalysts.add(new GenericStack(AEItemKey.of(circuit), 1));
        }
        GTRecipe matched = null;
        Set<String> candidateTypes = new LinkedHashSet<>();
        if (type.isEmpty()) {
            candidateTypes.addAll(hostTypes);
            for (var candidate : GTRegistries.RECIPE_TYPES) {
                if (candidate.registryName == null) continue;
                String candidateType = candidate.registryName.toString();
                if (hostTypes.stream().anyMatch(host -> RecipeTypeSharedSearchSets.isShared(host, candidateType)))
                    candidateTypes.add(candidateType);
            }
        } else candidateTypes.add(type);
        for (String candidateType : candidateTypes) {
            GTRecipe candidate = VirtualPatternEncodingHelper.findMatchingRecipeForPattern(
                    processing.getSparseInputs(), processing.getSparseOutputs(), candidateType,
                    catalysts.toArray(GenericStack[]::new));
            if (candidate == null || candidate.recipeType == null || candidate.recipeType.registryName == null
                    || !PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent(
                            candidateType, candidate.recipeType.registryName.toString())) continue;
            if (matched != null && !matched.id.equals(candidate.id)) return null;
            matched = candidate;
        }
        return matched;
    }
    private static void append(List<Template> result, ItemStack original, IPatternDetails pattern,
            GTRecipe recipe, Set<String> types, Level level) {
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) return;
        String type = recipe.recipeType.registryName.toString();
        if (types.stream().noneMatch(host -> RecipeTypeSharedSearchSets.isShared(host, type))) return;
        if (!PatternRecipeTypeHelper.storedRecipeFitsPattern(original, level, recipe)) return;
        ItemStack stack = original.copyWithCount(1);
        PatternRecipeTypeHelper.writeRecipeType(stack, recipe);
        // 派生身份供 baseStack 重載使用，不受玩家編碼偏好影響。
        stack.getOrCreateTag().putString(PatternRecipeTypeHelper.TAG_RECIPE_ID, recipe.id.toString());
        result.add(new Template(stack, pattern, recipe));
    }
}
