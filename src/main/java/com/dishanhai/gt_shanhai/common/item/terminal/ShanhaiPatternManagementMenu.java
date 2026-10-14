package com.dishanhai.gt_shanhai.common.item.terminal;

import appeng.api.storage.ITerminalHost;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.menu.SlotSemantics;
import appeng.menu.ToolboxMenu;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.PatternAccessTermMenu;
import appeng.menu.slot.RestrictedInputSlot;
import com.glodblock.github.extendedae.container.ContainerExPatternTerminal;
import de.mari_023.ae2wtlib.AE2wtlibSlotSemantics;
import de.mari_023.ae2wtlib.terminal.WTMenuHost;
import de.mari_023.ae2wtlib.wut.ItemWUT;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShanhaiPatternContainerMetadataPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiPatternStellarDetailPacket;
import appeng.helpers.patternprovider.PatternContainer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

public final class ShanhaiPatternManagementMenu extends ContainerExPatternTerminal {

    private static final Field BY_ID_FIELD = findField(PatternAccessTermMenu.class, "byId");
    private static final Field TRACKER_CONTAINER_FIELD = findTrackerContainerField();

    public static final MenuType<ShanhaiPatternManagementMenu> TYPE = MenuTypeBuilder
            .create(ShanhaiPatternManagementMenu::new, ShanhaiPatternManagementMenuHost.class)
            .build("wireless_pattern_management_terminal");

    private final ShanhaiPatternManagementMenuHost host;
    private final ToolboxMenu toolboxMenu;
    private final Set<Long> stellarContainerIds = new HashSet<>();
    private final Map<Long, StellarTerminalDetail> stellarDetails = new HashMap<>();
    private Set<Long> lastSentStellarContainerIds = Set.of();
    private int lastStellarDetailHash = Integer.MIN_VALUE;

    public ShanhaiPatternManagementMenu(int id, Inventory inventory, ShanhaiPatternManagementMenuHost host) {
        super(TYPE, id, inventory, host, true);
        this.host = host;
        this.toolboxMenu = new ToolboxMenu(this);
        IUpgradeInventory upgrades = host.getUpgrades();
        for (int i = 0; i < upgrades.size(); i++) {
            RestrictedInputSlot slot = new RestrictedInputSlot(
                    RestrictedInputSlot.PlacableItemType.UPGRADES, upgrades, i);
            slot.setNotDraggable();
            addSlot(slot, SlotSemantics.UPGRADE);
        }
        addSlot(new RestrictedInputSlot(
                RestrictedInputSlot.PlacableItemType.QE_SINGULARITY,
                host.getSubInventory(WTMenuHost.INV_SINGULARITY), 0),
                AE2wtlibSlotSemantics.SINGULARITY);
    }

    @Override
    public void broadcastChanges() {
        toolboxMenu.tick();
        super.broadcastChanges();
        if (!isClientSide()) {
            syncStellarContainerMetadata();
        }
    }

    public boolean isWUT() {
        return host.getItemStack().getItem() instanceof ItemWUT;
    }

    public ITerminalHost getTerminalHost() {
        return host;
    }

    public ShanhaiPatternManagementMenuHost getWirelessHost() {
        return host;
    }

    public ToolboxMenu getToolbox() {
        return toolboxMenu;
    }

    public boolean isStellarContainer(long serverId) {
        return stellarContainerIds.contains(serverId);
    }

    public void replaceStellarContainerIds(Set<Long> serverIds) {
        stellarContainerIds.clear();
        stellarContainerIds.addAll(serverIds);
    }

    public StellarTerminalDetail stellarDetail(long serverId) {
        return stellarDetails.get(serverId);
    }

    public void replaceStellarDetails(List<StellarTerminalDetail> details) {
        stellarDetails.clear();
        for (StellarTerminalDetail detail : details) {
            stellarDetails.put(detail.serverId(), detail);
        }
    }

    public RecipeTypePatternBufferPartMachine resolveStellarContainer(long serverId) {
        PatternContainer container = resolveTrackedContainer(serverId);
        if (!(container instanceof RecipeTypePatternBufferPartMachine stellar)) {
            return null;
        }
        IGrid currentGrid = getCurrentGrid();
        if (currentGrid == null || stellar.getGrid() != currentGrid) {
            return null;
        }
        return stellar.resolveRemotePatternOwner();
    }

    public RecipeTypePatternBufferPartMachine resolveStellarContainer(ServerLevel level, BlockPos pos) {
        IGrid currentGrid = getCurrentGrid();
        if (currentGrid == null) return null;
        for (Long2ObjectMap.Entry<?> entry : trackedContainers().long2ObjectEntrySet()) {
            Object tracker = entry.getValue();
            if (tracker == null) continue;
            if (!(readContainer(tracker) instanceof RecipeTypePatternBufferPartMachine stellar)) continue;
            RecipeTypePatternBufferPartMachine owner = stellar.resolveRemotePatternOwner();
            if (owner.getLevel() == level && owner.getPos().equals(pos) && owner.getGrid() == currentGrid) {
                return owner;
            }
        }
        return null;
    }

    private void syncStellarContainerMetadata() {
        Set<Long> current = new HashSet<>();
        List<StellarTerminalDetail> details = new ArrayList<>();
        int detailHash = 1;
        for (Long2ObjectMap.Entry<?> entry : trackedContainers().long2ObjectEntrySet()) {
            Object tracker = entry.getValue();
            if (tracker == null) continue;
            if (!(readContainer(tracker) instanceof RecipeTypePatternBufferPartMachine stellar)) continue;
            current.add(entry.getLongKey());
            StellarTerminalDetail detail = stellar.terminalDetail(entry.getLongKey());
            details.add(detail);
            detailHash = 31 * detailHash + detail.hashCode();
        }
        stellarContainerIds.clear();
        stellarContainerIds.addAll(current);
        net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) getPlayer();
        if (!current.equals(lastSentStellarContainerIds)) {
            lastSentStellarContainerIds = Set.copyOf(current);
            ShanhaiNetwork.CHANNEL.sendTo(
                    new ShanhaiPatternContainerMetadataPacket(containerId, current),
                    player.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
        }
        if (detailHash != lastStellarDetailHash) {
            lastStellarDetailHash = detailHash;
            ShanhaiNetwork.CHANNEL.sendTo(
                    new ShanhaiPatternStellarDetailPacket(containerId, details),
                    player.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    private PatternContainer resolveTrackedContainer(long serverId) {
        Object tracker = trackedContainers().get(serverId);
        return tracker == null ? null : readContainer(tracker);
    }

    @SuppressWarnings("unchecked")
    private Long2ObjectMap<Object> trackedContainers() {
        try {
            return (Long2ObjectMap<Object>) BY_ID_FIELD.get(this);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to read AE2 pattern terminal trackers", e);
        }
    }

    private static PatternContainer readContainer(Object tracker) {
        try {
            return (PatternContainer) TRACKER_CONTAINER_FIELD.get(tracker);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to read AE2 pattern terminal container", e);
        }
    }

    private IGrid getCurrentGrid() {
        IActionHost actionHost = getActionHost();
        IGridNode node = actionHost == null ? null : actionHost.getActionableNode();
        return node != null && node.isActive() ? node.getGrid() : null;
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Field findTrackerContainerField() {
        for (Class<?> nested : PatternAccessTermMenu.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("ContainerTracker")) {
                return findField(nested, "container");
            }
        }
        throw new ExceptionInInitializerError("AE2 ContainerTracker class not found");
    }
}
