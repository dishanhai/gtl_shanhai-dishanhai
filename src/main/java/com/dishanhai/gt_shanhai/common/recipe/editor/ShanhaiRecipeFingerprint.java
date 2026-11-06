package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ShanhaiRecipeFingerprint {

    public static final String PREFIX = "v4.sorted:sha256:";
    private static final String LEGACY_PREFIX = "v3.sorted:json:";

    private ShanhaiRecipeFingerprint() {}

    public static String of(ShanhaiRecipeBase base) {
        return fingerprint(base == null ? JsonNull.INSTANCE : base.fingerprintJson());
    }

    public static String legacySnapshotFingerprint(ShanhaiRecipeBase base) {
        if (base == null) return legacyFingerprint(JsonNull.INSTANCE);
        JsonObject payload = new JsonObject();
        payload.addProperty("recipeTypeId", base.recipeTypeId());
        payload.addProperty("recipeId", base.recipeId());
        payload.addProperty("duration", base.duration());
        payload.addProperty("eut", base.eut());
        payload.add("inputs", base.inputs());
        payload.add("outputs", base.outputs());
        payload.add("tickInputs", base.tickInputs());
        payload.add("conditions", new JsonArray());
        return legacyFingerprint(payload);
    }

    /** Previous full snapshot format; read-only compatibility for existing override files. */
    public static String legacyV3Fingerprint(ShanhaiRecipeBase base) {
        return legacyFingerprint(base == null ? JsonNull.INSTANCE : base.fingerprintJson());
    }

    private static String legacyFingerprint(JsonElement value) {
        String serialized = new GsonBuilder().disableHtmlEscaping().create().toJson(canonicalize(value));
        if (serialized.length() > 400) {
            serialized = serialized.substring(0, 220) + "~~" + serialized.substring(serialized.length() - 180);
        }
        return LEGACY_PREFIX + serialized;
    }

    public static String fingerprint(JsonElement value) {
        String serialized = new GsonBuilder().disableHtmlEscaping().create().toJson(canonicalize(value));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(serialized.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
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
