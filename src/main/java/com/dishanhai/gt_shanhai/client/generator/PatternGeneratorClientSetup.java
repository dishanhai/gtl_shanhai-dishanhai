package com.dishanhai.gt_shanhai.client.generator;

import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorClientHooks;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorFilter;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorPresetFiles;

import java.io.IOException;

/** 客户端启动时挂上译名搜索和预设目录。 */
public final class PatternGeneratorClientSetup {
    private PatternGeneratorClientSetup() {}

    public static void install() {
        PatternGeneratorClientHooks.install(new PatternGeneratorClientHooks.Hooks() {
            @Override
            public PatternGeneratorFilter.NameHits find(String query) {
                return PatternGeneratorNameSearch.find(query);
            }

            @Override
            public PatternGeneratorPresetFiles.Listing list() throws IOException {
                return PatternGeneratorPresets.list();
            }

            @Override
            public void save(PatternGeneratorPresetFiles.Preset preset) throws IOException {
                PatternGeneratorPresets.save(preset);
            }

            @Override
            public void openDirectory() throws IOException {
                PatternGeneratorPresets.openDirectory();
            }
        });
    }
}
