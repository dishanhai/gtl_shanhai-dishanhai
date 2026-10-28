package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：Integrate 結構特效移植的純運算契約，無須啟動 Minecraft。 */
class IntegrateHostFxTest {
    private static final String PACKAGE = "com.dishanhai.gt_shanhai.client.renderer.hostfx.";

    @Test
    void changingHostsInvalidatesNamesEnergyTickAndOutputsEvenAtTheSameTick() throws Exception {
        Class<?> type = Class.forName(PACKAGE + "HostFxReadingCache");
        Object cache = type.getConstructor().newInstance();
        Object firstHost = new String("same coordinates"), secondHost = new String("same coordinates");
        var select = type.getMethod("selectHost", Object.class);
        assertEquals(true, select.invoke(cache, firstHost));
        Object state = field(cache, "state");
        state.getClass().getField("energyInKind").setInt(state, 2);
        state.getClass().getField("hostOutputs").set(state, new String[]{"first host"});
        setField(cache, "lastBuildTick", 100L);
        setField(cache, "lastNameMask", 3L);
        setField(cache, "namesResolved", true);
        setField(cache, "lastHostOutIds", new int[]{4});
        assertEquals(false, select.invoke(cache, firstHost));
        assertSame(state, field(cache, "state"));
        assertEquals(true, select.invoke(cache, secondHost));
        Object fresh = field(cache, "state");
        assertNotSame(state, fresh);
        assertEquals(-1, fresh.getClass().getField("energyInKind").get(fresh));
        assertEquals(0, ((String[]) fresh.getClass().getField("hostOutputs").get(fresh)).length);
        assertEquals(Long.MIN_VALUE, field(cache, "lastBuildTick"));
        assertEquals(Long.MIN_VALUE, field(cache, "lastNameMask"));
        assertEquals(false, field(cache, "namesResolved"));
        assertNull(field(cache, "lastHostOutIds"));
    }

    @Test
    void modulePowerFallbackSaturatesAndDoesNotClaimToCoverRemoteModules() throws Exception {
        Class<?> stateType = Class.forName(PACKAGE + "HostFxGlassArModel$State");
        Object state = stateType.getConstructor().newInstance();
        stateType.getField("installedModules").setInt(state, 2);
        stateType.getField("moduleSlots").set(state, new int[]{0, 1});
        stateType.getField("modEuPerTick").set(state, new long[]{Long.MAX_VALUE, 1L});
        var sum = Class.forName(PACKAGE + "HostFxGlassArModel").getDeclaredMethod("moduleEuSum", stateType);
        sum.setAccessible(true);
        assertEquals(Long.MAX_VALUE, sum.invoke(null, state));
        stateType.getField("installedModules").setInt(state, 40);
        assertEquals(-1L, sum.invoke(null, state));
    }

    @Test
    void authoritativeModulePowerIncludesRemoteModulesAndOverridesLocalFallback() throws Exception {
        Class<?> stateType = Class.forName(PACKAGE + "HostFxGlassArModel$State");
        Object state = stateType.getConstructor().newInstance();
        stateType.getField("installedModules").setInt(state, 40);
        stateType.getField("moduleEuTotal").setLong(state, 123456L);
        var sum = Class.forName(PACKAGE + "HostFxGlassArModel").getDeclaredMethod("moduleEuSum", stateType);
        sum.setAccessible(true);
        assertEquals(123456L, sum.invoke(null, state));
    }

