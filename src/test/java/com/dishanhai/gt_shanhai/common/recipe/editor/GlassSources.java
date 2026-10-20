package com.dishanhai.gt_shanhai.common.recipe.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 玻璃 UI 源码契约测试共用的读文件工具。 */
final class GlassSources {

    static final Path JAVA = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai");
    static final Path KOTLIN = Path.of("src", "main", "kotlin", "com", "dishanhai", "gt_shanhai");

    private GlassSources() {}

    /** 工作区按 core.autocrlf 可能是 CRLF，换行归一后再比。 */
    static String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }
}
