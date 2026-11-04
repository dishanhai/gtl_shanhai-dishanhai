package com.dishanhai.gt_shanhai.common.machine.thread;

// Author: dishanhai
import com.dishanhai.gt_shanhai.mixin.StructureFormGetPartsMixin;
import com.dishanhai.gt_shanhai.testutil.MixinConfigs;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.util.PatternMatchContext;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Executes GTCEu's real formation/getParts methods; isolates world access and the Mixin weaving boundary. */
class RecipeThreadFormationVoltageTest {
    private static final Unsafe UNSAFE = unsafe();

    @Test
    void unguardedReentrantPartsReadAbortsFormationBeforeVoltageTierInitialization() throws Exception {
        Controller controller = controller(false);
        assertThrows(ConcurrentModificationException.class, controller::onStructureFormed);
        assertTrue(controller.isFormed(), "GTCEu marks formed before invoking part callbacks");
        assertFalse(controller.formationReturned, "The electric subclass cannot initialize energy or tier");
    }

    @Test
    void guardedReentrantPartsReadCompletesFormationBeforeElectricInitialization() throws Exception {
        Controller controller = controller(true);
        assertDoesNotThrow(controller::onStructureFormed);
        assertTrue(controller.formationReturned);
        assertEquals(2, controller.getParts().size());
        assertEquals(2, controller.getPartPositions().length);
    }

    @Test
    void guardKeepsEmptyClientCacheOnTheNativeRebuildPath() throws Exception {
        Guard guard = (Guard) UNSAFE.allocateInstance(Guard.class);
        var callback = invokeGuard(guard, true, new ArrayList<>(), new BlockPos[]{BlockPos.ZERO});
        assertFalse(callback.isCancelled());
        assertTrue(MixinConfigs.readAll().contains("\"StructureFormGetPartsMixin\""));
    }

    @Test
    void actualElectricFormationCalculatesEnergyAndTierOnlyAfterSuperclassReturns() throws Exception {
        String resource = WorkableElectricMultiblockMachine.class.getName().replace('.', '/') + ".class";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            var method = node.methods.stream().filter(m -> m.name.equals("onStructureFormed")).findFirst().orElseThrow();
            int formation = -1, energy = -1, tier = -1;
            for (int i = 0; i < method.instructions.size(); i++) {
                var instruction = method.instructions.get(i);
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals(WorkableMultiblockMachine.class.getName().replace('.', '/'))
                            && call.name.equals("onStructureFormed")) formation = i;
                    if (call.name.equals("getEnergyContainer")) energy = i;
                }
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.name.equals("tier")) tier = i;
            }
            assertTrue(formation >= 0 && energy > formation && tier > energy);
        }
    }

    private static Controller controller(boolean guarded) throws Exception {
        Controller controller = (Controller) UNSAFE.allocateInstance(Controller.class);
        controller.guarded = guarded;
        controller.guard = (Guard) UNSAFE.allocateInstance(Guard.class);
        controller.liveParts = new ArrayList<>();
        put(MultiblockControllerMachine.class, "parts", controller, controller.liveParts);
        put(MultiblockControllerMachine.class, "partPositions", controller, new BlockPos[0]);
        controller.definition = (MultiblockMachineDefinition) UNSAFE.allocateInstance(MultiblockMachineDefinition.class);
        controller.definition.setPartSorter((a, b) -> 0);
        controller.state = (MultiblockState) UNSAFE.allocateInstance(MultiblockState.class);
        PatternMatchContext context = new PatternMatchContext();
        put(MultiblockState.class, "matchContext", controller.state, context);
        var parts = new LinkedHashSet<IMultiPart>();
        parts.add(part(controller, true));
        parts.add(part(controller, false));
        context.set("parts", parts);
        return controller;
    }

    private static IMultiPart part(Controller controller, boolean readsParts) {
        return (IMultiPart) Proxy.newProxyInstance(RecipeThreadFormationVoltageTest.class.getClassLoader(),
                new Class<?>[]{IMultiPart.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "addedToController" -> { if (readsParts) controller.getParts(); yield null; }
                    case "self" -> controller;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static CallbackInfoReturnable<List<IMultiPart>> invokeGuard(Guard guard, boolean formed,
            List<IMultiPart> parts, BlockPos[] positions) throws Exception {
        put(StructureFormGetPartsMixin.class, "isFormed", guard, formed);
        put(StructureFormGetPartsMixin.class, "parts", guard, parts);
        put(StructureFormGetPartsMixin.class, "partPositions", guard, positions);
        Method method = StructureFormGetPartsMixin.class.getDeclaredMethod("shanhai$keepLivePartsWhileForming",
                CallbackInfoReturnable.class);
        method.setAccessible(true);
        var callback = new CallbackInfoReturnable<List<IMultiPart>>("getParts", true);
        method.invoke(guard, callback);
        return callback;
    }

    private static class Guard extends StructureFormGetPartsMixin {}

    private static class Controller extends MultiblockControllerMachine {
        boolean guarded, formationReturned;
        Guard guard;
        List<IMultiPart> liveParts;
        MultiblockMachineDefinition definition;
        MultiblockState state;
        Controller() { super(null); }
        @Override public MultiblockMachineDefinition getDefinition() { return definition; }
        @Override public MultiblockState getMultiblockState() { return state; }
        @Override public boolean shouldAddPartToController(IMultiPart part) { return true; }
        @Override public BlockPos getPos() { return BlockPos.ZERO; }
        @Override public void onStructureFormed() {
            super.onStructureFormed();
            formationReturned = true;
        }
        @Override public List<IMultiPart> getParts() {
            if (guarded) {
                try {
                    var callback = invokeGuard(guard, isFormed(), liveParts, getPartPositions());
                    if (callback.isCancelled()) return callback.getReturnValue();
                } catch (Exception error) { throw new AssertionError(error); }
            }
            return super.getParts();
        }
    }

    private static void put(Class<?> owner, String name, Object target, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (Exception error) { throw new AssertionError(error); }
    }

}
