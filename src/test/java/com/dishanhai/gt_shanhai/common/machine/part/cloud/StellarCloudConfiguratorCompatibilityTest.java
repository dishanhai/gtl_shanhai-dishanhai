package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** 山海：檢查雲端配置頁與 LDLib 1.0.33.b 的服務端標籤同步相容性。 */
class StellarCloudConfiguratorCompatibilityTest {

    @Test
    void synchronizedCloudLabelsAvoidTheComponentConstructorWithNullTextSupplier() throws Exception {
        String resource = "com/dishanhai/gt_shanhai/common/machine/part/cloud/" +
                "StellarCloudConfiguratorUi.class";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            ClassNode configurator = new ClassNode();
            new ClassReader(input).accept(configurator, 0);
            int labels = 0;
            for (var method : configurator.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.name.equals("<init>") &&
                            call.owner.equals("com/lowdragmc/lowdraglib/gui/widget/LabelWidget")) {
                        labels++;
                        if (call.desc.equals("(IILnet/minecraft/network/chat/Component;)V")) {
                            fail("LDLib 1.0.33.b 的 Component 建構子不會設定 textSupplier，" +
                                    "雲端配置頁的服務端同步會觸發 NullPointerException");
                        }
                    }
                }
            }
            assertTrue(labels > 0, "必須檢查實際編譯的雲端配置頁標籤");
        }
    }
}
