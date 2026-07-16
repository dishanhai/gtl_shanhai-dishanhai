package com.dishanhai.gt_shanhai.common.compat.eaep;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EaepProviderRecipeTypeBridgeSourceTest {

    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path BRIDGE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "compat", "eaep", "EaepProviderRecipeTypeBridge.java");
    private static final Path REQUEST_MIXIN = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "EaepRequestProvidersListRecipeTypesMixin.java");
    private static final Path PACKET_MIXIN = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "EaepProvidersListRecipeTypesMixin.java");
    private static final Path SCREEN_MIXIN = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "EaepProviderSelectScreenRecipeTypeMixin.java");

    @Test
    void eaepUploadMixinsAreRegisteredOnCorrectSides() throws IOException {
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(config.contains("\"EaepProvidersListRecipeTypesMixin\""),
                "服务端/通用 packet mixin 必须注册");
        assertTrue(config.contains("\"EaepRequestProvidersListRecipeTypesMixin\""),
                "服务端 provider 列表请求 mixin 必须注册");
        assertTrue(config.contains("\"EaepProviderSelectScreenRecipeTypeMixin\""),
                "客户端选择界面 mixin 必须注册");
    }

    @Test
    void providerListPacketCarriesRecipeTypesBeforeOpeningScreen() throws IOException {
        String packetMixin = Files.readString(PACKET_MIXIN);

        assertTrue(packetMixin.contains("buf.writeVarInt(providerTypes.size())"),
                "S2C provider 列表必须追加每个 provider 的配方类型");
        assertTrue(packetMixin.contains("gtShanhai$setProviderRecipeTypeIds(providerTypes)"),
                "客户端 decode 后必须恢复配方类型列表");
        assertTrue(packetMixin.contains("setIncomingProviderRecipeTypes"),
                "打开 EAEP 选择界面前必须暂存配方类型");
        assertTrue(packetMixin.contains("clearIncomingProviderRecipeTypes"),
                "界面创建完成后必须清理暂存，避免污染下一次打开");
    }

    @Test
    void requestMixinKeepsOriginalNullContainerGuard() throws IOException {
        String requestMixin = Files.readString(REQUEST_MIXIN);

        assertOrder(requestMixin, "if (container == null)", "PatternProviderDataUtil.getAvailableSlots(container)",
                "空 provider 必须先过滤，不能比 EAEP 原逻辑更容易 NPE");
        assertTrue(requestMixin.contains("EaepProviderRecipeTypeBridge.collectProviderRecipeTypeIds(container)"),
                "发送 provider 列表时必须同步星律暴露的主机配方类型");
    }

    @Test
    void screenUsesUploadRecipeTypeForPriorityAndFiveTypeHintLimit() throws IOException {
        String screenMixin = Files.readString(SCREEN_MIXIN);
        String bridge = Files.readString(BRIDGE);

        assertTrue(screenMixin.contains("gtShanhai$uploadRecipeTypeQuery"),
                "排序和提示必须固定使用本次上传样板类型，不能被后续搜索框文本覆盖");
        assertTrue(screenMixin.contains("gtShanhai$includeRecipeTypeMatches"),
                "当前搜索必须把配方类型命中的主机补入 EAEP 结果");
        assertTrue(screenMixin.contains("this.gIds")
                        && screenMixin.contains("this.gNames")
                        && screenMixin.contains("this.gTotalSlots")
                        && screenMixin.contains("this.gCount"),
                "补入主机时必须同步复制 EAEP 的四个分组列表字段");
        assertTrue(screenMixin.contains("this.query != null && !this.query.isBlank()"),
                "当前非空搜索词必须优先于上传样板类型");
        assertTrue(bridge.contains("public static boolean providerMatchesRecipeType"),
                "screen mixin 必须复用统一的配方类型匹配规则");
        assertTrue(!screenMixin.contains("@Inject(method = \"m_88315_\""),
                "ProviderSelectScreen 未覆写 render，不能向不存在的方法注入绘制逻辑");
        assertTrue(screenMixin.contains("@Inject(method = \"m_7856_\"")
                        && screenMixin.contains("ScreenAccessor")
                        && screenMixin.contains("eap$getRenderables().add(this::gtShanhai$renderRecipeTypeHints)"),
                "配方类型提示必须在界面初始化时注册为独立 Renderable");
        assertTrue(screenMixin.contains("gtShanhai$renderRecipeTypeHints")
                        && screenMixin.contains("button.getX() + button.getWidth() + 8"),
                "配方类型提示必须绘制在按钮右侧空区，不能侵占原按钮文本");
        assertTrue(screenMixin.contains("buildProviderRecipeTypeText(")
                        && screenMixin.contains("gtShanhai$effectiveRecipeTypeQuery()")
                        && screenMixin.contains("5);"),
                "选择按钮提示最多显示 5 个配方类型");
        assertTrue(screenMixin.contains("gtShanhai$trimText(font, text, maxWidth)")
                        && screenMixin.contains("guiGraphics.enableScissor"),
                "右侧提示必须按可用宽度截断并限制绘制区域，避免文本溢出屏幕");
        assertTrue(!screenMixin.contains("gtShanhai$appendRecipeTypeHint"),
                "不得再通过 buildLabel 拼接配方类型提示");
        assertTrue(bridge.contains("preferred.add(displayName)") && bridge.contains("others.add(displayName)")
                        && bridge.contains("ordered.addAll(preferred)") && bridge.contains("ordered.addAll(others)"),
                "多类型主机必须先显示本次上传样板匹配类型，再显示其他类型");
        assertTrue(bridge.contains("ordered.subList(0, maxTypes)"),
                "超过最大类型数量必须截断，避免按钮文本溢出");
        assertTrue(bridge.contains("new LinkedHashSet<>()"),
                "同名 provider 合并配方类型时必须保留星律收集顺序");
    }

    private static void assertOrder(String source, String first, String second, String message) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex, message);
    }
}
