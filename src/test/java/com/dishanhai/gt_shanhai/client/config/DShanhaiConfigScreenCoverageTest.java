package com.dishanhai.gt_shanhai.client.config;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可视化配置屏必须覆盖 {@link DShanhaiConfig.ConfigValues} 的每一个公共配置项。
 * 新增 toml 项后若忘记注册到 {@link DShanhaiConfigScreen}，此测试会失败。
 */
class DShanhaiConfigScreenCoverageTest {

    @Test
    void everyCommonConfigValueIsRegisteredOnTheScreen() throws IOException {
        String screen = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/config/DShanhaiConfigScreen.java"));
        int checked = 0;
        for (Field field : DShanhaiConfig.ConfigValues.class.getDeclaredFields()) {
            if (!ForgeConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
                continue;
            }
            checked++;
            String name = field.getName();
            assertTrue(screen.contains("cfg." + name + ".get()"), "配置界面未读取: " + name);
            assertTrue(screen.contains("cfg." + name + "::set") || screen.contains("cfg." + name + ".set"),
                    "配置界面未写回: " + name);
        }
        assertTrue(checked >= 44, "配置项数量异常，实际 " + checked);
    }
}
