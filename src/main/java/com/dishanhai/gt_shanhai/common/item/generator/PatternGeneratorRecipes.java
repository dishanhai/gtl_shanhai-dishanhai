package com.dishanhai.gt_shanhai.common.item.generator;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.Ae2GtmProcessingPattern;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternEncoderMetadata;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 只读已注册机器和当前服务器配方，不改配方本身。 */
public final class PatternGeneratorRecipes {
    private PatternGeneratorRecipes() {}

    public record Entry(GTRecipe recipe, int circuit, List<PatternGeneratorFilter.Material> inputs,
                        List<PatternGeneratorFilter.Material> outputs, List<GenericStack> encodedInputs,
                        List<GenericStack> encodedOutputs) {
        public String id() {
            return recipe.id.toString();
        }

        public int tier() {
            return Math.max(0, Math.min(GTValues.VN.length - 1, RecipeHelper.getRecipeEUtTier(recipe)));
        }

        public boolean matches(PatternGeneratorSettings settings) {
            return PatternGeneratorFilter.matchesCircuit(settings.circuit, circuit)
                    && PatternGeneratorFilter.matchesTier(settings.minTier, settings.maxTier, tier())
                    && PatternGeneratorFilter.matchesId(settings.recipeQuery, id())
                    && settings.allowsGhosts(inputs, true)
                    && settings.allowsGhosts(outputs, false)
                    && PatternGeneratorFilter.allows(inputs, PatternGeneratorFilter.keywords(settings.inputWhite),
                    settings.inputNamedItems, settings.inputNamedFluids, java.util.Set.of())
                    && PatternGeneratorFilter.allows(outputs, PatternGeneratorFilter.keywords(settings.outputWhite),
                    settings.outputNamedItems, settings.outputNamedFluids, java.util.Set.of());
        }
    }

    public static List<GTRecipeType> machineTypes(ItemStack machine) {
        if (machine.isEmpty() || !(machine.getItem() instanceof MetaMachineItem item)) return List.of();
        GTRecipeType[] types = item.getDefinition().getRecipeTypes();
        if (types == null) return List.of();
        return Arrays.stream(types).filter(type -> type != null && type.registryName != null).distinct().toList();
    }

    public static String typeTranslation(GTRecipeType type) {
        return type.registryName.getNamespace() + "." + type.registryName.getPath();
    }

    public static List<Entry> recipes(ServerPlayer player, GTRecipeType type) {
        return player.server.getRecipeManager().getAllRecipesFor(type).stream()
                .map(PatternGeneratorRecipes::describe)
                .filter(entry -> !entry.encodedOutputs().isEmpty())
                .sorted(comparator(PatternGeneratorFilter.SORT_ID))
                .toList();
    }

    public static Comparator<Entry> comparator(int sort) {
        int mode = PatternGeneratorFilter.sortMode(sort);
        if (mode == PatternGeneratorFilter.SORT_EUT_ASC) {
            return Comparator.comparingLong((Entry entry) -> RecipeHelper.getInputEUt(entry.recipe())).thenComparing(Entry::id);
        }
        if (mode == PatternGeneratorFilter.SORT_EUT_DESC) {
            return Comparator.<Entry>comparingLong(entry -> RecipeHelper.getInputEUt(entry.recipe())).reversed().thenComparing(Entry::id);
        }
        if (mode == PatternGeneratorFilter.SORT_DURATION) {
            return Comparator.comparingInt((Entry entry) -> entry.recipe().duration).thenComparing(Entry::id);
        }
        return Comparator.comparing(Entry::id);
    }

    public static Entry describe(GTRecipe recipe) {
        var inputs = new ArrayList<PatternGeneratorFilter.Material>();
        var outputs = new ArrayList<PatternGeneratorFilter.Material>();
        var encodedInputs = new ArrayList<GenericStack>();
        var encodedOutputs = new ArrayList<GenericStack>();
        int circuit = appendItems(recipe.getInputContents(ItemRecipeCapability.CAP), inputs, encodedInputs, true);
        appendItems(recipe.getOutputContents(ItemRecipeCapability.CAP), outputs, encodedOutputs, false);
        appendFluids(recipe.getInputContents(FluidRecipeCapability.CAP), inputs, encodedInputs);
        appendFluids(recipe.getOutputContents(FluidRecipeCapability.CAP), outputs, encodedOutputs);
        return new Entry(recipe, circuit, List.copyOf(inputs), List.copyOf(outputs),
                List.copyOf(encodedInputs), List.copyOf(encodedOutputs));
    }

