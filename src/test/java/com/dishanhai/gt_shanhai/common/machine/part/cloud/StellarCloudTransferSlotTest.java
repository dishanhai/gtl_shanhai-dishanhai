package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：執行實際槽閘門位元碼，覆核原生槽與 shift-click 共用的取放檢查。 */
public class StellarCloudTransferSlotTest {
    private static final String UI = "com/dishanhai/gt_shanhai/common/machine/part/cloud/StellarCloudLibraryUi";
    private static final String SLOT = UI + "$TransferSlot";
    private static final String LD_SLOT = "com/lowdragmc/lowdraglib/gui/widget/SlotWidget";
    private static final String PROBE = "CloudTransferSlotProbe";

    private static ClassNode read(String name) throws Exception {
        try (var input = StellarCloudTransferSlotTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, "Both physical transfer slots need a server authorization gate: " + name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    /** 僅替代 Minecraft／LDLib 載入需求；閘門方法直接來自正式類別。 */
    public static class SlotBase {
        public boolean remote, put = true, take = true;
        public boolean isRemote() { return remote; }
        public boolean canPutStack(Object stack) { return put; }
        public boolean canTakeStack(Object player) { return take; }
    }

    private static String descriptor(String value) {
        return value.replace("Lnet/minecraft/world/item/ItemStack;", "Ljava/lang/Object;")
                .replace("Lnet/minecraft/world/entity/player/Player;", "Ljava/lang/Object;");
    }

    private static SlotBase probe(BooleanSupplier authorized) throws Exception {
        var source = read(SLOT);
        String base = Type.getInternalName(SlotBase.class);
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PROBE, null, base, null);
        writer.visitField(Opcodes.ACC_PRIVATE, "authorized", "Ljava/util/function/BooleanSupplier;", null, null).visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/util/function/BooleanSupplier;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, base, "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, PROBE, "authorized", "Ljava/util/function/BooleanSupplier;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        for (String name : new String[]{"canPutStack", "canTakeStack"}) {
            MethodNode method = source.methods.stream().filter(it -> it.name.equals(name)).findFirst().orElseThrow();
            method.desc = descriptor(method.desc);
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals(SLOT)) call.owner = PROBE;
                    else if (call.owner.equals(LD_SLOT)) call.owner = base;
                    call.desc = descriptor(call.desc);
                } else if (instruction instanceof FieldInsnNode field && field.owner.equals(SLOT)) field.owner = PROBE;
            }
            method.accept(writer);
        }
        writer.visitEnd();
        class Loader extends ClassLoader {
            Class<?> define(byte[] bytes) { return defineClass(PROBE.replace('/', '.'), bytes, 0, bytes.length); }
        }
        return (SlotBase) new Loader().define(writer.toByteArray()).getConstructor(BooleanSupplier.class).newInstance(authorized);
    }

    @Test void alreadyOpenServerSlotsRecheckAuthorizationForEveryNativePutAndTake() throws Exception {
        var allowed = new AtomicBoolean(true);
        var reads = new AtomicInteger();
        var slot = probe(() -> { reads.incrementAndGet(); return allowed.get(); });
        assertTrue(slot.canPutStack(null));
        assertTrue(slot.canTakeStack(null));
        allowed.set(false); // 撤權、超出距離或容器身份變更，既有槽不能沿用開啟時授權。
        assertFalse(slot.canPutStack(null));
        assertFalse(slot.canTakeStack(null));
        assertEquals(4, reads.get());
        allowed.set(true);
        assertTrue(slot.canTakeStack(null));
    }

    @Test void pendingInboxOperationRejectsReplacementAndExtraction() throws Exception {
        var pending = new AtomicBoolean(false);
        var inbox = probe(() -> !pending.get());
        assertTrue(inbox.canPutStack(null));
        assertTrue(inbox.canTakeStack(null));
        pending.set(true);
        assertFalse(inbox.canPutStack(null), "Native placement and drag must preserve the pending original");
        assertFalse(inbox.canTakeStack(null), "Native extraction and shift-click must preserve the pending original");
    }

    @Test void clientPredictionNeverReadsServerOnlyAuthorizationAndKeepsBaseSlotRestrictions() throws Exception {
        var slot = probe(() -> { fail("Client prediction cannot read server authorization or pending upload state"); return false; });
        slot.remote = true;
        assertTrue(slot.canPutStack(null));
        assertTrue(slot.canTakeStack(null));
        slot.put = false;
        assertFalse(slot.canPutStack(null), "Output remains read-only for insertion");
        slot.take = false;
        assertFalse(slot.canTakeStack(null));
    }

    @Test void bothPhysicalSlotsUseTheGuardAndSessionsCheckCurrentMachineAuthorization() throws Exception {
        var create = read(UI).methods.stream().filter(it -> it.name.equals("create")).findFirst().orElseThrow();
        assertEquals(2, StreamSupport.stream(create.instructions.spliterator(), false)
                .filter(TypeInsnNode.class::isInstance).map(TypeInsnNode.class::cast)
                .filter(it -> it.getOpcode() == Opcodes.NEW && it.desc.equals(SLOT)).count());
        String machine = Files.readString(Path.of("src/main/java/" + UI.replace("StellarCloudLibraryUi", "StellarCloudLibraryMachine") + ".java"));
        assertTrue(machine.contains("boolean inboxAccessible() { return uploadOperation == null && actionAuthorized(player); }"));
        assertTrue(machine.contains("boolean outputAccessible() { return actionAuthorized(player); }"));
        String ui = Files.readString(Path.of("src/main/java/" + UI + ".java"));
        assertTrue(ui.contains("session::inboxAccessible") && ui.contains("session::outputAccessible"));
    }

    @Test void ldlibNativeHandlersAndShiftMergeRouteThroughTheGuardedMethods() throws Exception {
        var nativeSlot = read(LD_SLOT + "$WidgetSlotItemTransfer");
        for (String gate : new String[]{"canPutStack", "canTakeStack"}) {
            assertTrue(nativeSlot.methods.stream().anyMatch(method -> StreamSupport.stream(method.instructions.spliterator(), false)
                    .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                    .anyMatch(call -> call.owner.equals(LD_SLOT) && call.name.equals(gate))), gate);
        }
        var merge = read("com/lowdragmc/lowdraglib/gui/modular/ModularUIContainer").methods.stream()
                .filter(method -> method.name.equals("mergeItemStack")).findFirst().orElseThrow();
        assertTrue(StreamSupport.stream(merge.instructions.spliterator(), false)
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .anyMatch(call -> call.owner.equals("net/minecraft/world/inventory/Slot")
                        && call.desc.equals("(Lnet/minecraft/world/item/ItemStack;)Z")),
                "LDLib shift-merge must evaluate the native slot placement gate before changing a stack");
    }
}
