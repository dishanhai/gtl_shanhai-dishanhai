package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.common.machine.part.ProgrammableHatchPartMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferProxyPartMachine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public final class RecipeTypePatternSearchHelper {

    private RecipeTypePatternSearchHelper() {
    }

    public static Iterator<GTRecipe> searchRecipe(IRecipeLogicMachine machine) {
        if (machine == null) return Collections.emptyIterator();

        GTRecipeType forcedType = ProgrammableHatchPartMachine.getSelectedRecipeTypeFor(machine);
        GTRecipeType baseType = forcedType != null ? forcedType : machine.getRecipeType();
        Iterator<GTRecipe> baseIterator = searchType(baseType, machine);
        ProgrammableHatchPartMachine.logRecipeTypeRedirect(machine, forcedType, machine.getRecipeType());
        if (forcedType != null) {
            return baseIterator;
        }

        Set<GTRecipe> virtualRecipes = collectMarkedPatternRecipes(machine);
        if (virtualRecipes.isEmpty()) {
            return baseIterator;
        }
        return new AppendingRecipeIterator(baseIterator, virtualRecipes.iterator());
    }

    public static Set<GTRecipe> collectMarkedPatternRecipes(IRecipeLogicMachine machine) {
        LinkedHashSet<GTRecipe> result = new LinkedHashSet<>();
        if (!(machine instanceof IRecipeCapabilityMachine capabilityMachine)) return result;

        List<MEPatternRecipeHandlePart> parts = capabilityMachine.getMEPatternRecipeHandleParts();
        if (parts != null && !parts.isEmpty()) {
            for (MEPatternRecipeHandlePart part : parts) {
                if (part == null) continue;
                Object[] handlers = part.getMERecipeHandlers();
                if (handlers == null) continue;
                for (Object handler : handlers) {
                    collectMarkedPatternRecipesFromHandler(capabilityMachine, handler, result);
                }
            }
        }
        collectMarkedPatternRecipesFromParts(machine, capabilityMachine, result);
        return applyRecipeTypeSwitch(machine, result);
    }

    /**
     * 原生 GTCEu/GTLCore 机器(不实现 IRecipeCapabilityMachine)的虚拟候选收集。
     * 只走 IMultiController#getParts() 从结构仓室扫星律样板总成,不依赖 handler 激活路径。
     * capabilityMachine 传 null:activatePatternRecipe 的 guard 会安全跳过供料注册,
     * 原生机器的供料由 mixin 侧手动 handleRecipeInput 完成,不需要 tryAddAndActiveMERhp。
     */
    public static Set<GTRecipe> collectNativeVirtualRecipes(IRecipeLogicMachine machine) {
        LinkedHashSet<GTRecipe> result = new LinkedHashSet<>();
        if (!(machine instanceof IMultiController controller)) return result;
        for (IMultiPart part : controller.getParts()) {
            if (part == null) continue;
            Object patternMachine = resolvePatternBuffer(part);
            if (patternMachine == null) continue;
            collectMarkedPatternRecipesFromMachine(null, part, patternMachine,
                    readActiveSlots(patternMachine), result);
        }
        return applyRecipeTypeSwitch(machine, result);
    }

    public static Set<GTRecipe> collectMarkedPatternRecipesFromBuffer(IRecipeLogicMachine machine, Object buffer) {
        LinkedHashSet<GTRecipe> result = new LinkedHashSet<>();
        IRecipeCapabilityMachine capabilityMachine = machine instanceof IRecipeCapabilityMachine
                ? (IRecipeCapabilityMachine) machine
                : null;
        collectMarkedPatternRecipesFromMachine(capabilityMachine, buffer, buffer, readActiveSlots(buffer), result);
        return applyRecipeTypeSwitch(machine, result);
    }

    public static Set<GTRecipeType> collectDeclaredRecipeTypes(IRecipeLogicMachine machine) {
        LinkedHashSet<GTRecipeType> result = new LinkedHashSet<>();
        if (!(machine instanceof IRecipeCapabilityMachine capabilityMachine)) return result;

        List<MEPatternRecipeHandlePart> parts = capabilityMachine.getMEPatternRecipeHandleParts();
        if (parts == null || parts.isEmpty()) return result;

        for (MEPatternRecipeHandlePart part : parts) {
            if (part == null) continue;
            Object[] handlers = part.getMERecipeHandlers();
            if (handlers == null) continue;
            for (Object handler : handlers) {
                collectDeclaredRecipeTypesFromHandler(handler, result);
            }
        }
        return result;
    }

    private static void collectMarkedPatternRecipesFromHandler(IRecipeCapabilityMachine capabilityMachine, Object handler,
            Set<GTRecipe> result) {
        Object ownerMachine = findHandlerMachine(handler);
        collectMarkedPatternRecipesFromMachine(capabilityMachine, ownerMachine, resolvePatternBuffer(ownerMachine),
                readActiveSlots(handler), result);
    }

    private static void collectMarkedPatternRecipesFromParts(IRecipeLogicMachine machine,
            IRecipeCapabilityMachine capabilityMachine, Set<GTRecipe> result) {
        if (!(machine instanceof IMultiController controller)) return;
        for (IMultiPart part : controller.getParts()) {
            if (part == null) continue;
            Object patternMachine = resolvePatternBuffer(part);
            Object slotSource = patternMachine == null ? part : patternMachine;
            collectMarkedPatternRecipesFromMachine(capabilityMachine, part, patternMachine,
                    readActiveSlots(slotSource), result);
        }
    }

    private static void collectMarkedPatternRecipesFromMachine(IRecipeCapabilityMachine capabilityMachine,
            Object ownerMachine, Object patternMachine, int[] activeSlots, Set<GTRecipe> result) {
        if (!(patternMachine instanceof RecipeTypePatternSlotAccess access)
                || !(patternMachine instanceof MEPatternBufferPartMachineBase)) {
            return;
        }
        if (activeSlots == null || activeSlots.length == 0) return;
        for (int slot : activeSlots) {
            GTRecipe recipe = access.gtShanhai$getPatternRecipe(slot);
            if (recipe != null && access.gtShanhai$slotAllowsRecipe(slot, recipe)) {
                activatePatternRecipe(capabilityMachine, ownerMachine, recipe, slot);
                result.add(recipe);
            }
        }
    }

    private static Object resolvePatternBuffer(Object ownerMachine) {
        if (ownerMachine instanceof RecipeTypePatternSlotAccess) return ownerMachine;
        if (ownerMachine instanceof MEPatternBufferProxyPartMachine proxy) {
            return proxy.getBuffer();
        }
        return null;
    }

    private static void activatePatternRecipe(IRecipeCapabilityMachine capabilityMachine, Object ownerMachine,
            GTRecipe recipe, int slot) {
        boolean guardFail = capabilityMachine == null || recipe == null || !(ownerMachine instanceof IMEPatternPartMachine);
        if (guardFail) return;
        MEPatternRecipeHandlePart handlePart = MEPatternRecipeHandlePart.of((IMEPatternPartMachine) ownerMachine);
        int handledSlot = handlePart.handleRecipe(recipe, copyRecipeContents(recipe.inputs), true, true);
        if (handledSlot < 0) return;
        capabilityMachine.tryAddAndActiveMERhp(handlePart, recipe, handledSlot);
    }

    private static Reference2ObjectMap<RecipeCapability<?>, List<Object>> copyRecipeContents(
            Map<RecipeCapability<?>, List<Content>> contents) {
        Reference2ObjectMap<RecipeCapability<?>, List<Object>> copied = new Reference2ObjectOpenHashMap<>();
        if (contents == null || contents.isEmpty()) return copied;
        for (Map.Entry<RecipeCapability<?>, List<Content>> entry : contents.entrySet()) {
            List<Object> values = new ObjectArrayList<>();
            List<Content> contentList = entry.getValue();
            if (contentList != null) {
                for (Content content : contentList) {
                    if (content != null) {
                        values.add(content.getContent());
                    }
                }
            }
            copied.put(entry.getKey(), values);
        }
        return copied;
    }

    private static Set<GTRecipe> applyRecipeTypeSwitch(IRecipeLogicMachine machine, Set<GTRecipe> recipes) {
        if (machine == null || recipes == null || recipes.isEmpty()) return recipes;

        DShanhaiConfig.ConfigValues.RecipeTypePatternSwitchMode mode =
                DShanhaiConfig.COMMON.recipeTypePatternSwitchMode.get();

        // 严格模式：必须有山海可编程仓随主机成型并成功把宿主切到样板类型才放行；
        // 无可编程仓或宿主无法切换时，虚拟样板不参与执行（保留旧语义，要求样板类型唯一）。
        if (mode == DShanhaiConfig.ConfigValues.RecipeTypePatternSwitchMode.PROGRAMMABLE_HATCH_REQUIRED) {
            GTRecipeType target = getUniqueRecipeType(recipes);
            if (target == null) return Collections.emptySet();
            return ProgrammableHatchPartMachine.switchRecipeTypeFor(machine, target, true)
                    ? recipes
                    : Collections.emptySet();
        }

        // 纯虚拟注入（默认 VIRTUAL_ACTIVE_TYPE）：不校验宿主是否原生支持该配方类型，
        // 也不切换宿主 activeRecipeType。样板配方已作为完整 GTRecipe 通过 MEPatternRecipeHandlePart
        // 挂载并入执行队列，执行不依赖宿主原生类型，故跨配方类型样板可虚拟直跑，且支持多类型混装。
        return recipes;
    }

    private static GTRecipeType getUniqueRecipeType(Set<GTRecipe> recipes) {
        GTRecipeType target = null;
        for (GTRecipe recipe : recipes) {
            if (recipe == null || recipe.recipeType == null) continue;
            if (target == null) {
                target = recipe.recipeType;
                continue;
            }
            if (!recipeTypeEquals(target, recipe.recipeType)) {
                return null;
            }
        }
        return target;
    }

    private static void collectDeclaredRecipeTypesFromHandler(Object handler, Set<GTRecipeType> result) {
        Object machine = resolvePatternBuffer(findHandlerMachine(handler));
        if (!(machine instanceof RecipeTypePatternSlotAccess access)
                || !(machine instanceof MEPatternBufferPartMachineBase)) {
            return;
        }

        int[] activeSlots = readActiveSlots(handler);
        if (activeSlots == null || activeSlots.length == 0) return;
        for (int slot : activeSlots) {
            GTRecipeType type = PatternRecipeTypeHelper.resolveRecipeType(access.gtShanhai$getPatternRecipeTypeId(slot));
            if (type != null) {
                result.add(type);
            }
        }
    }

    private static Object findHandlerMachine(Object handler) {
        Object current = unwrapProxyHandler(handler);
        Object machine = invokeNoArg(current, "getMachine");
        if (machine != null) return machine;
        machine = invokeNoArg(current, "mo293getMachine");
        if (machine != null) return machine;
        machine = invokeNoArg(current, "mo292getMachine");
        if (machine != null) return machine;
        return invokeNoArg(current, "mo291getMachine");
    }

    private static int[] readActiveSlots(Object handler) {
        Object activeSlots = invokeNoArg(handler, "getActiveSlots");
        return activeSlots instanceof int[] ? (int[]) activeSlots : new int[0];
    }

    private static Object unwrapProxyHandler(Object handler) {
        Object current = handler;
        for (int i = 0; i < 4; i++) {
            Object next = readField(current, "handler");
            if (next == null || next == current) return current;
            current = next;
        }
        return current;
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Iterator<GTRecipe> searchType(GTRecipeType type, IRecipeLogicMachine machine) {
        if (type == null || machine == null) return Collections.emptyIterator();
        Iterator<GTRecipe> iterator = type.searchRecipe(machine);
        return iterator == null ? Collections.emptyIterator() : iterator;
    }

    private static boolean recipeTypeEquals(GTRecipeType left, GTRecipeType right) {
        return left == right || (left != null && right != null && left.registryName != null
                && left.registryName.equals(right.registryName));
    }

    private static final class AppendingRecipeIterator implements Iterator<GTRecipe> {

        private final Iterator<GTRecipe> extraRecipes;
        private Iterator<GTRecipe> current;

        private AppendingRecipeIterator(Iterator<GTRecipe> baseIterator, Iterator<GTRecipe> extraRecipes) {
            this.current = baseIterator == null ? Collections.emptyIterator() : baseIterator;
            this.extraRecipes = extraRecipes == null ? Collections.emptyIterator() : extraRecipes;
        }

        @Override
        public boolean hasNext() {
            if (current.hasNext()) {
                return true;
            }
            return extraRecipes.hasNext();
        }

        @Override
        public GTRecipe next() {
            if (current.hasNext()) {
                return current.next();
            }
            if (extraRecipes.hasNext()) {
                return extraRecipes.next();
            }
            throw new NoSuchElementException();
        }
    }
}
