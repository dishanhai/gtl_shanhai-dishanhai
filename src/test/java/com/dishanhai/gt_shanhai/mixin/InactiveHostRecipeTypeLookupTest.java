package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production lookup hook with registry/world boundaries replaced, without launching Minecraft. */
public class InactiveHostRecipeTypeLookupTest {
    private static final String PREFIX = Type.getInternalName(InactiveHostRecipeTypeLookupTest.class);
    private static final String GT = "com/gregtechceu/gtceu/api/";
    private static final String ITEM = "com/dishanhai/gt_shanhai/common/item/";
    private static final String CONFIG = "com/dishanhai/gt_shanhai/config/DShanhaiConfig";
    public interface Holder {}
    public interface Machine extends Holder { RecipeType[] getRecipeTypes(); }
    public interface Selectable extends Machine {}
    public interface Capability { List<Part> getMEPatternRecipeHandleParts(); }
    public static class RecipeType {
        public String registryName;
        RecipeType(String id) { registryName = id; }
    }
    public static class Recipe {
        public RecipeType recipeType;
        Recipe(RecipeType type) { recipeType = type; }
    }
    public static class Part {
        final ObjectSet<Recipe> cached = new ObjectOpenHashSet<>();
        public ObjectSet<Recipe> getCachedGTRecipe() { return cached; }
    }
    public static class Host implements Machine, Capability {
        final RecipeType blast = new RecipeType("gtceu:blast_furnace");
        final RecipeType alloy = new RecipeType("gtceu:alloy_smelter");
        final RecipeType alloyBlast = new RecipeType("gtceu:alloy_blast_smelter");
        final Part part = new Part();
        final Set<Recipe> marked = new LinkedHashSet<>();
        RecipeType forcedType;
        public RecipeType[] getRecipeTypes() { return new RecipeType[]{blast, alloy, alloyBlast}; }
        public List<Part> getMEPatternRecipeHandleParts() { return List.of(part); }
    }
    public static class SelectableHost extends Host implements Selectable {}
    public interface Additional {
        List<Recipe> getAdditionalRecipes();
        void setAdditionalRecipes(List<Recipe> recipes);
    }
    public static class RecipeIterator implements Additional {
        List<Recipe> recipes;
        public List<Recipe> getAdditionalRecipes() { return recipes; }
        public void setAdditionalRecipes(List<Recipe> value) { recipes = value; }
    }
    public static class Search {
        static int calls;
        static int lookupCalls;
        static boolean warmRejectedCache;
        public static Set<Recipe> collectActiveMarkedPatternRecipes(Machine machine) {
            calls++;
            if (warmRejectedCache) {
                ((Host) machine).part.cached.addAll(((Host) machine).marked);
                return Set.of();
            }
            return ((Host) machine).marked;
        }
        public static Set<Recipe> collectActiveMarkedPatternRecipesForLookup(Machine machine) {
            lookupCalls++;
            return collectActiveMarkedPatternRecipes(machine);
        }
    }
    public static class Shared {
        public static boolean isSharedWithAny(RecipeType recipeType, RecipeType[] types) { return false; }
    }
    public static class ProgrammableHatch {
        public static RecipeType getSelectedRecipeTypeFor(Machine machine) { return ((Host) machine).forcedType; }
    }
    public static class Config { public static Settings COMMON = new Settings(); }
    public static class Settings {
        public Flag recipeTypePatternAllowUnsupportedHostRecipeTypes = new Flag();
        public ModeFlag recipeTypePatternSwitchMode = new ModeFlag();
    }
    public enum Mode { PROGRAMMABLE_HATCH_REQUIRED, VIRTUAL_ACTIVE_TYPE }
    public static class ModeFlag {
        Mode mode = Mode.VIRTUAL_ACTIVE_TYPE;
        public Object get() { return mode; }
    }
    public static class Flag {
        boolean enabled;
        public Object get() { return enabled; }
    }
    private static class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }

    private Object hook;
    private Method append;

    @BeforeEach void loadProductionHook() throws Exception {
        Config.COMMON = new Settings();
        Search.calls = 0;
        Search.lookupCalls = 0;
        Search.warmRejectedCache = false;
        String mixin = "com/dishanhai/gt_shanhai/mixin/InactiveHostRecipeTypeLookupMixin";
        Map<String, String> names = new HashMap<>();
        names.put(mixin, PREFIX + "$GeneratedHook");
        names.put(GT + "capability/recipe/IRecipeCapabilityHolder", PREFIX + "$Holder");
        names.put(GT + "machine/feature/IRecipeLogicMachine", PREFIX + "$Machine");
        names.put("com/dishanhai/gt_shanhai/api/machine/SelectableRecipeTypeSetMachine", PREFIX + "$Selectable");
        names.put("org/gtlcore/gtlcore/api/machine/trait/IRecipeCapabilityMachine", PREFIX + "$Capability");
        names.put("org/gtlcore/gtlcore/api/machine/trait/MEPatternRecipeHandlePart", PREFIX + "$Part");
        names.put("org/gtlcore/gtlcore/api/recipe/IAdditionalRecipeIterator", PREFIX + "$Additional");
        names.put(GT + "recipe/GTRecipe", PREFIX + "$Recipe");
        names.put(GT + "recipe/GTRecipeType", PREFIX + "$RecipeType");
        names.put(GT + "recipe/lookup/RecipeIterator", PREFIX + "$RecipeIterator");
        names.put("net/minecraft/resources/ResourceLocation", "java/lang/String");
        names.put(ITEM + "RecipeTypePatternSearchHelper", PREFIX + "$Search");
        names.put(ITEM + "RecipeTypeSharedSearchSets", PREFIX + "$Shared");
        names.put("com/dishanhai/gt_shanhai/common/machine/part/ProgrammableHatchPartMachine", PREFIX + "$ProgrammableHatch");
        names.put(CONFIG, PREFIX + "$Config");
        names.put(CONFIG + "$ConfigValues", PREFIX + "$Settings");
        names.put(CONFIG + "$ConfigValues$RecipeTypePatternSwitchMode", PREFIX + "$Mode");
        names.put("net/minecraftforge/common/ForgeConfigSpec$BooleanValue", PREFIX + "$Flag");
        names.put("net/minecraftforge/common/ForgeConfigSpec$EnumValue", PREFIX + "$ModeFlag");
        ClassWriter writer = new ClassWriter(0);
        try (var input = getClass().getClassLoader().getResourceAsStream(mixin + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassRemapper(writer, new SimpleRemapper(names)), 0);
        }
        Class<?> type = new Loader().define(writer.toByteArray());
        hook = type.getConstructor().newInstance();
        append = type.getDeclaredMethod("gtShanhai$appendInactiveHostRecipeTypes",
                Holder.class, Predicate.class, CallbackInfoReturnable.class);
        append.setAccessible(true);
    }

    private RecipeIterator lookup(Host host, Recipe... existing) throws Exception {
        var field = hook.getClass().getDeclaredField("recipeType");
        field.setAccessible(true);
        field.set(hook, host.blast);
        RecipeIterator iterator = new RecipeIterator();
        iterator.recipes = new ArrayList<>(List.of(existing));
        var callback = new CallbackInfoReturnable<RecipeIterator>("getRecipeIterator", false, iterator);
        append.invoke(hook, host, (Predicate<Recipe>) recipe -> true, callback);
        return iterator;
    }

    @Test void coldAlloySmelterPatternsAreFoundWithoutSwitchingModesOrWarmingCache() throws Exception {
        Host host = new Host();
        Recipe alloy = new Recipe(host.alloy), alloyBlast = new Recipe(host.alloyBlast);
        host.marked.addAll(List.of(alloy, alloyBlast));
        assertEquals(List.of(alloy, alloyBlast), lookup(host).recipes,
                "Both inactive modes must be searched before any pattern has populated the native recipe cache");
        assertEquals(1, Search.calls);
        assertEquals(1, Search.lookupCalls, "Native lookup must use the path that cannot pull consumable inputs");
        assertTrue(host.part.cached.isEmpty());
    }

    @Test void cachedPlainPatternsRemainAvailable() throws Exception {
        Host host = new Host();
        Recipe cached = new Recipe(host.alloy);
        host.part.cached.add(cached);
        assertEquals(List.of(cached), lookup(host).recipes);
    }

    @Test void keepsExistingCandidatesAndDeduplicatesNewlyResolvedPatterns() throws Exception {
        Host host = new Host();
        Recipe blast = new Recipe(host.blast), alloy = new Recipe(host.alloy);
        host.marked.add(alloy);
        host.part.cached.add(alloy);
        assertEquals(List.of(blast, alloy), lookup(host, blast, alloy).recipes);
    }

    @Test void rejectsForeignTypesUnlessExplicitlyAllowed() throws Exception {
        Host host = new Host();
        Recipe foreign = new Recipe(new RecipeType("gtceu:macerator"));
        host.marked.add(foreign);
        host.part.cached.add(foreign);
        assertTrue(lookup(host).recipes.isEmpty());
        Config.COMMON.recipeTypePatternAllowUnsupportedHostRecipeTypes.enabled = true;
        assertEquals(List.of(foreign), lookup(host).recipes);
    }

    @Test void selectableHostsKeepTheirOwnSearchScope() throws Exception {
        Host host = new SelectableHost();
        host.marked.add(new Recipe(host.alloy));
        assertTrue(lookup(host).recipes.isEmpty());
        assertEquals(0, Search.calls);
    }

    @Test void strictModeDoesNotWarmRejectedPatternsIntoTheNativeCache() throws Exception {
        Host host = new Host();
        host.marked.add(new Recipe(host.alloy));
        Search.warmRejectedCache = true;
        Config.COMMON.recipeTypePatternSwitchMode.mode = Mode.PROGRAMMABLE_HATCH_REQUIRED;
        assertTrue(lookup(host).recipes.isEmpty());
        assertEquals(0, Search.calls, "Strict mode must not resolve and activate a rejected pattern as a side effect");
        assertTrue(host.part.cached.isEmpty());
    }

    @Test void programmableHatchSelectionDoesNotResolveOtherModes() throws Exception {
        Host host = new Host();
        host.forcedType = host.blast;
        host.marked.add(new Recipe(host.alloy));
        assertTrue(lookup(host).recipes.isEmpty(), "A programmable hatch selecting blast recipes must stay exclusive");
        assertEquals(0, Search.lookupCalls);
    }

    @Test void lookupCircuitRepairCannotReachNetworkExtraction() throws Exception {
        ClassNode helper = new ClassNode();
        try (var input = getClass().getClassLoader().getResourceAsStream(ITEM + "RecipeTypePatternSearchHelper.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(helper, 0);
        }
        MethodNode topUp = helper.methods.stream().filter(method -> method.name.equals("topUpVirtualSupply")
                && Type.getArgumentTypes(method.desc).length == 6).findFirst().orElse(null);
        assertNotNull(topUp, "Lookup circuit repair needs a presence-only path before network extraction");
        Set<AbstractInsnNode> presenceOnly = reachable(topUp, false);
        assertTrue(presenceOnly.stream().anyMatch(node -> node instanceof MethodInsnNode call
                && call.name.equals("gtShanhai$addVirtualTargetToSlot")), "Virtual circuit presence must still be repaired");
        assertFalse(presenceOnly.stream().anyMatch(node -> node instanceof MethodInsnNode call
                && call.name.equals("extract")), "An exhausted AE order must never trigger another network extraction");
        assertTrue(reachable(topUp, true).stream().anyMatch(node -> node instanceof MethodInsnNode call
                && call.name.equals("extract")), "The original explicit virtual-supply path must remain available");
    }

    @Test void lookupUsesPresenceOnlyCollectionAndItsOwnCache() throws Exception {
        ClassNode helper = new ClassNode();
        try (var input = getClass().getClassLoader().getResourceAsStream(ITEM + "RecipeTypePatternSearchHelper.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(helper, 0);
        }
        MethodNode lookup = helper.methods.stream().filter(method -> method.name.equals(
                "collectActiveMarkedPatternRecipesForLookup")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(lookup.instructions.toArray()).anyMatch(node -> node instanceof MethodInsnNode call
                && call.name.equals("collectActiveMarkedPatternRecipes")
                && node.getPrevious().getOpcode() == Opcodes.ICONST_0));
        Class<?> helperClass = Class.forName(ITEM.replace('/', '.') + "RecipeTypePatternSearchHelper");
        var activeCache = helperClass.getDeclaredField("ACTIVE_MARKED_RECIPE_CACHE");
        var lookupCache = helperClass.getDeclaredField("ACTIVE_LOOKUP_MARKED_RECIPE_CACHE");
        activeCache.setAccessible(true);
        lookupCache.setAccessible(true);
        assertNotSame(activeCache.get(null), lookupCache.get(null),
                "Lookup-before-execution and execution-before-lookup must not share side-effect caches");
    }

    /** Follow all control-flow branches except the known final boolean argument; no Minecraft initialization. */
    private static Set<AbstractInsnNode> reachable(MethodNode method, boolean allowConsumableTopUp) {
        Set<AbstractInsnNode> visited = new HashSet<>();
        Deque<AbstractInsnNode> pending = new ArrayDeque<>();
        pending.add(method.instructions.getFirst());
        while (!pending.isEmpty()) {
            AbstractInsnNode instruction = pending.removeFirst();
            if (!visited.add(instruction)) continue;
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) continue;
            if (instruction instanceof JumpInsnNode jump) {
                AbstractInsnNode previous = instruction.getPrevious();
                boolean known = previous instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ILOAD
                        && variable.var == 5 && (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE);
                boolean take = opcode == Opcodes.IFEQ ? !allowConsumableTopUp : allowConsumableTopUp;
                if (!known || take) pending.add(jump.label);
                if (opcode == Opcodes.GOTO || known && take) continue;
            }
            if (instruction.getNext() != null) pending.add(instruction.getNext());
        }
        return visited;
    }
}
