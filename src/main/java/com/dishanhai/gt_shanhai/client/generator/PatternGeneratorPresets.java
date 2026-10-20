package com.dishanhai.gt_shanhai.client.generator;

import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorPresetFiles;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 只由客户端菜单调用。服务器数据包不能指定文件路径。 */
public final class PatternGeneratorPresets {
    private PatternGeneratorPresets() {}

    public static Path directory() {
        return com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.ensureDir(
                com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.patternGeneratorPresetsDir());
    }

    public static PatternGeneratorPresetFiles.Listing list() throws IOException {
        return PatternGeneratorPresetFiles.list(directory());
    }

    public static void save(PatternGeneratorPresetFiles.Preset preset) throws IOException {
        PatternGeneratorPresetFiles.save(directory(), preset);
    }

    public static void openDirectory() throws IOException {
        Files.createDirectories(directory());
        Util.getPlatform().openFile(directory().toFile());
    }
}
