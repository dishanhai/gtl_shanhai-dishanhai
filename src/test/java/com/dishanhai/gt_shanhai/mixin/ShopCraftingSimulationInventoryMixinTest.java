package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.MEStorage;
import com.dishanhai.gt_shanhai.common.shop.ShopAutoCraftActionSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real shop reservation handlers without a woven Minecraft runtime (dishanhai). */
class ShopCraftingSimulationInventoryMixinTest {
    private static final AEKey KEY = new TestKey();

    @Test
    void lazyGtlCoreStockSubtractsEarlierPlansBeforeAnotherPlanUsesIt() throws Exception {
        Fixture fixture = new Fixture(100L);
        assertEquals(30L, fixture.available(shopSource(70L), Long.MAX_VALUE, 100L));
        assertEquals(1, fixture.queries.get());
    }

    @Test
    void fullyReservedStockIsUnavailableEvenWhenTheNativeListWasSkipped() throws Exception {
        Fixture fixture = new Fixture(100L);
        assertEquals(0L, fixture.available(shopSource(100L), Long.MAX_VALUE, 100L));
    }

    @Test
    void reservationIsAppliedBeforeTheRequestedAmountCap() throws Exception {
        Fixture fixture = new Fixture(100L);
        assertEquals(20L, fixture.available(shopSource(70L), 20L, 20L));
    }

    @Test
    void permissionOrExternalReservationLimitsAreNeverIncreased() throws Exception {
        Fixture fixture = new Fixture(100L);
        assertEquals(5L, fixture.available(shopSource(70L), 100L, 5L));
    }

    @Test
    void unrelatedAeCalculationsAreUnchangedAndDoNotQueryStorageAgain() throws Exception {
        Fixture fixture = new Fixture(100L);
        assertEquals(20L, fixture.available(IActionSource.empty(), 20L, 20L));
        assertEquals(0, fixture.queries.get());
    }

    @Test
    void unreservedKeysKeepTheNativeResultWithoutAnExtraQuery() throws Exception {
        Fixture fixture = new Fixture(100L);
        IActionSource source = new ShopAutoCraftActionSource(IActionSource.empty(), Map.of());
        assertEquals(20L, fixture.available(source, 20L, 20L));
        assertEquals(0, fixture.queries.get());
    }

    private static IActionSource shopSource(long reserved) {
        return new ShopAutoCraftActionSource(IActionSource.empty(), Map.of(KEY, reserved));
    }

    private static final class Fixture {
        final AtomicInteger queries = new AtomicInteger();
        final IStorageService storage;

        Fixture(long stored) {
            MEStorage inventory = new MEStorage() {
                @Override
                public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
                    assertSame(KEY, key);
                    assertEquals(Long.MAX_VALUE, amount);
                    assertEquals(Actionable.SIMULATE, mode);
                    queries.incrementAndGet();
                    return stored;
                }

                @Override
                public Component getDescription() { return Component.literal("shop test"); }
            };
            storage = (IStorageService) Proxy.newProxyInstance(
                    IStorageService.class.getClassLoader(), new Class<?>[]{IStorageService.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getInventory")) return inventory;
                        throw new AssertionError("Must not copy or modify the shared storage cache");
                    });
        }

        long available(IActionSource source, long requested, long nativeResult) throws Exception {
            Method limit = Arrays.stream(ShopCraftingSimulationInventoryMixin.class.getDeclaredMethods())
                    .filter(method -> method.getParameterCount() == 3
                            && method.getParameterTypes()[0] == AEKey.class
                            && method.getParameterTypes()[2] == CallbackInfoReturnable.class)
                    .findFirst().orElse(null);
            assertNotNull(limit, "Reservations must filter simulateExtractParent, not the unused native list");
            ShopCraftingSimulationInventoryMixin mixin = new ShopCraftingSimulationInventoryMixin() {};
            Method capture = ShopCraftingSimulationInventoryMixin.class.getDeclaredMethod(
                    "gtShanhai$reserveShopStock", IStorageService.class, IActionSource.class, CallbackInfo.class);
            capture.setAccessible(true);
            capture.invoke(mixin, storage, source, new CallbackInfo("<init>", false));
            limit.setAccessible(true);
            CallbackInfoReturnable<Long> result = new CallbackInfoReturnable<>(
                    "simulateExtractParent", true, nativeResult);
            limit.invoke(mixin, KEY, requested, result);
            return result.getReturnValue();
        }
    }

    private static final class TestKey extends AEKey {
        @Override public AEKeyType getType() { return null; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag() { return new CompoundTag(); }
        @Override public Object getPrimaryKey() { return this; }
        @Override public ResourceLocation getId() { return new ResourceLocation("gt_shanhai", "shop_test"); }
        @Override public void writeToPacket(FriendlyByteBuf buffer) {}
        @Override protected Component computeDisplayName() { return Component.literal("shop test"); }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {}
    }
}
