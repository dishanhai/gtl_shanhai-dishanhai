package com.dishanhai.gt_shanhai.mixin.replay;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.refmap.ReferenceMapper;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ReplayRefmapWrapperTest {
    @Test void developmentNamesStayNamedWhileProductionUsesSrg() {
        var stream = getClass().getResourceAsStream("/gt_shanhai.replay.refmap.json");
        assertNotNull(stream);
        var map = ReferenceMapper.read(new InputStreamReader(stream, StandardCharsets.UTF_8), "replay");
        String owner = "com/dishanhai/gt_shanhai/mixin/replay/ShanhaiServerLevelTickClockMixin";
        String method = "tick(Ljava/util/function/BooleanSupplier;)V";
        assertEquals(method, new ReplayRefmapWrapper(map, false).remap(owner, method));
        assertEquals("Lnet/minecraft/server/level/ServerLevel;m_8793_(Ljava/util/function/BooleanSupplier;)V",
                new ReplayRefmapWrapper(map, true).remap(owner, method));
        String accessor = "com/dishanhai/gt_shanhai/mixin/replay/ShanhaiParticleAccessor";
        assertEquals("age", new ReplayRefmapWrapper(map, false).remap(accessor, "age"));
        assertEquals("f_107224_:I", new ReplayRefmapWrapper(map, true).remap(accessor, "age"));
    }

    @Test void everyReplayInjectionAndAccessorIsInThePackagedMap() throws Exception {
        Path dir = Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin/replay");
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/gt_shanhai.replay.refmap.json"), StandardCharsets.UTF_8);
             var files = Files.list(dir)) {
            var mappings = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("mappings");
            var inject = Pattern.compile("@Inject\\s*\\(\\s*method\\s*=\\s*((?:\"[^\"]*\"\\s*\\+?\\s*)+)");
            var quoted = Pattern.compile("\"([^\"]*)\"");
            var accessor = Pattern.compile("@Accessor\\(\"([^\"]+)\"\\)");
            int classes = 0;
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!source.contains("@Mixin(")) continue;
                classes++;
                String simpleName = file.getFileName().toString().replace(".java", "");
                var entries = mappings.getAsJsonObject("com/dishanhai/gt_shanhai/mixin/replay/" + simpleName);
                assertNotNull(entries, simpleName);
                var selectors = inject.matcher(source);
                while (selectors.find()) {
                    var parts = quoted.matcher(selectors.group(1));
                    StringBuilder reference = new StringBuilder();
                    while (parts.find()) reference.append(parts.group(1));
                    assertTrue(entries.has(reference.toString()), simpleName + ": " + reference);
                }
                var fields = accessor.matcher(source);
                while (fields.find()) assertTrue(entries.has(fields.group(1)), simpleName + ": " + fields.group(1));
                assertFalse(source.contains("@At(value = \"INVOKE\""), simpleName + " needs an invoke target mapping");
            }
            assertEquals(36, classes);
        }
    }
}
