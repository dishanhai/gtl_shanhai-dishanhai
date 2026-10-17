package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialModuleRemovalDropSourceTest {

    private static final Path BASE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialOmegaEngineModuleBase.java");
    private static final Path MODULES = BASE.getParent().resolve("module");

    @Test
    void controllerRemovalDropsInternalSlots() throws IOException {
        String source = Files.readString(BASE);
        String removed = extractBlock(source, "public void onMachineRemoved() {");

        assertTrue(removed.contains("super.onMachineRemoved();"));
        assertTrue(removed.contains("clearInventory(moduleSlot.storage);"),
                "物质模块槽必须在拆主机时掉落");
        assertTrue(removed.contains("clearInventory(threadBoostSlot.storage);"),
                "线程槽必须在拆主机时掉落");
        assertTrue(removed.contains("clearInventory(extraMountSlots.storage);"),
                "额外挂载槽必须在拆主机时掉落");
        assertTrue(removed.contains("getPersistedStorages()"),
                "子类自有槽（并行模块、电路等）必须跟着掉落");
        assertTrue(removed.contains("isBaseStorage(storage)"),
                "基类三槽已单独清空，不能再从子类列表里掉第二次");
    }

    @Test
    void subclassesDoNotReplaceRemovalHook() throws IOException {
        try (Stream<Path> files = Files.walk(MODULES)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String source;
                try {
                    source = Files.readString(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                assertFalse(source.contains("void onMachineRemoved("),
                        path + " 不能自行覆写 onMachineRemoved，否则会漏掉基类掉落");
            });
        }
    }

    private static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "missing declaration: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(openBrace, i + 1);
                }
            }
        }
        throw new AssertionError("unclosed block: " + declaration);
    }
}
