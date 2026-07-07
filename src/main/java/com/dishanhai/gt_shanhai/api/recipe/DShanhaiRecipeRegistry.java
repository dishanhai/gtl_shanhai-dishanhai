package com.dishanhai.gt_shanhai.api.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fluids.FluidType;

/**
 * 统一配方注册器。
 * 替代 KubeJS recipes.forEach 中的重复 if 判断和 null 检查。
 * <p>
 * KubeJS 用法（推荐）:
 * <pre>
 * var R = Java.loadClass('com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeRegistry');
 * recipes.forEach(r =&gt; R.register(r));
 * </pre>
 * <p>
 * 或 JSON 字符串:
 * <pre>
 * R.registerJson('{"type":"assembler","id":"my","itemInputs":["64x iron"],"EUt":30,"duration":100}');
 * </pre>
 */
public class DShanhaiRecipeRegistry {

    /** 从 JS 对象注册配方（推荐方式，KubeJS 数组传入即用） */
    public static void register(Object recipe) {
        try {
            String jsonStr = String.valueOf(recipe);
            JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
            registerJson(json);
        } catch (Exception ignored) {}
    }

    /** 从 JSON 字符串注册配方 */
    public static void registerJson(String json) {
        try {
            registerJson(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception ignored) {}
    }

    /** 从 JsonObject 注册配方 */
    public static void registerJson(JsonObject json) {
        try {
            String typeId = json.get("type").getAsString();
            String id = json.get("id").getAsString();
            int eut = json.get("EUt").getAsInt();
            int duration = json.get("duration").getAsInt();

            GTRecipeType type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(typeId));
            if (type == null) return;

            var builder = type.recipeBuilder(new ResourceLocation("dishanhai", id));

            // 可选字段
            if (json.has("circuit")) builder.circuitMeta(json.get("circuit").getAsInt());
            if (json.has("notConsumable")) {
                String nc = json.get("notConsumable").getAsString();
                if (!nc.isEmpty()) builder.notConsumable(parseItemStack(nc).getItem());
            }

            // 输入/输出数组
            addItemInputs(builder, json.get("itemInputs"));
            addFluidInputs(builder, json.get("inputFluids"));
            addItemOutputs(builder, json.get("itemOutputs"));
            addFluidOutputs(builder, json.get("outputFluids"));

            builder.EUt(eut).duration(duration);
            GTRecipe recipe = builder.buildRawRecipe();
            type.getLookup().addRecipe(recipe);
        } catch (Exception ignored) {}
    }

    // ========== 数组解析辅助 ==========

    private static void addItemInputs(GTRecipeBuilder builder, JsonElement arr) {
        if (arr == null || !arr.isJsonArray()) return;
        for (JsonElement e : arr.getAsJsonArray()) {
            String s = e.getAsString();
            if (!s.isEmpty()) builder.inputItems(parseIngredient(s));
        }
    }

    private static void addFluidInputs(GTRecipeBuilder builder, JsonElement arr) {
        if (arr == null || !arr.isJsonArray()) return;
        for (JsonElement e : arr.getAsJsonArray()) {
            String s = e.getAsString();
            if (!s.isEmpty()) builder.inputFluids(parseFluidStack(s));
        }
    }

    private static void addItemOutputs(GTRecipeBuilder builder, JsonElement arr) {
        if (arr == null || !arr.isJsonArray()) return;
        for (JsonElement e : arr.getAsJsonArray()) {
            String s = e.getAsString();
            if (!s.isEmpty()) builder.outputItems(parseItemStack(s));
        }
    }

    private static void addFluidOutputs(GTRecipeBuilder builder, JsonElement arr) {
        if (arr == null || !arr.isJsonArray()) return;
        for (JsonElement e : arr.getAsJsonArray()) {
            String s = e.getAsString();
            if (!s.isEmpty()) builder.outputFluids(parseFluidStack(s));
        }
    }

    // ========== 字段式注册（传参方式） ==========

    /** 注册单条配方（传参方式，兼容旧调用） */
    public static void register(String typeId, String id,
                                 Integer circuit, String notConsumable,
                                 String[] itemInputs, String[] inputFluids,
                                 String[] itemOutputs, String[] outputFluids,
                                 int eut, int duration) {
        try {
            GTRecipeType type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(typeId));
            if (type == null) {
                return;
            }
            var builder = type.recipeBuilder(new ResourceLocation("dishanhai", id));

            if (circuit != null && circuit > 0) {
                builder.circuitMeta(circuit);
            }
            if (notConsumable != null && !notConsumable.isEmpty()) {
                builder.notConsumable(parseItemStack(notConsumable).getItem());
            }
            if (itemInputs != null) {
                for (String s : itemInputs) {
                    if (s != null && !s.isEmpty()) {
                        builder.inputItems(parseIngredient(s));
                    }
                }
            }
            if (inputFluids != null) {
                for (String s : inputFluids) {
                    if (s != null && !s.isEmpty()) {
                        builder.inputFluids(parseFluidStack(s));
                    }
                }
            }
            if (itemOutputs != null) {
                for (String s : itemOutputs) {
                    if (s != null && !s.isEmpty()) {
                        builder.outputItems(parseItemStack(s));
                    }
                }
            }
            if (outputFluids != null) {
                for (String s : outputFluids) {
                    if (s != null && !s.isEmpty()) {
                        builder.outputFluids(parseFluidStack(s));
                    }
                }
            }

            builder.EUt(eut).duration(duration);
            GTRecipe recipe = builder.buildRawRecipe();
            type.getLookup().addRecipe(recipe);
        } catch (Exception ignored) {
            // 静默失败，与 KubeJS 的 try-catch 一致
        }
    }

    // ========== 解析器 ==========

    /**
     * 解析 "64x gtceu:iron_ingot" → ItemStack
     * 解析 "gtceu:iron_ingot" → ItemStack(count=1)
     */
    public static ItemStack parseItemStack(String str) {
        str = str.trim();
        int count = 1;
        int xIdx = str.indexOf('x');
        if (xIdx > 0) {
            String countStr = str.substring(0, xIdx).trim();
            try {
                count = Integer.parseInt(countStr);
            } catch (NumberFormatException ignored) {}
            str = str.substring(xIdx + 1).trim();
        }
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation(str));
        return new ItemStack(item, count);
    }

    /**
     * 解析 "64x gtceu:iron_ingot" → Ingredient
     */
    public static Ingredient parseIngredient(String str) {
        str = str.trim();
        int xIdx = str.indexOf('x');
        if (xIdx > 0) {
            str = str.substring(xIdx + 1).trim();
        }
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation(str));
        return Ingredient.of(item);
    }

    /**
     * 解析 "gtceu:water 1000" → FluidStack
     * 解析 "gtceu:water" → FluidStack(amount=1000)
     */
    public static FluidStack parseFluidStack(String str) {
        str = str.trim();
        int amount = FluidType.BUCKET_VOLUME;
        String[] parts = str.split("\\s+");
        String id = parts[0];
        if (parts.length > 1) {
            try {
                amount = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {}
        }
        var fluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(new ResourceLocation(id));
        return FluidStack.create(fluid, amount);
    }
}
