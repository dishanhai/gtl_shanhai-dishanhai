package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AmountFormat;
import appeng.api.stacks.GenericStack;
import appeng.core.localization.GuiText;
import appeng.crafting.pattern.AECraftingPattern;
import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;
import com.dishanhai.gt_shanhai.common.item.WildcardPatternBridge;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorRecipes;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternEncoderMetadata;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 山海：原件保持不動，只解碼一次供顯示和搜尋；母樣板僅編碼第一個可預覽配方。 */
record StellarCloudPatternPreview(ItemStack definition, Component title, List<Component> tooltip, String searchText) {
    private static final String PREFIX = "gt_shanhai.cloud.";

    static StellarCloudPatternPreview of(ItemStack original, Level level) {
        ItemStack definition = original;
        Component title = original.getHoverName();
        List<Component> lines = new ArrayList<>();
        lines.add(tr("ui.original", title));
        StringBuilder search = new StringBuilder(title.getString());
        int recipes = 1;
        try {
            PatternEncoderMetadata.readEncoder(original).ifPresent(encoder -> lines.add(
                    Component.translatable("tooltip.gtlcore.pattern_encoder", encoder.displayName())));
            var typeIds = PatternQuickUploadMetadata.readRecipeTypeIds(original);
            if (!typeIds.isEmpty()) {
                var names = Component.empty();
                boolean first = true;
                for (var id : typeIds) {
                    if (!first) names.append(", ");
                    names.append(PatternQuickUploadMetadata.recipeTypeName(id));
                    first = false;
                }
                lines.add(Component.translatable("tooltip.gtlcore.pattern_quick_upload_recipe_types", names));
            }
            if (SelectedRecipePattern.isPattern(original)) {
                SelectedRecipePattern.appendTooltip(original, level, lines);
                var contents = SelectedRecipePattern.read(original.getTag());
                if (contents != null) {
                    recipes = contents.recipes().size();
                    for (String name : SelectedRecipePattern.searchNames(original, level).outputs()) search.append(' ').append(name);
                    for (String id : contents.recipes()) {
                        var key = ResourceLocation.tryParse(id);
                        if (key == null || !(level.getRecipeManager().byKey(key).orElse(null) instanceof GTRecipe recipe)) continue;
                        var entry = PatternGeneratorRecipes.describe(recipe);
                        if (!entry.encodedOutputs().isEmpty()) {
                            definition = PatternGeneratorRecipes.encodeDefinition(entry, contents.scale(), contents.outputScale());
                            break;
                        }
                    }
                }
            } else if (WildcardPatternBridge.isWildcardPattern(original)) {
                var expanded = WildcardPatternBridge.expandPatterns(original, level);
                recipes = expanded.size();
                for (IPatternDetails pattern : expanded) {
                    for (var output : pattern.getOutputs()) search.append(' ').append(output.what().getDisplayName().getString());
                }
                if (!expanded.isEmpty()) definition = expanded.getFirst().getDefinition().toStack();
            }
            IPatternDetails details = PatternDetailsHelper.decodePattern(definition, level, false);
            if (details != null && details.getOutputs().length > 0) {
                title = details.getPrimaryOutput().what().getDisplayName();
                if (recipes > 1) lines.add(tr("ui.pattern_count", recipes));
                lines.add(tr("ui.inputs", Component.empty()));
                for (var input : details.getInputs()) {
                    var alternatives = input.getPossibleInputs();
                    if (alternatives.length == 0) continue;
                    lines.add(ingredient(alternatives[0], input.getMultiplier()));
                }
                lines.add(tr("ui.outputs", Component.empty()));
                for (var output : details.getOutputs()) {
                    search.append(' ').append(output.what().getDisplayName().getString());
                    lines.add(ingredient(output, 1));
                }
                if (details instanceof AECraftingPattern crafting) {
                    lines.add(GuiText.Substitute.text().copy().append(" ")
                            .append((crafting.canSubstitute ? GuiText.Yes : GuiText.No).text()));
                }
            } else lines.add(tr("ui.preview_unavailable"));
        } catch (RuntimeException malformed) {
            definition = original;
            lines.add(tr("ui.preview_unavailable"));
        }
        return new StellarCloudPatternPreview(definition.copy(), title, List.copyOf(lines), search.toString().toLowerCase(Locale.ROOT));
    }

    private static Component ingredient(GenericStack stack, long multiplier) {
        long amount;
        try { amount = Math.multiplyExact(stack.amount(), multiplier); }
        catch (ArithmeticException overflow) { amount = Long.MAX_VALUE; }
        return tr("ui.ingredient", stack.what().formatAmount(amount, AmountFormat.FULL), stack.what().getDisplayName());
    }

    private static Component tr(String suffix, Object... values) { return Component.translatable(PREFIX + suffix, values); }
}
