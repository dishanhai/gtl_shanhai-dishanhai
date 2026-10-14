package com.dishanhai.gt_shanhai.common.item.generator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 本地 JSON 预设。只存配置和配方 ID，不存背包或正在进行的队列。 */
public final class PatternGeneratorPresetFiles {
    public static final int MAX_BYTES = 524288;
    public static final int MAX_RECIPES = 16384;
    public static final String FORMAT = "gt_shanhai:pattern_generator";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public record Preset(String name, String author, String authorId, String savedAt, String configuration, List<String> recipes) {
        public Preset {
            if (name == null || name.isBlank() || name.length() > 48 || author == null || author.length() > 64
                    || authorId == null || authorId.length() > 64 || savedAt == null || savedAt.length() > 64
                    || configuration == null || configuration.length() > 65536 || recipes == null || recipes.size() > MAX_RECIPES)
                throw new IllegalArgumentException("Invalid pattern preset");
            recipes = List.copyOf(recipes);
            if (recipes.stream().anyMatch(id -> id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || id.length() > 256))
                throw new IllegalArgumentException("Invalid recipe ID in pattern preset");
        }
    }

    public record Listing(List<Preset> presets, int unreadable) {}

    private PatternGeneratorPresetFiles() {}

    public static Listing list(Path directory) throws IOException {
        Files.createDirectories(directory);
        var presets = new ArrayList<Preset>();
        int unreadable = 0;
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().limit(512).toList()) {
                try {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES)
                        throw new IOException("Invalid preset file size/type");
                    presets.add(decode(Files.readString(file, StandardCharsets.UTF_8)));
                } catch (IOException | RuntimeException invalid) {
                    unreadable++;
                }
            }
        }
        presets.sort(Comparator.comparing(Preset::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Preset::author));
        return new Listing(List.copyOf(presets), unreadable);
    }

    public static String encode(Preset preset) {
        JsonObject json = GSON.toJsonTree(preset).getAsJsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("version", 1);
        return GSON.toJson(json) + "\n";
    }

    public static Preset decode(String text) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Preset too large");
        var json = JsonParser.parseString(text).getAsJsonObject();
        if (!json.has("format") || !FORMAT.equals(json.get("format").getAsString())
                || !json.has("version") || json.get("version").getAsInt() != 1)
            throw new IllegalArgumentException("Unsupported preset format");
        return GSON.fromJson(json, Preset.class);
    }

    public static Path save(Path directory, Preset preset) throws IOException {
        Files.createDirectories(directory);
        byte[] bytes = encode(preset).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Preset too large");
        String filename = "preset-" + UUID.randomUUID();
        Path temporary = directory.resolve(filename + ".tmp");
        Path destination = directory.resolve(filename + ".json");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
    }

    public static Preset create(String name, String author, String authorId, String configuration, List<String> recipes) {
        return new Preset(name.strip(), author, authorId, Instant.now().toString(), configuration, recipes);
    }
}
