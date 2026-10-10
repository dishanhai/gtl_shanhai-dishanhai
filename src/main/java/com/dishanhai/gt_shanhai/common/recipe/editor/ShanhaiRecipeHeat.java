package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * JEI lines such as temperature and coil tier. They come from the recipe type's
 * data infos and the recipe NBT key {@code ebf_temp}, not from recipe conditions.
 */
public final class ShanhaiRecipeHeat {

    private static final Map<String, Boolean> USES_TEMPERATURE = new HashMap<>();

    private ShanhaiRecipeHeat() {}

    public static boolean usesTemperature(String typeId) {
        if (typeId == null || typeId.isEmpty()) return false;
        Boolean cached = USES_TEMPERATURE.get(typeId);
        if (cached != null) return cached;
        boolean uses = !lines(typeId, 0).equals(lines(typeId, 5400));
        USES_TEMPERATURE.put(typeId, uses);
        return uses;
    }

    public static List<String> lines(String typeId, int temperature) {
        List<String> result = new ArrayList<>();
        if (temperature < 0) return result;
        ResourceLocation id = ResourceLocation.tryParse(typeId == null ? "" : typeId);
        if (id == null) return result;
        GTRecipeType type = GTRegistries.RECIPE_TYPES.get(id);
        if (type == null || type.getDataInfos() == null) return result;
        CompoundTag data = new CompoundTag();
        data.putInt("ebf_temp", temperature);
        for (Function<CompoundTag, String> info : type.getDataInfos()) {
            if (info == null) continue;
            try {
                String line = info.apply(data);
                if (line != null && !line.isEmpty()) result.add(line);
            } catch (Throwable ignored) {
                // One broken data info must not hide the temperature field.
            }
        }
        return result;
    }

    public static String temperatureLine(String typeId, int temperature) {
        List<String> lines = lines(typeId, temperature);
        if (!lines.isEmpty()) return lines.get(0);
        return temperature < 0 ? "" : "温度: " + temperature + "K";
    }

    public static String coilLine(String typeId, int temperature) {
        List<String> lines = lines(typeId, temperature);
        return lines.size() >= 2 ? lines.get(1) : "";
    }
}
