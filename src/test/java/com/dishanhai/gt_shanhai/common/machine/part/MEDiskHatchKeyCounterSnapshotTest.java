package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MEDiskHatchKeyCounterSnapshotTest {

    private static final String KEY_COUNTER = "appeng.api.stacks.KeyCounter";
    private static final String SNAPSHOT = MEDiskHatchPartMachine.class.getName() + "$KeyCounterSnapshot";
    private static final Path HATCH_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "MEDiskHatchPartMachine.java");
    private static final AEKeyType TEST_KEY_TYPE = new AEKeyType(
            new ResourceLocation("gt_shanhai", "snapshot_test"), TestKey.class, Component.literal("snapshot test")) {
        @Override
        public AEKey readFromPacket(FriendlyByteBuf input) {
            return null;
        }

        @Override
        public AEKey loadKeyFromTag(CompoundTag tag) {
            return null;
        }
    };

    @Test
    void snapshotLookupMatchesItsSingleIteratedEntry() throws Exception {
        MixinAwareClassLoader loader = new MixinAwareClassLoader(getClass().getClassLoader());
        Class<?> counterType = loader.loadClass(KEY_COUNTER);
        Object source = newCounter(counterType);
        TestKey key = new TestKey("lookup");
        add(counterType, source, key, 4L);
        add(counterType, source, key, 3L);

        Object snapshot = newSnapshot(loader, counterType, source, false);
        Object out = newCounter(counterType);
        addSnapshot(loader, counterType, snapshot, out);

        assertEquals(1, size(counterType, out));
        assertEquals(7L, iteratedAmount(out));
        assertEquals(7L, get(counterType, out, key));
    }

    @Test
    void snapshotReusesClearedCountersBackingIndex() throws Exception {
        MixinAwareClassLoader loader = new MixinAwareClassLoader(getClass().getClassLoader());
        Class<?> counterType = loader.loadClass(KEY_COUNTER);
        TestKey key = new TestKey("reuse");
        Object source = newCounter(counterType);
        add(counterType, source, key, 11L);
        Object snapshot = newSnapshot(loader, counterType, source, true);

        Object out = newCounter(counterType);
        add(counterType, out, key, 1L);
        Object backingIndex = backingIndex(out, key);
        counterType.getMethod("clear").invoke(out);

        addSnapshot(loader, counterType, snapshot, out);

        assertSame(backingIndex, backingIndex(out, key));
        assertEquals(11L, get(counterType, out, key));
        assertEquals(1, size(counterType, out));
    }

    @Test
    void snapshotReplayAvoidsBulkCopyingVariantSubmaps() throws IOException {
        String source = Files.readString(HATCH_SOURCE);

        assertEquals(-1, source.indexOf("out.addAll(counter)"),
                "KeyCounterSnapshot.addTo 不能整表 addAll，否则空输出计数器会触发 VariantCounter.copy()");
        assertTrue(source.contains("out.add(entry.getKey(), entry.getLongValue())"),
                "KeyCounterSnapshot.addTo 应逐条回放，避开 AE2 子表 copy 热点");
    }

    private static Object newCounter(Class<?> counterType) throws Exception {
        return counterType.getConstructor().newInstance();
    }

    private static Object newSnapshot(ClassLoader loader, Class<?> counterType, Object source,
                                      boolean preferCounterCopy) throws Exception {
        Class<?> snapshotType = loader.loadClass(SNAPSHOT);
        Constructor<?> constructor = snapshotType.getDeclaredConstructor(counterType, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(source, preferCounterCopy);
    }

    private static void addSnapshot(ClassLoader loader, Class<?> counterType, Object snapshot, Object out)
            throws Exception {
        Method addTo = loader.loadClass(SNAPSHOT).getDeclaredMethod("addTo", counterType);
        addTo.setAccessible(true);
        addTo.invoke(snapshot, out);
    }

    private static void add(Class<?> counterType, Object counter, AEKey key, long amount) throws Exception {
        counterType.getMethod("add", AEKey.class, long.class).invoke(counter, key, amount);
    }

    private static long get(Class<?> counterType, Object counter, AEKey key) throws Exception {
        return (long) counterType.getMethod("get", AEKey.class).invoke(counter, key);
    }

    private static int size(Class<?> counterType, Object counter) throws Exception {
        return (int) counterType.getMethod("size").invoke(counter);
    }

    private static long iteratedAmount(Object counter) {
        long amount = 0L;
        for (Object entry : (Iterable<?>) counter) {
            amount += ((Object2LongMap.Entry<?>) entry).getLongValue();
        }
        return amount;
    }

    private static Object backingIndex(Object counter, AEKey key) throws Exception {
        Field listsField = counter.getClass().getDeclaredField("lists");
        listsField.setAccessible(true);
        Reference2ObjectMap<?, ?> lists = (Reference2ObjectMap<?, ?>) listsField.get(counter);
        return lists.get(key.getPrimaryKey());
    }

    private static final class MixinAwareClassLoader extends ClassLoader {
        private static final String SNAPSHOT_OWNER = MEDiskHatchPartMachine.class.getName();
        private static final String VARIANT_COUNTER = "appeng.api.stacks.VariantCounter";
        private static final String KEY_LONG_MAP = "appeng.api.stacks.AEKey2LongMap";

        private MixinAwareClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = isIsolated(name) ? findClass(name) : super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream input = getParent().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytecode = input.readAllBytes();
                return defineClass(name, bytecode, 0, bytecode.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        private static boolean isIsolated(String name) {
            return name.equals(KEY_COUNTER)
                    || name.equals(VARIANT_COUNTER)
                    || name.startsWith(VARIANT_COUNTER + "$")
                    || name.equals(KEY_LONG_MAP)
                    || name.startsWith(KEY_LONG_MAP + "$")
                    || name.equals(SNAPSHOT_OWNER)
                    || name.startsWith(SNAPSHOT_OWNER + "$");
        }

    }

    private static final class TestKey extends AEKey {
        private final String id;
        private final Object primaryKey = new Object();

        private TestKey(String id) {
            this.id = id;
        }

        @Override
        public AEKeyType getType() {
            return TEST_KEY_TYPE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag() {
            return new CompoundTag();
        }

        @Override
        public Object getPrimaryKey() {
            return primaryKey;
        }

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("gt_shanhai", id);
        }

        @Override
        public void writeToPacket(FriendlyByteBuf output) {}

        @Override
        protected Component computeDisplayName() {
            return Component.literal(id);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {}
    }
}
