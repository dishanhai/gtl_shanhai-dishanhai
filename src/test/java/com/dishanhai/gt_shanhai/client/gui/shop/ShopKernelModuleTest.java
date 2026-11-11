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
        var loader = getClass().getClassLoader();
        String[] declaration;
        try (var resource = loader.getResourceAsStream("META-INF/sh_better_ui.modules")) {
            assertNotNull(resource, "商店内核模块须由模组 JAR 的资源描述符发现");
            declaration = new String(resource.readAllBytes(), StandardCharsets.UTF_8).strip().split("\\s+");
        }
        assertEquals(2, declaration.length);
        assertEquals("gt_shanhai", declaration[0]);
        var module = Class.forName(declaration[1]).asSubclass(UIModule.class).getConstructor().newInstance();
        var descriptor = module.descriptor();
        assertEquals(new UiId(declaration[0], "shop"), descriptor.id());
        assertTrue(descriptor.requiredCapabilities().containsKey(UIApis.NATIVE_SCREEN));
        assertTrue(descriptor.requestedPermissions().isEmpty());
    }
}
