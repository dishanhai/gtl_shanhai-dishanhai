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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ShanhaiRecipeOverrideStore {

    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path path;

    public ShanhaiRecipeOverrideStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    /**
     * {@code keepOriginal} is only meaningful when {@code sourceRecipeId} differs from
     * {@code recipeId}: the entry is then an added recipe. Same ids stay an in-place replacement.
     */
    public record Entry(
            String recipeTypeId,
            String recipeId,
            String baseFingerprint,
            JsonObject payload,
            boolean keepOriginal,
            String sourceRecipeId) {

        public Entry(String recipeTypeId, String recipeId, String baseFingerprint, JsonObject payload) {
            this(recipeTypeId, recipeId, baseFingerprint, payload, false, recipeId);
        }

        public Entry {
            if (sourceRecipeId == null || sourceRecipeId.isEmpty()) {
                sourceRecipeId = recipeId == null ? "" : recipeId;
            }
        }
    }

    public Optional<Entry> find(String recipeId) {
        return find("", recipeId);
    }

    public Optional<Entry> find(String recipeTypeId, String recipeId) {
        if (recipeId == null || !Files.isRegularFile(path)) return Optional.empty();
        try {
            JsonObject root = loadRoot();
            Entry fallback = null;
            for (JsonElement element : root.getAsJsonArray("entries")) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (recipeId.equals(string(object, "recipeId", ""))) {
                    JsonObject payload = object.has("payload") && object.get("payload").isJsonObject()
                            ? object.getAsJsonObject("payload").deepCopy() : new JsonObject();
                    Entry entry = readEntry(object, recipeId, payload);
                    if (recipeTypeId != null && !recipeTypeId.isEmpty()
                            && recipeTypeId.equals(entry.recipeTypeId())) {
                        return Optional.of(entry);
                    }
                    if (fallback == null) fallback = entry;
                }
            }
            if (fallback != null) return Optional.of(fallback);
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public Set<String> typeIds() {
        if (!Files.isRegularFile(path)) return Set.of();
        try {
            JsonObject root = loadRoot();
            Set<String> result = new LinkedHashSet<>();
            for (JsonElement element : root.getAsJsonArray("entries")) {
                if (!element.isJsonObject()) continue;
                String typeId = storedType(element.getAsJsonObject());
                if (!typeId.isEmpty()) result.add(typeId);
            }
            return Set.copyOf(result);
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    public Map<String, Entry> entriesForType(String recipeTypeId) {
        if (recipeTypeId == null || recipeTypeId.isEmpty() || !Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            JsonObject root = loadRoot();
            Map<String, Entry> result = new LinkedHashMap<>();
            for (JsonElement element : root.getAsJsonArray("entries")) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (!recipeTypeId.equals(storedType(object))) continue;
                String recipeId = string(object, "recipeId", "");
                if (recipeId.isEmpty()) continue;
                JsonObject payload = object.has("payload") && object.get("payload").isJsonObject()
                        ? object.getAsJsonObject("payload").deepCopy() : new JsonObject();
                result.putIfAbsent(recipeId, readEntry(object, recipeId, payload));
            }
            return Map.copyOf(result);
        } catch (Exception ignored) {
            return Map.of();
        }
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
            boolean sameId = base.recipeId().equals(string(object, "recipeId", ""));
            boolean sameType = base.recipeTypeId().equals(storedType(object));
            if (!(sameId && sameType)) kept.add(object);
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

    /**
     * Writes a recipe that lives under {@code liveRecipeId} and was cloned from
     * {@code sourceRecipeId}. Does not delete an in-place replacement of the source.
     */
    public void putLinked(
            ShanhaiRecipeBase opened,
            String fingerprint,
            String liveRecipeId,
            String sourceRecipeId,
            boolean keepOriginal,
            String updatedBy) throws IOException {
        JsonObject root = loadRoot();
        JsonArray kept = new JsonArray();
        for (JsonElement element : root.getAsJsonArray("entries")) {
            if (!element.isJsonObject()) {
                kept.add(element);
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            boolean sameType = opened.recipeTypeId().equals(storedType(object));
            String id = string(object, "recipeId", "");
            if (sameType && (id.equals(liveRecipeId) || id.equals(opened.recipeId()))) {
                String storedSource = string(object, "sourceRecipeId", id);
                boolean addition = !storedSource.equals(id);
                if (id.equals(liveRecipeId) || addition) continue;
            }
            kept.add(object);
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("recipeId", liveRecipeId);
        entry.addProperty("sourceRecipeId", sourceRecipeId == null ? opened.recipeId() : sourceRecipeId);
        entry.addProperty("keepOriginal", keepOriginal);
        entry.addProperty("recipeTypeId", opened.recipeTypeId());
        entry.addProperty("baseFingerprint", fingerprint);
        entry.addProperty("updatedBy", updatedBy == null ? "unknown" : updatedBy);
        entry.addProperty("updatedAt", java.time.Instant.now().toString());
        entry.add("payload", opened.payloadJson());
        kept.add(entry);
        root.add("entries", kept);
        writeRoot(root);
    }

    public void remove(String recipeId) throws IOException {
        remove("", recipeId);
    }

    public void remove(String recipeTypeId, String recipeId) throws IOException {
        if (recipeId == null) return;
        JsonObject root = loadRoot();
        JsonArray kept = new JsonArray();
        for (JsonElement element : root.getAsJsonArray("entries")) {
            if (!element.isJsonObject()) {
                kept.add(element);
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            boolean sameId = recipeId.equals(string(object, "recipeId", ""));
            boolean sameType = recipeTypeId == null || recipeTypeId.isEmpty()
                    || recipeTypeId.equals(storedType(object));
            if (!(sameId && sameType)) kept.add(object);
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

    private static Entry readEntry(JsonObject object, String recipeId, JsonObject payload) {
        String source = string(object, "sourceRecipeId", recipeId);
        boolean keepOriginal = object.has("keepOriginal")
                && object.get("keepOriginal").isJsonPrimitive()
                && object.get("keepOriginal").getAsBoolean();
        return new Entry(
                storedType(object),
                recipeId,
                string(object, "baseFingerprint", ""),
                payload,
                keepOriginal,
                source);
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
    }

    private static String storedType(JsonObject object) {
        String typeId = string(object, "recipeTypeId", "");
        if (!typeId.isEmpty()) return typeId;
        JsonElement payload = object.get("payload");
        return payload != null && payload.isJsonObject()
                ? string(payload.getAsJsonObject(), "recipeTypeId", "") : "";
    }
}
