package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class ShanhaiRecipeOverrideStore {

    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path path;

    public ShanhaiRecipeOverrideStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public record Entry(String recipeId, String baseFingerprint, JsonObject payload) {}

    public Optional<Entry> find(String recipeId) {
        if (recipeId == null || !Files.isRegularFile(path)) return Optional.empty();
        try {
            JsonObject root = loadRoot();
            for (JsonElement element : root.getAsJsonArray("entries")) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (recipeId.equals(string(object, "recipeId", ""))) {
                    JsonObject payload = object.has("payload") && object.get("payload").isJsonObject()
                            ? object.getAsJsonObject("payload").deepCopy() : new JsonObject();
                    return Optional.of(new Entry(
                            recipeId,
                            string(object, "baseFingerprint", ""),
                            payload));
                }
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public void put(ShanhaiRecipeBase base, String fingerprint, String updatedBy) throws IOException {
        JsonObject root = loadRoot();
        JsonArray entries = root.getAsJsonArray("entries");
        JsonArray kept = new JsonArray();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                kept.add(element);
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            if (!base.recipeId().equals(string(object, "recipeId", ""))) kept.add(object);
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("recipeId", base.recipeId());
        entry.addProperty("recipeTypeId", base.recipeTypeId());
        entry.addProperty("baseFingerprint", fingerprint);
        entry.addProperty("updatedBy", updatedBy == null ? "unknown" : updatedBy);
        entry.addProperty("updatedAt", java.time.Instant.now().toString());
        entry.add("payload", base.payloadJson());
        kept.add(entry);
        root.add("entries", kept);
        writeRoot(root);
    }

    public void remove(String recipeId) throws IOException {
        if (recipeId == null) return;
        JsonObject root = loadRoot();
        JsonArray kept = new JsonArray();
        for (JsonElement element : root.getAsJsonArray("entries")) {
            if (!element.isJsonObject()
                    || !recipeId.equals(string(element.getAsJsonObject(), "recipeId", ""))) {
                kept.add(element);
            }
        }
        root.add("entries", kept);
        writeRoot(root);
    }

    private JsonObject loadRoot() throws IOException {
        if (!Files.isRegularFile(path)) {
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", SCHEMA_VERSION);
            root.add("entries", new JsonArray());
            return root;
        }
        JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) throw new IOException("override root is not object");
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("entries") || !root.get("entries").isJsonArray()) root.add("entries", new JsonArray());
        return root;
    }

    private void writeRoot(JsonObject root) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
    }
}
