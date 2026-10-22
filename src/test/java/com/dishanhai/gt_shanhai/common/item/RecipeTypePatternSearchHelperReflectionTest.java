package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import appeng.api.stacks.AEKey;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

class RecipeTypePatternSearchHelperReflectionTest {

    @Test
    void firstNoArgInvocationFailureDoesNotMarkTheMethodMissing() throws Exception {
        FlakyNoArg target = new FlakyNoArg();

        assertNull(invokeNoArg(target, "read"));
        assertEquals("ready", invokeNoArg(target, "read"));
        assertEquals(2, target.calls);
    }

    @Test
    void firstInheritedIntInvocationFailureDoesNotMarkTheMethodMissing() throws Exception {
        InheritedInt target = new InheritedInt();

        assertNull(invokeWithInt(target, "getInternalSlot", -1));
        assertEquals("slot-2", invokeWithInt(target, "getInternalSlot", 2));
    }

    @Test
    void firstAddInvocationFailureCanBeRetried() throws Exception {
        FlakyAdd target = new FlakyAdd();
        Method helper = helper("invokeAdd", Object.class, AEKey.class, long.class);

        helper.invoke(null, target, null, 3L);
        helper.invoke(null, target, null, 7L);

        assertEquals(2, target.calls);
        assertEquals(7L, target.added);
    }

    @Test
    void inheritedFieldsRemainReadableAndMissingMembersAreNegativelyCached() throws Exception {
        InheritedInt target = new InheritedInt();
        Method readField = helper("readField", Object.class, String.class);

        assertEquals("inherited", readField.invoke(null, target, "label"));
        assertNull(readField.invoke(null, target, "missingField"));
        assertEquals(Optional.empty(), classCache("FIELD_CACHE", InheritedInt.class).get("missingField"));
        assertNull(invokeNoArg(target, "missingMethod"));
        assertEquals(Optional.empty(), classCache("METHOD_CACHE", InheritedInt.class).get("missingMethod"));
        assertNull(invokeWithInt(target, "missingIntMethod", 0));
        assertEquals(Optional.empty(), classCache("METHOD_CACHE", InheritedInt.class).get("missingIntMethod(int)"));
    }

    @Test
    void inaccessibleFieldDoesNotBecomeMissingOrEscapeAsRuntimeFailure() throws Exception {
        Field value = String.class.getDeclaredField("value");
        assumeFalse(value.trySetAccessible(), "此測試要求 java.base/java.lang 未向測試模組開放");
        Method readField = helper("readField", Object.class, String.class);

        assertNull(assertDoesNotThrow(() -> readField.invoke(null, "value", "value")));
        assertFalse(classCache("FIELD_CACHE", String.class).containsKey("value"),
                "存取限制不是成員不存在，不能寫入負快取");
    }

    @Test
    void repeatedInvocationFailureWarnsOnceWithoutDisablingRetries() throws Exception {
        AlwaysFails target = new AlwaysFails();
        WarningCounter counter = new WarningCounter(AlwaysFails.class.getName());
        Logger root = (Logger) LogManager.getRootLogger();
        counter.start();
        root.addAppender(counter);
        try {
            assertNull(invokeNoArg(target, "read"));
            assertNull(invokeNoArg(target, "read"));

            assertEquals(2, target.calls);
            assertEquals(1, counter.count, "同一類別與成員的反射失敗只告警一次");
            assertTrue(classCache("METHOD_CACHE", AlwaysFails.class).get("read").isPresent());
        } finally {
            root.removeAppender(counter);
            counter.stop();
        }
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        return helper("invokeNoArg", Object.class, String.class).invoke(null, target, name);
    }

    private static Object invokeWithInt(Object target, String name, int argument) throws Exception {
        return helper("invokeWithInt", Object.class, String.class, int.class)
                .invoke(null, target, name, argument);
    }

    private static Method helper(String name, Class<?>... parameters) throws Exception {
        Method method = RecipeTypePatternSearchHelper.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Optional<?>> classCache(String name, Class<?> targetType) throws Exception {
        Field field = RecipeTypePatternSearchHelper.class.getDeclaredField(name);
        field.setAccessible(true);
        Map<Class<?>, Map<String, Optional<?>>> cache =
                (Map<Class<?>, Map<String, Optional<?>>>) field.get(null);
        return cache.get(targetType);
    }

    private static final class FlakyNoArg {
        private int calls;

        private String read() {
            if (++calls == 1) throw new IllegalStateException("transient read failure");
            return "ready";
        }
    }

    private static class IntAccess {
        @SuppressWarnings("unused")
        private final String label = "inherited";

        @SuppressWarnings("unused")
        protected String getInternalSlot(int slot) {
            if (slot < 0) throw new IndexOutOfBoundsException("slot " + slot);
            return "slot-" + slot;
        }
    }

    private static final class InheritedInt extends IntAccess {
    }

    private static final class FlakyAdd {
        private int calls;
        private long added;

        @SuppressWarnings("unused")
        private void add(AEKey key, long amount) {
            if (++calls == 1) throw new IllegalStateException("transient add failure");
            added += amount;
        }
    }

    private static final class AlwaysFails {
        private int calls;

        private Object read() {
            calls++;
            throw new IllegalStateException("persistent read failure");
        }
    }

    private static final class WarningCounter extends AbstractAppender {
        private final String targetClassName;
        private int count;

        private WarningCounter(String targetClassName) {
            super("shanhai-reflection-warning-test", null, null, false);
            this.targetClassName = targetClassName;
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel() == org.apache.logging.log4j.Level.WARN
                    && event.getMessage().getFormattedMessage().contains(targetClassName)) {
                count++;
            }
        }
    }
}
