package com.dishanhai.gt_shanhai.common.machine.part.ui;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：LDLib 的空開關面可合法省略，但不能包成含 null 的材質群組。 */
class StellarSwitchNullTextureTest {
    @Test
    void missingSwitchFaceBecomesAnEmptyTextureBeforeItReachesLdlib() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/machine/part/ui/StellarGlassTexture.java"));
        int start = source.indexOf("private static IGuiTexture switchFace(");
        int body = source.indexOf('{', start);
        String face = source.substring(body + 1, source.indexOf("public static AETextInputButtonWidget", body));
        assertTrue(face.stripLeading().startsWith("if (nativeTexture == null) return IGuiTexture.EMPTY;"),
                "A missing base or pressed face must remain invisible without placing null in GuiTextureGroup");
    }

    @Test
    void actualLdlibSettersWrapEachFaceWithoutFilteringNull() throws Exception {
        ClassNode widget = new ClassNode();
        new ClassReader("com.lowdragmc.lowdraglib.gui.widget.SwitchWidget")
                .accept(widget, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (String setter : new String[]{"setBaseTexture", "setPressedTexture"}) {
            var method = widget.methods.stream().filter(m -> m.name.equals(setter)).findFirst().orElseThrow();
            boolean constructsGroup = false;
            boolean storesFace = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                        && type.desc.equals("com/lowdragmc/lowdraglib/gui/texture/GuiTextureGroup")) {
                    constructsGroup = true;
                }
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.name.equals(setter.equals("setBaseTexture") ? "baseTexture" : "pressedTexture")) {
                    storesFace = true;
                }
            }
            assertTrue(constructsGroup && storesFace, setter);
        }
    }
}