    @Test
    void itemSummaryExplainsItsIdAggregationWithoutHidingFluidOrNbtLimits() throws Exception {
        Class<?> type = Class.forName(PACKAGE + "HostFxGlassArModel");
        var format = type.getDeclaredMethod("totalOutputText", int.class, long.class);
        format.setAccessible(true);
        String summary = (String) format.invoke(null, 2, 10L);
        assertTrue(summary.contains("物品"));
        assertTrue(summary.contains("ID类"));
        Class<?> stateType = Class.forName(PACKAGE + "HostFxGlassArModel$State");
        Object model = type.getMethod("build", stateType).invoke(null, stateType.getConstructor().newInstance());
        var texts = (java.util.List<?>) model.getClass().getField("texts").get(model);
        var notes = new java.util.ArrayList<String>();
        for (Object text : texts) {
            int slot = text.getClass().getField("slot").getInt(text);
            if (text.getClass().getField("zone").getInt(text) != type.getField("Z_MODLIST").getInt(null)
                    || slot < 102 || slot > 103) continue;
            String line = (String) text.getClass().getField("text").get(text);
            notes.add(line);
            float x = text.getClass().getField("x").getFloat(text);
            float y = text.getClass().getField("y").getFloat(text);
            assertTrue(x >= 0 && x + line.codePointCount(0, line.length()) * 9 <= 1100);
            assertTrue(y >= 0 && y + 8 <= 200, "說明文字必須留在22×4格AR面板內");
        }
        assertEquals(java.util.List.of("物品按ID汇总；流体与NBT明细", "以配方／输出仓为准"), notes);
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void setField(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @Test
    void shaderReloadInvalidatesTheCachedHandleAndAllowsFailedLoadsToRetry() throws Exception {
        Class<?> type = Class.forName(PACKAGE + "HostFxLazyHandle");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<Object> factory = () -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("first load");
            return new Object();
        };
        Object handle = type.getConstructor(java.util.function.Supplier.class).newInstance(factory);
        assertNull(type.getMethod("get").invoke(handle));
        assertNull(type.getMethod("get").invoke(handle));
        assertEquals(1, calls.get());
        type.getMethod("invalidate").invoke(handle);
        Object shader = type.getMethod("get").invoke(handle);
        assertNotNull(shader);
        assertSame(shader, type.getMethod("get").invoke(handle));
        assertSame(shader, type.getMethod("invalidate").invoke(handle));
        assertNotSame(shader, type.getMethod("get").invoke(handle));
        assertEquals(2, type.getMethod("builds").invoke(handle));
    }

    @Test
    void hostParametersClampAndDisabledEffectsFreezeTheExistingPhase() throws Exception {
        Class<?> type = Class.forName(PACKAGE + "HostFxState");
        var feed = type.getMethod("feed", boolean.class, float.class, float.class,
                float.class, float.class, boolean.class);
        type.getMethod("clear").invoke(null);
        feed.invoke(null, true, 120F, -9F, 90F, 30F, true);
        assertEquals(60F, type.getMethod("ringDegPerSec").invoke(null));
        assertEquals(0F, type.getMethod("shipUpDegPerSec").invoke(null));
        assertEquals(60F, type.getMethod("shipDownDegPerSec").invoke(null));
        assertEquals(12F, type.getMethod("holeRadiusBlocks").invoke(null));
        type.getMethod("animSeconds").invoke(null);
        Thread.sleep(5);
        float phase = (Float) type.getMethod("animSeconds").invoke(null);
        assertTrue(phase > 0F);
        feed.invoke(null, false, 12F, 9F, 9F, 5.2F, false);
        float frozen = (Float) type.getMethod("animSeconds").invoke(null);
        Thread.sleep(5);
        assertEquals(frozen, type.getMethod("animSeconds").invoke(null));
        assertTrue(frozen >= phase);
        type.getMethod("clear").invoke(null);
    }

    @Test
    void arModelUsesShadowRadiusAndPreservesThinBeamsWhenMainPillarsDisappear() throws Exception {
        Class<?> stateType = Class.forName(PACKAGE + "HostFxGlassArModel$State");
        Class<?> modelType = Class.forName(PACKAGE + "HostFxGlassArModel");
        Object state = stateType.getConstructor().newInstance();
        stateType.getField("holeRadiusBlocks").setFloat(state, 6F);
        stateType.getField("animSeconds").setFloat(state, 10F);
        Object normal = modelType.getMethod("build", stateType).invoke(null, state);
        double[] physical = (double[]) normal.getClass().getField("physBlocks").get(normal);
        double rs = 6D / modelType.getField("B_CRIT").getDouble(null);
        assertArrayEquals(new double[]{6, rs, 1.5 * rs, 3 * rs, 11 * rs}, physical, 1e-6);
        var count = normal.getClass().getMethod("count", int.class, String.class);
        int top = modelType.getField("Z_TOP").getInt(null);
        assertEquals(4, count.invoke(normal, top, "top.md_pillar"));
        assertEquals(12, count.invoke(normal, top, "top.thin"));
        stateType.getField("fxOn").setBoolean(state, false);
        stateType.getField("degraded").setBoolean(state, true);
        stateType.getField("mdPillarCount").setInt(state, 0);
        Object degraded = modelType.getMethod("build", stateType).invoke(null, state);
        assertEquals(0, count.invoke(degraded, top, "top.md_pillar"));
        assertEquals(12, count.invoke(degraded, top, "top.thin"));
        assertEquals(normal.getClass().getField("ringAngleDeg").get(normal),
                degraded.getClass().getField("ringAngleDeg").get(degraded));
    }
}
