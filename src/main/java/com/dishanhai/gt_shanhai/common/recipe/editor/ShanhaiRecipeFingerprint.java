package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ShanhaiRecipeFingerprint {

    public static final String PREFIX = "v3.sorted:json:";

    private ShanhaiRecipeFingerprint() {}

    public static String of(ShanhaiRecipeBase base) {
        return fingerprint(base == null ? JsonNull.INSTANCE : base.payloadJson());
    }

    public static String legacySnapshotFingerprint(ShanhaiRecipeBase base) {
        if (base == null) return fingerprint(JsonNull.INSTANCE);
        JsonObject payload = new JsonObject();
        payload.addProperty("recipeTypeId", base.recipeTypeId());
        payload.addProperty("recipeId", base.recipeId());
        payload.addProperty("duration", base.duration());
        payload.addProperty("eut", base.eut());
        payload.add("inputs", base.inputs());
        payload.add("outputs", base.outputs());
        payload.add("tickInputs", base.tickInputs());
        payload.add("conditions", new JsonArray());
        return fingerprint(payload);
    }

    public static String fingerprint(JsonElement value) {
        JsonElement canonical = canonicalize(value);
        String serialized = new GsonBuilder().disableHtmlEscaping().create().toJson(canonical);
        if (serialized.length() > 400) {
            serialized = serialized.substring(0, 220) + "~~" + serialized.substring(serialized.length() - 180);
        }
        return PREFIX + serialized;
    }

    public static JsonElement canonicalize(JsonElement value) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) array.add(canonicalize(element));
            return array;
        }
        if (!value.isJsonObject()) return value;
        JsonObject input = value.getAsJsonObject();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : input.entrySet()) keys.add(entry.getKey());
        keys.sort(String::compareTo);
        JsonObject output = new JsonObject();
        for (String key : keys) output.add(key, canonicalize(input.get(key)));
        return output;
    }
}
