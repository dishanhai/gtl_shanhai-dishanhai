package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;

import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorRecipes;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorScale;
import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 山海样板生成工具写出的星律通配样板。
 * 放进星律样板总成的通配符母槽后，按记录的配方 ID 展开，不走材料通配符。
 */
public final class SelectedRecipePattern {

    public static final String TAG = "gt_shanhai_selected_recipes";
    public static final String RECIPE_ID = "gt_shanhai_bundle_recipe";
    public static final int VERSION = 1;
    public static final int MAX_RECIPES = 4096;

    private SelectedRecipePattern() {}

    public record Contents(int scale, int outputScale, String recipeType, List<String> recipes) {}

    public record Expanded(IPatternDetails pattern, ItemStack stack, GTRecipe recipe) {}

    public static final class Tooltip implements IAddInformation {
        public static final Tooltip INSTANCE = new Tooltip();

        private Tooltip() {}

        @Override
        public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
            SelectedRecipePattern.appendTooltip(stack, lines);
        }
    }

    public static ItemStack create(Contents contents) {
        CompoundTag tag = write(contents);
        if (tag == null || !tag.contains(TAG) || DShanhaiItems.SELECTED_RECIPE_PATTERN == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(DShanhaiItems.SELECTED_RECIPE_PATTERN.get());
        stack.setTag(tag);
        return stack;
    }

    public static boolean isPattern(ItemStack stack) {
        if (stack == null || stack.isEmpty() || DShanhaiItems.SELECTED_RECIPE_PATTERN == null) return false;
        return stack.getItem() == DShanhaiItems.SELECTED_RECIPE_PATTERN.get() && read(stack.getTag()) != null;
    }

    public static boolean hasRecipeId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(RECIPE_ID, Tag.TAG_STRING) && !tag.getString(RECIPE_ID).isEmpty();
    }

    @Nullable
    public static CompoundTag write(@Nullable Contents contents) {
        if (contents == null || contents.recipes() == null || contents.recipes().isEmpty()) return null;
        ListTag recipes = new ListTag();
        Set<String> seen = new HashSet<>();
        for (String id : contents.recipes()) {
            if (recipes.size() >= MAX_RECIPES) break;
            if (id == null || ResourceLocation.tryParse(id) == null || !seen.add(id)) continue;
            recipes.add(StringTag.valueOf(id));
        }
        if (recipes.isEmpty()) return null;
        String recipeType = contents.recipeType() == null ? "" : contents.recipeType();
        if (!recipeType.isEmpty() && ResourceLocation.tryParse(recipeType) == null) recipeType = "";
        CompoundTag data = new CompoundTag();
        data.putInt("version", VERSION);
        data.putInt("scale", PatternGeneratorScale.clamp(contents.scale()));
        data.putInt("outputScale", PatternGeneratorScale.clamp(contents.outputScale()));
        data.putString("recipeType", recipeType);
        data.put("recipes", recipes);
        CompoundTag root = new CompoundTag();
        root.put(TAG, data);
        return root;
    }

    @Nullable
    public static Contents read(@Nullable CompoundTag root) {
        if (root == null || !root.contains(TAG, Tag.TAG_COMPOUND)) return null;
        CompoundTag data = root.getCompound(TAG);
        if (data.getInt("version") != VERSION || !data.contains("recipes", Tag.TAG_LIST)) return null;
        ListTag recipes = data.getList("recipes", Tag.TAG_STRING);
        List<String> ids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < recipes.size() && ids.size() < MAX_RECIPES; i++) {
            String id = recipes.getString(i);
            if (ResourceLocation.tryParse(id) == null || !seen.add(id)) continue;
            ids.add(id);
        }
        if (ids.isEmpty()) return null;
        String recipeType = data.getString("recipeType");
        if (!recipeType.isEmpty() && ResourceLocation.tryParse(recipeType) == null) recipeType = "";
        return new Contents(PatternGeneratorScale.clamp(data.getInt("scale")),
                PatternGeneratorScale.clamp(data.getInt("outputScale")), recipeType, List.copyOf(ids));
    }

    public static void appendTooltip(ItemStack stack, List<Component> lines) {
        Contents contents = read(stack == null ? null : stack.getTag());
        if (contents == null) return;
        lines.add(Component.translatable("tooltip.gt_shanhai.selected_recipe_pattern.use")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.gt_shanhai.selected_recipe_pattern.count", contents.recipes().size())
                .withStyle(ChatFormatting.AQUA));
        if (!contents.recipeType().isEmpty()) {
            ResourceLocation typeId = ResourceLocation.tryParse(contents.recipeType());
            if (typeId != null) {
                lines.add(Component.translatable("tooltip.gt_shanhai.selected_recipe_pattern.type",
                        Component.translatable(typeId.getNamespace() + "." + typeId.getPath()))
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        if (contents.scale() != 1 || contents.outputScale() != 1) {
            lines.add(Component.translatable("tooltip.gt_shanhai.selected_recipe_pattern.scale",
                    contents.scale(), PatternGeneratorScale.outputFactor(contents.scale(), contents.outputScale()))
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    public static List<Expanded> expand(ItemStack bundle, @Nullable Level level) {
        Contents contents = bundle == null ? null : read(bundle.getTag());
        if (contents == null || level == null) return List.of();
        List<Expanded> expanded = new ArrayList<>();
        for (String id : contents.recipes()) {
            Expanded one = expandOne(id, contents, level);
            if (one != null) expanded.add(one);
        }
        return expanded;
    }

    @Nullable
    public static GTRecipe recipeOf(ItemStack patternStack, @Nullable Level level, String assignedTypeId) {
        if (!hasRecipeId(patternStack) || level == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(patternStack.getTag().getString(RECIPE_ID));
        if (id == null) return null;
        if (!(level.getRecipeManager().byKey(id).orElse(null) instanceof GTRecipe recipe)) return null;
        if (assignedTypeId != null && !assignedTypeId.isEmpty()
                && (recipe.recipeType == null || recipe.recipeType.registryName == null
                || !assignedTypeId.equals(recipe.recipeType.registryName.toString()))) {
            return null;
        }
        return recipe;
    }

    @Nullable
    private static Expanded expandOne(String id, Contents contents, Level level) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return null;
        if (!(level.getRecipeManager().byKey(key).orElse(null) instanceof GTRecipe recipe)) return null;
        if (recipe.recipeType == null || recipe.recipeType.registryName == null) return null;
        PatternGeneratorRecipes.Entry entry = PatternGeneratorRecipes.describe(recipe);
        if (entry.encodedOutputs().isEmpty()) return null;
        ItemStack encoded;
        try {
            encoded = PatternGeneratorRecipes.encodeDefinition(entry, contents.scale(), contents.outputScale());
        } catch (RuntimeException ignored) {
            return null;
        }
        if (encoded.isEmpty()) return null;
        encoded.getOrCreateTag().putString(RECIPE_ID, recipe.id.toString());
        PatternRecipeTypeHelper.writeAuthoritativeRecipeType(encoded, recipe);
        IPatternDetails details = PatternDetailsHelper.decodePattern(encoded, level);
        if (details == null) return null;
        return new Expanded(details, encoded, recipe);
    }
}
