package com.dishanhai.gt_shanhai.common.machine.part.ui;

import net.minecraftforge.api.distmarker.Dist;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.assertSame;

/** 山海：模擬專用伺服器的端別與客戶端類別載入限制。 */
class StellarGlassDedicatedServerTest {
    private static final String TEXTURE = "com.dishanhai.gt_shanhai.common.machine.part.ui.StellarGlassTexture";
    private static final String ENVIRONMENT = "net.minecraftforge.fml.loading.FMLEnvironment";
    private static final String GUI_TEXTURE = "com.lowdragmc.lowdraglib.gui.texture.IGuiTexture";
    private static final String GLASS_PANEL = "com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture";

    @Test
    void commonUiCanBuildTexturesWithoutResolvingGuiGraphics() throws Throwable {
        ClassLoader server = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.client.")) throw new ClassNotFoundException(name);
                if (name.equals(ENVIRONMENT) || isClassOrNested(name, TEXTURE)
                        || isClassOrNested(name, GUI_TEXTURE) || isClassOrNested(name, GLASS_PANEL)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(ENVIRONMENT)) {
                    byte[] bytes = dedicatedEnvironment();
                    return defineClass(name, bytes, 0, bytes.length);
                }
                try (var stream = getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (stream == null) throw new ClassNotFoundException(name);
                    byte[] bytes = stream.readAllBytes();
                    if (name.equals(GUI_TEXTURE)) bytes = skipSrgMemoizer(bytes);
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException exception) {
                    throw new ClassNotFoundException(name, exception);
                }
            }

        };
        Class<?> texture = Class.forName(TEXTURE, true, server);
        Class<?> guiTexture = server.loadClass(GUI_TEXTURE);
        Object empty = guiTexture.getField("EMPTY").get(null);
        Object icon = guiTexture.getField("MISSING_TEXTURE").get(null);
        var lookup = MethodHandles.publicLookup();
        for (String method : new String[]{"root", "panel", "floatingPanel", "display", "slot", "page", "button"}) {
            var factory = lookup.findStatic(texture, method, MethodType.methodType(guiTexture));
            assertSame(empty, factory.invoke(), method);
        }
        for (String method : new String[]{"adapt", "accentIcon"}) {
            var factory = lookup.findStatic(texture, method, MethodType.methodType(guiTexture, guiTexture));
            assertSame(icon, factory.invoke(icon), method);
        }
    }

    private static boolean isClassOrNested(String name, String outer) {
        return name.equals(outer) || name.startsWith(outer + "$");
    }

    private static byte[] skipSrgMemoizer(byte[] bytes) {
        // LDLib 在測試 classpath 仍是 SRG，這個快取不參與材質建立。
        var texture = new ClassNode();
        new ClassReader(bytes).accept(texture, 0);
        var initializer = texture.methods.stream().filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElseThrow();
        for (var instruction : initializer.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/Util") && call.name.equals("m_143827_")) {
                initializer.instructions.remove(call);
            }
        }
        var writer = new ClassWriter(0);
        texture.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] dedicatedEnvironment() {
        String environment = ENVIRONMENT.replace('.', '/');
        String dist = Dist.class.getName().replace('.', '/');
        String descriptor = Dist.class.descriptorString();
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, environment, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "dist", descriptor, null, null).visitEnd();
        var initializer = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.visitCode();
        initializer.visitFieldInsn(Opcodes.GETSTATIC, dist, "DEDICATED_SERVER", descriptor);
        initializer.visitFieldInsn(Opcodes.PUTSTATIC, environment, "dist", descriptor);
        initializer.visitInsn(Opcodes.RETURN);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
