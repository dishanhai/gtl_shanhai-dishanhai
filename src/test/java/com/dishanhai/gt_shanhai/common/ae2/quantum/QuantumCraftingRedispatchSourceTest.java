package com.dishanhai.gt_shanhai.common.ae2.quantum;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class QuantumCraftingRedispatchSourceTest {

    private static final Path LOGIC = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "ae2", "quantum", "QuantumCraftingCPULogic.java");
    private static final Path MENU = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "QuantumCraftingCPUMenuMixin.java");
    private static final Path SCREEN = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "CraftingCPUScreenRedispatchMixin.java");
    @Test
    void redispatchRefillsOnlyRealRemainingInputs() throws IOException {
        String logic = Files.readString(LOGIC);

        assertTrue(logic.contains("retryRemainingDispatch()"));
        assertTrue(logic.contains("VirtualPatternEncodingHelper.isPresenceInput(input)"),
                "重发配不得把虚拟在场输入抽进 CPU");
        assertTrue(logic.contains("storage.extract(input.getKey(), missing, Actionable.MODULATE, cpu.getSrc())"),
                "补料必须真实修改 AE 库存");
        assertTrue(logic.contains("inventory.insert(input.getKey(), extracted, Actionable.MODULATE)"),
                "实际抽到的材料必须进入当前量子 CPU 库存");
    }

    @Test
    void byproductOutputsFinishTogether() throws IOException {
        String logic = Files.readString(LOGIC);
        String insert = logic.substring(logic.indexOf("public long insert(AEKey what"),
                logic.indexOf("public void cancel()"));
        String finishWhen = "job.remainingAmount <= 0 && job.waitingFor.list.isEmpty()";
        int first = insert.indexOf(finishWhen);
        int stored = insert.indexOf("inventory.insert(what, amount, Actionable.MODULATE);");
        int second = stored < 0 ? -1 : insert.indexOf(finishWhen, stored);

        assertTrue(first >= 0 && second > first,
                "主产物先回到 CPU 时不能收工；最后一份副产物入库后才一起结清");
    }

    @Test
    void menuActionTargetsTheSelectedQuantumCpu() throws IOException {
        String menu = Files.readString(MENU);

        assertTrue(menu.contains("registerClientAction(\"gtShanhaiRedispatch\""));
        assertTrue(menu.contains("gtShanhai$resolveExplicitSelectedQuantumCpu()"),
                "重发配只能操作用户当前明确选中的量子 CPU，不能 fallback 到其他忙碌 CPU");
        assertTrue(menu.contains("quantumCpu.craftingLogic.retryRemainingDispatch()"));
    }

    @Test
    void buttonOccupiesTheSlotImmediatelyLeftOfCancel() throws IOException {
        assertTrue(Files.exists(SCREEN));
        String screen = Files.readString(SCREEN);
        String mixins = MixinConfigs.readAll();

        assertTrue(screen.contains("Component.literal(\"重发配\")"));
        assertTrue(screen.contains("this.leftPos + 108"));
        assertTrue(screen.contains("this.topPos + this.imageHeight - 25"));
        assertTrue(screen.contains("control.gtShanhai$redispatch()"));
        assertTrue(mixins.contains("\"CraftingCPUScreenRedispatchMixin\""));
    }
}
