package com.dishanhai.gt_shanhai.client.renderer.machine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialOmegaEngineRendererPerformanceSourceTest {

    private static final Path DISPATCHER = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "client", "renderer", "machine", "PrimordialOmegaEngineRenderer.java");
    private static final Path UNIVERSE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "client", "renderer", "machine", "PrimordialUniverseSphereRenderer.java");
    private static final Path NEUTRON_STAR = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "client", "renderer", "machine", "PrimordialNeutronStarSphereRenderer.java");

    @Test
    void expensiveSpecialEffectsAreSkippedBehindOpenScreens() throws IOException {
        String source = Files.readString(UNIVERSE);
        int renderStart = source.indexOf("static void render(");
        int firstModelRender = source.indexOf("renderStar(", renderStart);
        String setup = source.substring(renderStart, firstModelRender);

        assertTrue(setup.contains("Minecraft.getInstance().screen != null"),
                "打开容器或其他 UI 时不应继续渲染原初恒星、轨道物与宇宙壳");
        assertTrue(setup.indexOf("return;") > setup.indexOf("Minecraft.getInstance().screen != null"),
                "UI 检查必须在任何特殊模型渲染之前直接返回");
    }

    @Test
    void sphereStyleDispatchCoversBothRenderers() throws IOException {
        String source = Files.readString(DISPATCHER);
        String dispatch = source.substring(source.indexOf("protected void renderSpecialEffects"));

        assertTrue(dispatch.contains("PrimordialNeutronStarSphereRenderer.enqueue("),
                "中子星风格必须派发到 PrimordialNeutronStarSphereRenderer");
        assertTrue(dispatch.contains("PrimordialUniverseSphereRenderer.render("),
                "宇宙风格必须派发到 PrimordialUniverseSphereRenderer");
    }

    /**
     * 中子星球体常驻可见，而 AbstractRingRenderer 传下来的 smoothTick 是「停机即 0」的哨兵值
     * （isWorking 就是由它推导的）。一旦有人把它直接喂给伪神锻的 AntichristStarRenderer，
     * 累积角度会在工作状态翻转时弹回基准角——整颗球猛翻一下。这条守卫钉住「中子星分支只吃连续时钟」。
     */
    @Test
    void neutronStarBranchUsesContinuousClockNotTheStoppedAtZeroSentinel() throws IOException {
        String dispatcher = Files.readString(DISPATCHER);
        int enqueueCall = dispatcher.indexOf("PrimordialNeutronStarSphereRenderer.enqueue(");
        String call = dispatcher.substring(enqueueCall, dispatcher.indexOf(';', enqueueCall));

        assertTrue(call.contains("RenderUtil.getSmoothTick("),
                "中子星分支必须用 RenderUtil.getSmoothTick 取连续时钟");
        assertFalse(call.contains("smoothTick,"),
                "中子星分支不得直接传入停机归零的 smoothTick 哨兵值");

        assertTrue(Files.readString(NEUTRON_STAR).contains("float continuousTick"),
                "enqueue 的时钟形参应命名为 continuousTick，把「必须连续」写进签名");
    }
}
