package com.dishanhai.gt_shanhai.common.machine.primordial.module.collector;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrimordialSixfoldResourceDependencyTest {

    private static final Path MODS_TOML = Path.of("src", "main", "resources", "META-INF", "mods.toml");

    @Test
    void gtlExtendIsAMandatoryEarlierLoadingDependency() throws Exception {
        List<Map<String, String>> dependencies = parseDependencyBlocks(Files.readAllLines(MODS_TOML));
        List<Map<String, String>> gtlExtendDependencies = dependencies.stream()
                .filter(dependency -> "gtl_extend".equals(dependency.get("modId")))
                .toList();

        assertEquals(1, gtlExtendDependencies.size(), "gtl_extend 依赖必须且只能声明一次");
        Map<String, String> dependency = gtlExtendDependencies.get(0);
        assertEquals("true", dependency.get("mandatory"));
        assertEquals("[0,)", dependency.get("versionRange"));
        assertEquals("AFTER", dependency.get("ordering"));
        assertEquals("BOTH", dependency.get("side"));
    }

    private static List<Map<String, String>> parseDependencyBlocks(List<String> lines) {
        List<Map<String, String>> dependencies = new ArrayList<>();
        Map<String, String> current = null;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.equals("[[mods.dependencies]]")) {
                current = new LinkedHashMap<>();
                dependencies.add(current);
            } else if (line.startsWith("[[") || line.startsWith("[")) {
                current = null;
            } else if (current != null && line.contains("=")) {
                int separator = line.indexOf('=');
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                current.put(key, value);
            }
        }
        return dependencies;
    }
}
