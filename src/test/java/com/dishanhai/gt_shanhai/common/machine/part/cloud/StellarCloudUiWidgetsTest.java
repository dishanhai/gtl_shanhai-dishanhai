package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：直接執行編譯後的動作閘門，避免啟動 Minecraft 客戶端。 */
class StellarCloudUiWidgetsTest {
    private static final String OWNER = "com/dishanhai/gt_shanhai/common/machine/part/cloud/StellarCloudUiWidgets";

    private static ClassNode read(String suffix) throws Exception {
        try (var input = StellarCloudUiWidgetsTest.class.getClassLoader().getResourceAsStream(OWNER + suffix + ".class")) {
            assertNotNull(input, "Shared cloud presentation widgets must exist: " + suffix);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    /** 複製實際閘門位元碼；保留其邏輯，僅去除 UI 類別的載入需求。 */
    private static java.lang.reflect.Method probe(String name, Class<?>... arguments) throws Exception {
        var source = read("");
        var method = source.methods.stream().filter(it -> it.name.equals(name)).findFirst().orElseThrow();
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "CloudActionGateProbe", null, "java/lang/Object", null);
        method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        method.accept(writer);
        writer.visitEnd();
        class ProbeLoader extends ClassLoader {
            Class<?> define(byte[] bytes) { return defineClass("CloudActionGateProbe", bytes, 0, bytes.length); }
        }
        return new ProbeLoader().define(writer.toByteArray()).getMethod(name, arguments);
    }

    private static java.lang.reflect.Method gate() throws Exception {
        return probe("runServerAction", boolean.class, Supplier.class, Runnable.class);
    }

    @Test void clientClickCannotEvaluateOrExecuteBusinessActions() throws Exception {
        var actions = new AtomicInteger();
        Supplier<String> forbiddenClientRead = () -> { fail("Server state cannot be read on a client click"); return ""; };
        assertEquals("", gate().invoke(null, true, forbiddenClientRead, (Runnable) actions::incrementAndGet));
        assertEquals(0, actions.get());
    }

    @Test void everyServerClickRechecksTheCurrentDisabledReason() throws Exception {
        var reason = new AtomicReference<>("gt_shanhai.cloud.status.denied");
        var actions = new AtomicInteger();
        var gate = gate();
        Runnable action = actions::incrementAndGet;
        assertEquals(reason.get(), gate.invoke(null, false, (Supplier<String>) reason::get, action));
        assertEquals(0, actions.get());
        reason.set("");
        assertEquals("", gate.invoke(null, false, (Supplier<String>) reason::get, action));
        assertEquals(1, actions.get());
        reason.set("gt_shanhai.cloud.status.output_full");
        assertEquals(reason.get(), gate.invoke(null, false, (Supplier<String>) reason::get, action));
        assertEquals(1, actions.get(), "An earlier enabled state cannot authorize the next click");
    }

    @Test void missingServerReasonRejectsWithAnExplicitWaitingResult() throws Exception {
        var actions = new AtomicInteger();
        assertEquals("gt_shanhai.cloud.ui.waiting", gate().invoke(null, false, (Supplier<String>) () -> null,
                (Runnable) actions::incrementAndGet));
        assertEquals(0, actions.get());
    }

    @Test void disabledReasonIsSynchronizedAtOpenAndWhenItChanges() throws Exception {
        var button = read("$ActionButton");
        for (String name : new String[]{"writeInitialData", "readInitialData", "detectAndSendChanges", "readUpdateInfo"}) {
            assertTrue(button.methods.stream().anyMatch(method -> method.name.equals(name)), name);
        }
        var click = button.methods.stream().filter(method -> method.name.equals("mouseClicked")).findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(click.instructions.spliterator(), false)
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .anyMatch(call -> call.name.equals("isEmpty")), "Disabled clicks must be intercepted before the base button sends an action");
        for (var method : button.methods) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) assertNotEquals("setActive", call.name,
                    "Disabled controls must keep receiving clicks so slots underneath cannot receive them");
        }
    }

    @Test void viewportHitAreaExcludesClippedChildrenAndItsRightAndBottomEdges() throws Exception {
        var bounds = probe("inside", int.class, int.class, int.class, int.class, double.class, double.class);
        assertEquals(true, bounds.invoke(null, 20, 30, 100, 80, 20d, 30d));
        assertEquals(true, bounds.invoke(null, 20, 30, 100, 80, 119.99d, 109.99d));
        assertEquals(false, bounds.invoke(null, 20, 30, 100, 80, 120d, 30d));
        assertEquals(false, bounds.invoke(null, 20, 30, 100, 80, 20d, 110d));
        assertEquals(false, bounds.invoke(null, 20, 30, 100, 80, 19.99d, 30d));
        assertEquals(false, bounds.invoke(null, 20, 30, 100, 80, 20d, 29.99d));
        var viewport = read("$Viewport");
        for (String name : new String[]{"isMouseOverElement", "mouseClicked", "mouseWheelMove"}) {
            assertTrue(viewport.methods.stream().anyMatch(method -> method.name.equals(name)), name);
        }
    }
}
