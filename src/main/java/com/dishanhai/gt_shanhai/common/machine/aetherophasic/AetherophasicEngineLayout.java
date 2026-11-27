package com.dishanhai.gt_shanhai.common.machine.aetherophasic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.StreamSupport;

/** 山海：以太相引擎 JSON 数据；调色板 A 起编号，切片保持 NBT 的 [z][y][x] 原序。 */
final class AetherophasicEngineLayout {
    static final String RESOURCE = "/assets/gt_shanhai/multiblock/aetherophasic_engine.json";

    final List<JsonObject> palette;
    final String[][] aisles;
    final char controllerSymbol;
    final char hatchSymbol;

    private AetherophasicEngineLayout(JsonObject root) {
        if (root.get("format_version").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported aetherophasic structure format");
        }
        var size = root.getAsJsonArray("size");
        if (size.size() != 3) throw new IllegalArgumentException("Expected X/Y/Z size");
        int width = size.get(0).getAsInt();
        int height = size.get(1).getAsInt();
        int depth = size.get(2).getAsInt();
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Invalid aetherophasic structure size");
        }
        palette = StreamSupport.stream(root.getAsJsonArray("palette").spliterator(), false)
                .map(entry -> entry.getAsJsonObject()).toList();
        if (palette.isEmpty() || palette.size() > 26) {
            throw new IllegalArgumentException("Invalid aetherophasic palette size");
        }
        controllerSymbol = root.get("controller_symbol").getAsCharacter();
        hatchSymbol = root.get("hatch_symbol").getAsCharacter();
        if (controllerSymbol == hatchSymbol || controllerSymbol < 'A' || hatchSymbol < 'A'
                || controllerSymbol >= 'A' + palette.size() || hatchSymbol >= 'A' + palette.size()) {
            throw new IllegalArgumentException("Invalid aetherophasic marker symbols");
        }
        var data = root.getAsJsonArray("aisles");
        if (data.size() != depth) throw new IllegalArgumentException("Incorrect aisle count");
        aisles = new String[depth][height];
        int controllers = 0;
        int hatches = 0;
        var controller = root.getAsJsonArray("controller");
        if (controller.size() != 3) throw new IllegalArgumentException("Expected controller X/Y/Z");
        for (int z = 0; z < depth; z++) {
            var rows = data.get(z).getAsJsonArray();
            if (rows.size() != height) throw new IllegalArgumentException("Incorrect row count at z=" + z);
            for (int y = 0; y < height; y++) {
                String row = rows.get(y).getAsString();
                if (row.length() != width) throw new IllegalArgumentException("Incorrect row width at z=" + z + ", y=" + y);
                aisles[z][y] = row;
                for (int x = 0; x < width; x++) {
                    char symbol = row.charAt(x);
                    if (symbol != ' ' && (symbol < 'A' || symbol >= 'A' + palette.size())) {
                        throw new IllegalArgumentException("Unknown aetherophasic symbol: " + symbol);
                    }
                    if (symbol == hatchSymbol) hatches++;
                    if (symbol == controllerSymbol) {
                        controllers++;
                        if (x != controller.get(0).getAsInt() || y != controller.get(1).getAsInt()
                                || z != controller.get(2).getAsInt()) {
                            throw new IllegalArgumentException("Controller position differs from structure");
                        }
                    }
                }
            }
        }
        if (controllers != 1 || hatches != 6) {
            throw new IllegalArgumentException("Expected one controller and six hatch positions");
        }
    }

    static AetherophasicEngineLayout read(Reader reader) {
        return new AetherophasicEngineLayout(JsonParser.parseReader(reader).getAsJsonObject());
    }

    static AetherophasicEngineLayout load() {
        try (var input = AetherophasicEngineLayout.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing structure: " + RESOURCE);
            return read(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read structure: " + RESOURCE, error);
        }
    }
}
