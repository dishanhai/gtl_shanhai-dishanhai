package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.block.ICoilType;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * JEI lines under a recipe, such as temperature, coil, and stellar containment tier.
 * They are recipe-type data infos over {@link GTRecipeType} NBT, not recipe conditions.
 */
public final class ShanhaiRecipeHeat {

    private static final String[] INT_KEYS = {
            "ebf_temp", "temperature",
            "SCTier", "CATier", "SEPMTier", "nano_forge_tier",
            "ev_min", "ev_max", "evt", "FRheat", "grindball"
    };
    private static final String[] LONG_KEYS = {"eu_to_start", "eu"};
    private static final Map<String, List<String>> KEYS = new HashMap<>();

    private ShanhaiRecipeHeat() {}

    public static boolean usesTemperature(String typeId) {
        return keysFor(typeId).contains("ebf_temp") || keysFor(typeId).contains("temperature");
    }

    public static List<String> keysFor(String typeId) {
        if (typeId == null || typeId.isEmpty()) return List.of();
        List<String> cached = KEYS.get(typeId);
        if (cached != null) return cached;
        GTRecipeType type = typeOf(typeId);
        List<String> keys = new ArrayList<>();
        if (type != null) {
            for (String key : INT_KEYS) {
                if (sensitive(type, key, false)) keys.add(key);
            }
            for (String key : LONG_KEYS) {
                if (sensitive(type, key, true)) keys.add(key);
            }
        }
        keys = List.copyOf(keys);
        KEYS.put(typeId, keys);
        return keys;
    }

    public static JsonObject capture(CompoundTag data) {
        JsonObject json = new JsonObject();
        if (data == null) return json;
        for (String key : data.getAllKeys()) {
            if (key == null || skip(key)) continue;
            if (data.contains(key, Tag.TAG_LONG) || data.contains(key, Tag.TAG_INT)
                    || data.contains(key, Tag.TAG_SHORT) || data.contains(key, Tag.TAG_BYTE)) {
                json.addProperty(key, data.getLong(key));
            }
        }
        return json;
    }

    public static void write(CompoundTag data, JsonObject values) {
        if (data == null || values == null) return;
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) continue;
            String key = entry.getKey();
            if (key == null || key.isEmpty() || skip(key)) continue;
            long value;
            try {
                value = entry.getValue().getAsLong();
            } catch (RuntimeException invalid) {
                continue;
            }
            if (isLongKey(key) || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
                data.putLong(key, value);
            } else {
                data.putInt(key, (int) value);
            }
        }
    }

    public static CompoundTag tagOf(JsonObject values) {
        CompoundTag data = new CompoundTag();
        write(data, values);
        return data;
    }

    public static List<String> lines(String typeId, int temperature) {
        CompoundTag data = new CompoundTag();
        if (temperature >= 0) data.putInt("ebf_temp", temperature);
        return lines(typeId, data);
    }

    public static List<String> lines(String typeId, CompoundTag data) {
        List<String> result = new ArrayList<>();
        GTRecipeType type = typeOf(typeId);
        if (type == null || type.getDataInfos() == null || data == null) return result;
        for (Function<CompoundTag, String> info : type.getDataInfos()) {
            if (info == null) continue;
            try {
                String line = info.apply(data);
                if (line != null && !line.isEmpty()) result.add(line);
            } catch (Throwable ignored) {
                // One broken data info must not hide the other requirements.
            }
        }
        return result;
    }

    public static String shortName(String key) {
        if (key == null) return "";
        return switch (key) {
            case "ebf_temp", "temperature" -> "温度";
            case "SCTier" -> "容器";
            case "CATier" -> "组件";
            case "SEPMTier" -> "模块";
            case "nano_forge_tier" -> "纳米";
            case "ev_min" -> "最小EV";
            case "ev_max" -> "最大EV";
            case "evt" -> "EVt";
            case "FRheat" -> "热量";
            case "grindball" -> "研磨球";
            case "eu_to_start" -> "启动EU";
            case "eu" -> "EU";
            default -> key;
        };
    }

    public static String keyOfShortName(String label) {
        if (label == null) return "";
        for (String key : INT_KEYS) {
            if (label.equals(shortName(key))) return key;
        }
        for (String key : LONG_KEYS) {
            if (label.equals(shortName(key))) return key;
        }
        return label;
    }

    public static boolean isTemperatureKey(String key) {
        return "ebf_temp".equals(key) || "temperature".equals(key);
    }

    /** Coil names with their temperatures, coldest first. Choosing one sets {@code ebf_temp}. */
    public static List<String> coilChoices() {
        List<String> choices = new ArrayList<>();
        ICoilType[] coils = ICoilType.ALL_COILS_TEMPERATURE_SORTED.get();
        if (coils == null) return choices;
        for (ICoilType coil : coils) {
            if (coil == null) continue;
            choices.add(coilChoice(coil));
        }
        return choices;
    }

    public static String coilFace(int temperature) {
        ICoilType coil = ICoilType.getMinRequiredType(Math.max(0, temperature));
        return coil == null ? Math.max(0, temperature) + "K" : coilChoice(coil);
    }

    public static int temperatureOfCoilChoice(String label) {
        if (label == null) return -1;
        int end = label.lastIndexOf('K');
        if (end <= 0) return -1;
        int start = end;
        while (start > 0 && Character.isDigit(label.charAt(start - 1))) start--;
        if (start == end) return -1;
        try {
            return Integer.parseInt(label.substring(start, end).trim());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static String coilChoice(ICoilType coil) {
        return coilName(coil) + " " + coil.getCoilTemperature() + "K";
    }

    private static String coilName(ICoilType coil) {
        Material material = coil.getMaterial();
        if (material != null) {
            String name = material.getLocalizedName().getString();
            if (name != null && !name.isEmpty()) return name;
        }
        String name = coil.getName();
        return name == null || name.isEmpty() ? "线圈" : name;
    }

    public static boolean isLongKey(String key) {
        for (String candidate : LONG_KEYS) {
            if (candidate.equals(key)) return true;
        }
        return false;
    }

    private static boolean sensitive(GTRecipeType type, String key, boolean wide) {
        CompoundTag low = new CompoundTag();
        CompoundTag high = new CompoundTag();
        if (wide) {
            low.putLong(key, 1L);
            high.putLong(key, 200_000_000L);
        } else {
            low.putInt(key, 1);
            high.putInt(key, 2);
        }
        return !format(type, low).equals(format(type, high));
    }

    private static String format(GTRecipeType type, CompoundTag data) {
        StringBuilder text = new StringBuilder();
        if (type.getDataInfos() == null) return "";
        for (Function<CompoundTag, String> info : type.getDataInfos()) {
            if (info == null) continue;
            try {
                String line = info.apply(data);
                text.append(line == null ? "" : line).append('\n');
            } catch (Throwable ignored) {
                text.append('\n');
            }
        }
        return text.toString();
    }

    private static boolean skip(String key) {
        return "batchSize".equals(key) || "ocTier".equals(key) || "ocLevel".equals(key);
    }

    private static GTRecipeType typeOf(String typeId) {
        ResourceLocation id = ResourceLocation.tryParse(typeId == null ? "" : typeId);
        return id == null ? null : GTRegistries.RECIPE_TYPES.get(id);
    }
}
