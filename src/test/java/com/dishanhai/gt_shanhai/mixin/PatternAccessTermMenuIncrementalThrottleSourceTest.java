package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 样板访问终端增量同步节流守卫。
 * <p>
 * AE2 sendIncrementalUpdate 每 tick 对全部样板槽做深度 NBT 比对（spark 实测约
 * 0.64% 服务端线程）。节流为每 4 tick 一次，玩家 doAction 后短窗恢复全速。
 * 本测试守住：节流注入可取消、交互爆发窗口存在、mixin 已注册。
 */
class PatternAccessTermMenuIncrementalThrottleSourceTest {

    private static final Path MIXIN = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "PatternAccessTermMenuIncrementalThrottleMixin.java");
    private static final Path MIXIN_JSON = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void incrementalUpdateThrottledWithInteractionBurst() throws IOException {
        String source = Files.readString(MIXIN);
        assertTrue(source.contains("@Mixin(value = PatternAccessTermMenu.class, remap = false)"),
                "必须挂在 AE2 PatternAccessTermMenu 上（子类含山海无线样板管理终端一并生效）");
        assertTrue(source.contains("method = \"sendIncrementalUpdate\"")
                        && source.contains("cancellable = true"),
                "增量 diff 必须可取消节流");
        assertTrue(source.contains("method = \"doAction\""),
                "必须有玩家交互钩子：doAction 后短窗恢复每 tick 全速，保证手动放取样板跟手");
        assertTrue(source.contains("tick <= gtShanhai$burstUntilTick) return;"),
                "爆发窗口内必须直接放行，不参与取模节流");
    }

    @Test
    void mixinRegistered() throws IOException {
        String json = Files.readString(MIXIN_JSON);
        assertTrue(json.contains("\"PatternAccessTermMenuIncrementalThrottleMixin\""),
                "mixin 必须注册进 gt_shanhai.mixin.json 的 mixins 数组");
    }
}
