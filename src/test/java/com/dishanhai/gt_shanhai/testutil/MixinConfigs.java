package com.dishanhai.gt_shanhai.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 源码断言用：把 {@code src/main/resources} 下所有 {@code gt_shanhai*.mixin.json} 拼成一份文本。
 *
 * <p>主配置已按注入模组拆分，断言"某 mixin 已注册"时不能再只读 {@code gt_shanhai.mixin.json}。
 */
public final class MixinConfigs {

    private static final Path DIR = Path.of("src", "main", "resources");

    private MixinConfigs() {
    }

    public static String readAll() {
        try (var stream = Files.list(DIR)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(MixinConfigs::isMixinConfig)
                    .toList());
            files.sort(Comparator.comparing(Path::toString));
            StringBuilder text = new StringBuilder();
            for (Path file : files) {
                text.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
            return text.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isMixinConfig(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("gt_shanhai") && name.endsWith(".mixin.json");
    }
}