    public static ItemStack encodeDefinition(Entry entry, int scale, int outputScale) {
        GenericStack[] inputs = scale(entry.encodedInputs(), PatternGeneratorScale.clamp(scale));
        GenericStack[] outputs = scale(entry.encodedOutputs(), PatternGeneratorScale.outputFactor(scale, outputScale));
        ItemStack encoded = PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);
        return encoded == null ? ItemStack.EMPTY : encoded;
    }

    public static ItemStack encode(Entry entry, ServerPlayer player, int scale, int outputScale) {
        validate(entry, player);
        ItemStack encoded = encodeDefinition(entry, scale, outputScale);
        if (encoded.isEmpty()) return ItemStack.EMPTY;
        var pattern = new Ae2GtmProcessingPattern(encoded, player, entry.recipe());
        pattern.setLore(Component.translatable("gui.gtlcore.machine_colon")
                .append(Component.translatable(typeTranslation(entry.recipe().recipeType)))
                .append(Component.translatable("gui.gtlcore.circuit_format", entry.circuit())));
        int tier = entry.tier();
        pattern.setLore(Component.translatable("gui.gtlcore.voltage_colon", GTValues.VN[tier], RecipeHelper.getInputEUt(entry.recipe())));
        if (PatternGeneratorScale.clamp(scale) != 1 || PatternGeneratorScale.clamp(outputScale) != 1) {
            pattern.setLore(Component.translatable("gt_shanhai.gui.pattern_generator.lore_scale",
                    PatternGeneratorScale.clamp(scale), PatternGeneratorScale.outputFactor(scale, outputScale)));
        }
        ItemStack result = pattern.getPatternItemStack();
        PatternQuickUploadMetadata.writeRecipeTypeId(result, entry.recipe().recipeType.registryName);
        PatternEncoderMetadata.writeEncoder(result, player.getUUID(), player.getName().getString());
        PatternGeneratorTransfer.stampRecipe(result, entry.id());
        return result;
    }

    public static List<GenericStack> scaled(List<GenericStack> stacks, int factor) {
        var result = new ArrayList<GenericStack>(stacks.size());
        for (GenericStack stack : stacks) {
            result.add(new GenericStack(stack.what(), PatternGeneratorScale.apply(stack.amount(), factor)));
        }
        return result;
    }

    private static GenericStack[] scale(List<GenericStack> stacks, int factor) {
        return scaled(stacks, factor).toArray(GenericStack[]::new);
    }

    private static int appendItems(List<Content> contents, List<PatternGeneratorFilter.Material> materials,
                                   List<GenericStack> encoded, boolean input) {
        int circuit = 0;
        if (contents == null) return circuit;
        for (Content content : contents) {
            if (content == null) continue;
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length == 0 || stacks[0].isEmpty()) continue;
            for (ItemStack stack : stacks) {
                if (stack.isEmpty()) continue;
                materials.add(new PatternGeneratorFilter.Material(
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        false, BuiltInRegistries.ITEM.getId(stack.getItem())));
            }
            ItemStack chosen = input ? chooseCircuit(ingredient, stacks[0]) : stacks[0];
            long amount = content.getContent() instanceof LongIngredient longIngredient ? longIngredient.getActualAmount()
                    : content.getContent() instanceof SizedIngredient sized ? sized.getAmount() : stacks[0].getCount();
            encoded.add(new GenericStack(AEItemKey.of(chosen), Math.max(1L, amount)));
            if (IntCircuitBehaviour.isIntegratedCircuit(chosen)) {
                circuit = IntCircuitBehaviour.getCircuitConfiguration(chosen);
            }
        }
        return circuit;
    }

    private static void appendFluids(List<Content> contents, List<PatternGeneratorFilter.Material> materials,
                                     List<GenericStack> encoded) {
        if (contents == null) return;
        for (Content content : contents) {
            if (content == null) continue;
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            var stacks = ingredient.getStacks();
            if (stacks.length == 0 || stacks[0].isEmpty()) continue;
            for (var stack : stacks) {
                if (stack.isEmpty()) continue;
                materials.add(new PatternGeneratorFilter.Material(
                        BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString(),
                        true, BuiltInRegistries.FLUID.getId(stack.getFluid())));
            }
            var chosen = stacks[0];
            AEFluidKey key = chosen.hasTag() ? AEFluidKey.of(chosen.getFluid(), chosen.getTag()) : AEFluidKey.of(chosen.getFluid());
            encoded.add(new GenericStack(key, Math.max(1L, ingredient.getAmount())));
        }
    }

    private static ItemStack chooseCircuit(Ingredient ingredient, ItemStack fallback) {
        for (String tier : GTValues.VN) {
            var id = new ResourceLocation("kubejs", tier.toLowerCase(Locale.ROOT) + "_universal_circuit");
            if (!BuiltInRegistries.ITEM.containsKey(id)) continue;
            var candidate = new ItemStack(BuiltInRegistries.ITEM.get(id));
            if (ingredient.test(candidate)) return candidate;
        }
        return fallback;
    }

    public static void validate(Entry entry, ServerPlayer player) {
        if (player.server.getRecipeManager().byKey(entry.recipe().id).orElse(null) != entry.recipe()
                || entry.recipe().recipeType == null || entry.encodedOutputs().isEmpty())
            throw new IllegalArgumentException("Recipe missing or changed: " + entry.id());
        Entry canonical = describe(entry.recipe());
        if (!canonical.encodedInputs().equals(entry.encodedInputs())
                || !canonical.encodedOutputs().equals(entry.encodedOutputs()) || canonical.circuit() != entry.circuit())
            throw new IllegalArgumentException("Recipe contents changed: " + entry.id());
        for (GenericStack input : entry.encodedInputs()) {
            if (input == null || input.what() == null || input.amount() <= 0)
                throw new IllegalArgumentException("Invalid encoded input: " + entry.id());
        }
        for (GenericStack output : entry.encodedOutputs()) {
            if (output == null || output.what() == null || output.amount() <= 0)
                throw new IllegalArgumentException("Invalid encoded output: " + entry.id());
        }
    }
}
