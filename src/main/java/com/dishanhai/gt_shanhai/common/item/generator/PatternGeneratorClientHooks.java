package com.dishanhai.gt_shanhai.common.item.generator;

import java.io.IOException;

/**
 * 客户端文件和译名搜索的入口。服务端只持有空实现，避免加载 Minecraft 客户端类。
 */
public final class PatternGeneratorClientHooks {
    public interface Hooks {
        PatternGeneratorFilter.NameHits find(String query);

        PatternGeneratorPresetFiles.Listing list() throws IOException;

        void save(PatternGeneratorPresetFiles.Preset preset) throws IOException;

        void openDirectory() throws IOException;
    }

    private static final Hooks NONE = new Hooks() {
        @Override
        public PatternGeneratorFilter.NameHits find(String query) {
            return PatternGeneratorFilter.NameHits.empty();
        }

        @Override
        public PatternGeneratorPresetFiles.Listing list() {
            return new PatternGeneratorPresetFiles.Listing(java.util.List.of(), 0);
        }

        @Override
        public void save(PatternGeneratorPresetFiles.Preset preset) throws IOException {
            throw new IOException("Pattern presets are client-only");
        }

        @Override
        public void openDirectory() throws IOException {
            throw new IOException("Pattern presets are client-only");
        }
    };

    private static Hooks hooks = NONE;

    private PatternGeneratorClientHooks() {}

    public static void install(Hooks value) {
        hooks = value == null ? NONE : value;
    }

    public static Hooks get() {
        return hooks;
    }
}
