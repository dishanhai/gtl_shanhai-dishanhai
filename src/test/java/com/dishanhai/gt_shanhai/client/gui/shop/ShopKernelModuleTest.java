package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.sh_better_ui.kernel.api.UIApis;
import com.dishanhai.sh_better_ui.kernel.api.UIModule;
import com.dishanhai.sh_better_ui.kernel.api.UiId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：验证 Forge 能从本模组的资源描述符找到商店内核入口。 */
class ShopKernelModuleTest {
    @Test
    void moduleDescriptorResolvesToAnOwnedNativeScreenConsumer() throws Exception {
        String[] declaration = java.nio.file.Files.readAllLines(java.nio.file.Path.of(
                        "src/main/resources/META-INF/sh_better_ui.modules"), StandardCharsets.UTF_8)
                .stream().map(String::strip).filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.split("\\s+"))
                .filter(parts -> parts.length == 2 && parts[1].equals(ShopKernelModule.class.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("商店内核模块须有独立资源声明"));
        assertEquals("gt_shanhai", declaration[0]);
        var module = Class.forName(declaration[1]).asSubclass(UIModule.class).getConstructor().newInstance();
        var descriptor = module.descriptor();
        assertEquals(new UiId(declaration[0], "shop"), descriptor.id());
        assertTrue(descriptor.requiredCapabilities().containsKey(UIApis.NATIVE_SCREEN));
        assertTrue(descriptor.requestedPermissions().isEmpty());
    }
}
