package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;

import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorRecipes;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorScale;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
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
    /** 右键面板放入格写入的配方 ID。生成器原有配方不进这份名单，取出样板时只撤这些。 */
    public static final String MANUAL_TAG = "gt_shanhai_selected_recipe_manual";
    public static final String RECIPE_ID = "gt_shanhai_bundle_recipe";
    public static final int VERSION = 1;
    public static final int MAX_RECIPES = 4096;
    /** 悬停只列前几条，剩下的进右键面板。 */
    public static final int TOOLTIP_RECIPES = 8;
    /** 面板里每张都是真实样板槽，和工具箱预览同一量级，避免一次同步上千张。 */
    public static final int PREVIEW_LIMIT = 270;
    /** GUI 四个角各叠一个物品材质。 */
    public static final int CORNER_ICONS = 4;

    private SelectedRecipePattern() {}

    public record Contents(int scale, int outputScale, String recipeType, List<String> recipes) {}

    public record Expanded(IPatternDetails pattern, ItemStack stack, GTRecipe recipe) {}

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

    /**
     * 先信样板上的配方 ID，且样板内容仍覆盖这条配方；对不上再走原来的样板匹配。
     * 通配样板自身不参与，避免套娃。
     */
    @Nullable
    public static GTRecipe matchEncodedPattern(ItemStack pattern, @Nullable Level level) {
        if (pattern == null || pattern.isEmpty() || level == null || isPattern(pattern)) return null;
        try {
            if (!PatternDetailsHelper.isEncodedPattern(pattern)) return null;
        } catch (RuntimeException ignored) {
            return null;
        }
        String stored = PatternRecipeTypeHelper.readRecipeId(pattern);
        if (!stored.isEmpty()) {
            GTRecipe recipe = PatternRecipeTypeHelper.resolveRecipe(level, stored);
            if (recipe != null && recipe.id != null
                    && PatternRecipeTypeHelper.storedRecipeFitsPattern(pattern, level, recipe)) {
                return recipe;
            }
        }
        GTRecipe peeked = PatternRecipeTypeHelper.peekRecipe(pattern, level);
        return peeked == null || peeked.id == null ? null : peeked;
    }

    /**
     * 用放入格当前匹配到的配方 ID 改写通配样板。
     * 生成器写下的配方留在底稿里；只增删 {@link #MANUAL_TAG} 记录过的 ID。
     * 已有配方类型保持不动，空样板才按本次放入结果补类型。
     */
    public static void syncManualRecipes(@Nullable CompoundTag root, List<String> slotRecipeIds,
            List<String> slotTypes) {
        if (root == null) return;
        List<String> previousManual = readManualIds(root);
        Contents current = read(root);
        List<String> base = new ArrayList<>();
        int scale = 1;
        int outputScale = 1;
        String type = "";
        if (current != null) {
            base.addAll(current.recipes());
            scale = current.scale();
            outputScale = current.outputScale();
            type = current.recipeType();
            for (String manualId : previousManual) {
                base.remove(manualId);
            }
        }
        List<String> manualNow = new ArrayList<>();
        List<String> manualTypes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (slotRecipeIds != null) {
            for (int i = 0; i < slotRecipeIds.size() && base.size() + manualNow.size() < MAX_RECIPES; i++) {
                String id = slotRecipeIds.get(i);
                if (!validRecipeId(id) || !seen.add(id) || base.contains(id)) {
                    continue;
                }
                manualNow.add(id);
                String slotType = slotTypes != null && i < slotTypes.size() ? slotTypes.get(i) : "";
                manualTypes.add(slotType == null ? "" : slotType);
            }
        }
        if (manualNow.isEmpty()) {
            root.remove(MANUAL_TAG);
        } else {
            ListTag manual = new ListTag();
            for (String id : manualNow) {
                manual.add(StringTag.valueOf(id));
            }
            root.put(MANUAL_TAG, manual);
        }
        List<String> merged = new ArrayList<>(base);
        merged.addAll(manualNow);
        if (merged.isEmpty()) {
            root.remove(TAG);
            return;
        }
        String nextType = type.isEmpty() ? singleRecipeType(manualTypes) : type;
        CompoundTag written = write(new Contents(scale, outputScale, nextType, merged));
        if (written == null) {
            root.remove(TAG);
            return;
        }
        root.put(TAG, written.getCompound(TAG));
    }

    private static List<String> readManualIds(CompoundTag root) {
        if (!root.contains(MANUAL_TAG, Tag.TAG_LIST)) return List.of();
        ListTag list = root.getList(MANUAL_TAG, Tag.TAG_STRING);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String id = list.getString(i);
            if (validRecipeId(id)) ids.add(id);
        }
        return ids;
    }

    private static boolean validRecipeId(String id) {
        if (id == null || id.isBlank()) return false;
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        return parsed != null && id.equals(parsed.toString());
    }

    private static String singleRecipeType(List<String> types) {
        String only = "";
        for (String type : types) {
            if (type == null || type.isEmpty() || ResourceLocation.tryParse(type) == null) continue;
            if (only.isEmpty()) {
                only = type;
            } else if (!only.equals(type)) {
                return "";
            }
        }
        return only;
    }

    /**
     * 按记录顺序取产出物品，跳过流体、空产出和重复物品，最多四个。
     * 只给客户端角标用，不编码 AE 样板。
     */
    public static List<ItemStack> cornerIcons(ItemStack bundle, @Nullable Level level) {
        Contents contents = bundle == null ? null : read(bundle.getTag());
        if (contents == null || level == null) return List.of();
        List<ItemStack> icons = new ArrayList<>(CORNER_ICONS);
        Set<Item> seen = new HashSet<>();
        for (String id : contents.recipes()) {
            if (icons.size() >= CORNER_ICONS) break;
            collectCornerIcons(level, id, icons, seen);
        }
        return List.copyOf(icons);
    }

    private static void collectCornerIcons(Level level, String id, List<ItemStack> icons, Set<Item> seen) {
        try {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null || !(level.getRecipeManager().byKey(key).orElse(null) instanceof GTRecipe recipe)) {
                return;
            }
            List<Content> outputs = recipe.getOutputContents(ItemRecipeCapability.CAP);
            if (outputs == null) return;
            for (Content content : outputs) {
                if (icons.size() >= CORNER_ICONS) return;
                ItemStack icon = firstOutputStack(content);
                if (icon == null || icon.isEmpty() || isSelf(icon) || !seen.add(icon.getItem())) continue;
                icons.add(icon);
            }
        } catch (RuntimeException ignored) {
            // 单个配方读失败就跳过，不影响其余角标。
        }
    }

    @Nullable
    private static ItemStack firstOutputStack(@Nullable Content content) {
        if (content == null) return null;
        Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
        if (ingredient == null || ingredient.isEmpty()) return null;
        ItemStack[] stacks = ingredient.getItems();
        if (stacks.length == 0 || stacks[0].isEmpty()) return null;
        ItemStack icon = stacks[0].copy();
        icon.setCount(1);
        return icon;
    }

    private static boolean isSelf(ItemStack stack) {
        return DShanhaiItems.SELECTED_RECIPE_PATTERN != null
                && stack.getItem() == DShanhaiItems.SELECTED_RECIPE_PATTERN.get();
    }

    public static List<String> tooltipIds(@Nullable Contents contents) {
        if (contents == null || contents.recipes() == null || contents.recipes().isEmpty()) return List.of();
        int shown = Math.min(TOOLTIP_RECIPES, contents.recipes().size());
        return List.copyOf(contents.recipes().subList(0, shown));
    }

    public static void appendTooltip(ItemStack stack, @Nullable Level level, List<Component> lines) {
        Contents contents = read(stack == null ? null : stack.getTag());
        if (contents == null) {
            lines.add(Component.translatable("tooltip.gt_shanhai.selected_recipe_pattern.empty")
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("tooltip.gt_shanhai.selected_recipe_pattern.open")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
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
        lines.add(Component.translatable("tooltip.gt_shanhai.selected_recipe_pattern.scale",
                contents.scale(), PatternGeneratorScale.outputFactor(contents.scale(), contents.outputScale()))
                .withStyle(ChatFormatting.GRAY));
        List<String> shown = tooltipIds(contents);
        for (String id : shown) {
            lines.add(describeRecipe(level, id));
        }
        int rest = contents.recipes().size() - shown.size();
        if (rest > 0) {
            lines.add(Component.translatable("tooltip.gt_shanhai.selected_recipe_pattern.more", rest)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        lines.add(Component.translatable("tooltip.gt_shanhai.selected_recipe_pattern.open")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 右键面板用的只读样板。编码失败或配方已不在时给屏障，避免整张清单断掉。 */
    public static List<ItemStack> previewStacks(ItemStack bundle, @Nullable Level level, int limit) {
        Contents contents = bundle == null ? null : read(bundle.getTag());
        if (contents == null || level == null || limit <= 0) return List.of();
        List<ItemStack> stacks = new ArrayList<>();
        for (String id : contents.recipes()) {
            if (stacks.size() >= limit) break;
            stacks.add(previewStack(id, contents, level));
        }
        return stacks;
    }

    private static ItemStack previewStack(String id, Contents contents, Level level) {
        try {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null || !(level.getRecipeManager().byKey(key).orElse(null) instanceof GTRecipe recipe)) {
                return missing(id);
            }
            if (recipe.recipeType == null || recipe.recipeType.registryName == null) return missing(id);
            PatternGeneratorRecipes.Entry entry = PatternGeneratorRecipes.describe(recipe);
            if (entry.encodedOutputs().isEmpty()) return missing(id);
            ItemStack encoded = PatternGeneratorRecipes.encodeDefinition(entry, contents.scale(), contents.outputScale());
            if (encoded.isEmpty()) return missing(id);
            encoded.getOrCreateTag().putString(RECIPE_ID, recipe.id.toString());
            encoded.setHoverName(Component.literal(recipe.id.toString()));
            return encoded;
        } catch (RuntimeException ignored) {
            return missing(id);
        }
    }

    private static ItemStack missing(String id) {
        ItemStack stack = new ItemStack(Items.BARRIER);
        stack.setHoverName(Component.translatable("gui.gt_shanhai.selected_recipe_pattern.missing", id));
        return stack;
    }

    private static Component describeRecipe(@Nullable Level level, String id) {
        Component output = outputName(level, id);
        if (output == null) return Component.literal(id).withStyle(ChatFormatting.DARK_GRAY);
        return output.copy().append(Component.literal(" " + id).withStyle(ChatFormatting.DARK_GRAY));
    }

    @Nullable
    private static Component outputName(@Nullable Level level, String id) {
        if (level == null) return null;
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return null;
        if (!(level.getRecipeManager().byKey(key).orElse(null) instanceof GTRecipe recipe)) return null;
        Component item = firstItemName(recipe.getOutputContents(ItemRecipeCapability.CAP));
        if (item != null) return item;
        return firstFluidName(recipe.getOutputContents(FluidRecipeCapability.CAP));
    }

    @Nullable
    private static Component firstItemName(@Nullable List<Content> contents) {
        if (contents == null) return null;
        for (Content content : contents) {
            if (content == null) continue;
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length == 0 || stacks[0].isEmpty()) continue;
            return stacks[0].getHoverName();
        }
        return null;
    }

    @Nullable
    private static Component firstFluidName(@Nullable List<Content> contents) {
        if (contents == null) return null;
        for (Content content : contents) {
            if (content == null) continue;
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            FluidStack[] stacks = ingredient.getStacks();
            if (stacks.length == 0 || stacks[0].isEmpty()) continue;
            return stacks[0].getDisplayName();
        }
        return null;
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
