package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：重現既有星律 proxy.p=-1 的身分缺口，綁定只修改未持有身分的節點。 */
class StellarCloudAEIdentityTest {
    private static boolean bind(IManagedGridNode managed, int playerId) throws Exception {
        Class<?> type = assertDoesNotThrow(() -> Class.forName(
                "com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudAEIdentity"),
                "既有星律需要從明確的伺服端玩家操作補綁 AE 身分");
        return (boolean) type.getMethod("bindUnowned", IManagedGridNode.class, int.class)
                .invoke(null, managed, playerId);
    }

    private static IManagedGridNode managed(AtomicInteger owner, AtomicInteger writes, boolean ready) {
        IGridNode node = (IGridNode) Proxy.newProxyInstance(IGridNode.class.getClassLoader(),
                new Class<?>[]{IGridNode.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getOwningPlayerId")) return owner.get();
                    throw new AssertionError(method.getName());
                });
        return (IManagedGridNode) Proxy.newProxyInstance(IManagedGridNode.class.getClassLoader(),
                new Class<?>[]{IManagedGridNode.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getNode")) return ready ? node : null;
                    if (method.getName().equals("setOwningPlayerId")) {
                        owner.set((int) args[0]); writes.incrementAndGet(); return null;
                    }
                    throw new AssertionError(method.getName());
                });
    }

    @Test void unownedLegacyNodeReceivesTheInteractingPlayersAeIdentity() throws Exception {
        var owner = new AtomicInteger(-1); var writes = new AtomicInteger();
        var managed = managed(owner, writes, true);
        assertTrue(bind(managed, 0));
        assertEquals(0, owner.get());
        assertFalse(bind(managed, 0));
        assertEquals(1, writes.get());
    }

    @Test void anExistingIdentityIsNeverReplacedByAnotherUiUser() throws Exception {
        var owner = new AtomicInteger(4); var writes = new AtomicInteger();
        assertFalse(bind(managed(owner, writes, true), 0));
        assertEquals(4, owner.get()); assertEquals(0, writes.get());
    }

    @Test void missingNodeAndInvalidPlayerCannotClaimAnIdentity() throws Exception {
        var owner = new AtomicInteger(-1); var writes = new AtomicInteger();
        assertFalse(bind(managed(owner, writes, false), 0));
        assertFalse(bind(managed(owner, writes, true), -1));
        assertEquals(-1, owner.get()); assertEquals(0, writes.get());
    }

    @Test void placementAndCloudToggleUseTheServerPlayersIdentity() throws Exception {
        Path root = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part");
        String machine = Files.readString(root.resolve("RecipeTypePatternBufferPartMachine.java"));
        assertTrue(machine.contains("onMachinePlaced(LivingEntity placer, ItemStack stack)"));
        assertTrue(machine.contains("getMainNode().setOwningPlayer(player)"));
        assertTrue(machine.contains("StellarCloudAEIdentity.bindUnowned(getMainNode(), IPlayerRegistry.getPlayerId(server))"));
        String ui = Files.readString(root.resolve("cloud/StellarCloudConfiguratorUi.java"));
        assertTrue(ui.contains("BiFunction<Player, Boolean, String> setEnabled"));
        assertTrue(ui.contains("setEnabled.apply(root.getGui().entityPlayer, value)"));
        assertTrue(ui.contains("!click.isRemote && click.button == 0"));
        assertTrue(machine.contains("menu.getModularUI().holder != this"));
        assertTrue(machine.contains("cloudEnabled ? cloudUnavailableStatus()"));
        assertTrue(machine.contains("node.getOwningPlayerProfileId() == null"));
    }
}
