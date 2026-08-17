package com.dishanhai.gt_shanhai.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualItemProviderRendererSourceTest {

    private static final Path ROOT = Path.of("src", "main");
    private static final Path ITEM = ROOT.resolve(Path.of("java", "com", "dishanhai", "gt_shanhai",
            "common", "item", "VirtualItemProviderItem.java"));
    private static final Path RENDERER = ROOT.resolve(Path.of("java", "com", "dishanhai", "gt_shanhai",
            "client", "renderer", "item", "VirtualItemProviderRenderer.java"));
    private static final Path MODEL_REGISTRATION = ROOT.resolve(Path.of("java", "com", "dishanhai", "gt_shanhai",
            "client", "renderer", "item", "VirtualItemProviderModelRegistration.java"));
    private static final Path ITEM_MODEL = ROOT.resolve(Path.of("resources", "assets", "gt_shanhai",
            "models", "item", "virtual_item_provider.json"));
    private static final Path BASE_MODEL = ROOT.resolve(Path.of("resources", "assets", "gt_shanhai",
            "models", "item", "virtual_item_provider_base.json"));

    @Test
    void boundProviderUsesTargetAsGuiMainIconAndProviderAsTopRightBadge() throws IOException {
        assertTrue(Files.exists(RENDERER), "必须新增虚拟物品提供器客户端渲染器");
        assertTrue(Files.exists(MODEL_REGISTRATION), "必须注册提供器的普通基础模型");
        assertTrue(Files.exists(BASE_MODEL), "必须保留非 GUI 场景使用的普通基础模型");

        String item = Files.readString(ITEM);
        String renderer = Files.readString(RENDERER);
        String registration = Files.readString(MODEL_REGISTRATION);
        String itemModel = Files.readString(ITEM_MODEL);
        String baseModel = Files.readString(BASE_MODEL);

        assertTrue(item.contains("void initializeClient(Consumer<IClientItemExtensions> consumer)"),
                "物品必须通过 Forge 客户端扩展接入自定义渲染器");
        assertTrue(item.contains("VirtualItemProviderRenderer.getInstance()"),
                "客户端扩展必须返回虚拟物品提供器渲染器");

        assertTrue(renderer.contains("pDisplayContext == ItemDisplayContext.GUI"),
                "复合图标只能在 GUI 显示上下文启用");
        assertTrue(renderer.contains("VirtualItemProviderHelper.getTarget(pStack)"),
                "GUI 主图必须读取现有绑定目标");
        assertTrue(renderer.contains("VirtualItemProviderHelper.isProviderItem(target)"),
                "损坏数据不得递归渲染另一个提供器");
        assertTrue(renderer.contains("renderStatic(target, ItemDisplayContext.GUI"),
                "绑定目标必须作为完整 GUI 主图渲染");
        assertTrue(renderer.contains("BADGE_SCALE"),
                "提供器角标必须使用固定缩放避免改变槽位尺寸");
        assertTrue(renderer.contains("renderProviderModel(pStack, pDisplayContext"),
                "非 GUI 场景必须继续渲染提供器本体");
        assertTrue(renderer.contains("renderProviderOnly(pStack, pDisplayContext"),
                "所有普通提供器回退路径必须先恢复自定义模型坐标原点");
        assertTrue(renderer.contains("private void renderProviderOnly("),
                "未绑定 GUI 与非 GUI 场景必须共用同一个坐标恢复入口");

        assertTrue(registration.contains("event.register(VirtualItemProviderRenderer.BASE_MODEL)"),
                "普通提供器基础模型必须作为额外模型载入");
        assertTrue(itemModel.contains("\"parent\": \"builtin/entity\""),
                "提供器入口模型必须转交自定义渲染器");
        assertTrue(baseModel.contains("\"parent\": \"minecraft:item/generated\""),
                "基础模型必须保持原有 generated 物品模型");
        assertTrue(baseModel.contains("\"layer0\": \"gt_shanhai:item/virtual_item_provider\""),
                "基础模型必须继续使用原提供器材质");
    }
}
