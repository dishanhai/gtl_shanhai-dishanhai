package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.crafting.pattern.ProcessingPatternItem;
import appeng.helpers.patternprovider.PatternContainer;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadMetadata;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 自合成仓：样板由本仓自己发配并完成 AE 合成。
 * 发配物退回本仓所在网络，不交给多方块配方消耗。
 */
public class SelfSynthesisChamberPartMachine extends MultiblockPartMachine
        implements IGridConnectedMachine, ICraftingProvider, PatternContainer {

    public static final ResourceLocation ID = new ResourceLocation(GTDishanhaiMod.MOD_ID, "self_synthesis_chamber");
    public static final int PATTERN_SLOTS = 36;

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            SelfSynthesisChamberPartMachine.class, MultiblockPartMachine.MANAGED_FIELD_HOLDER);

    @Persisted
    protected final GridNodeHolder nodeHolder;
    @Persisted
    protected final SinglePatternInventory patternInventory;
    @DescSynced
    protected boolean isOnline;

    private final IActionSource actionSource;
    private final TerminalPatternInventory terminalInventory = new TerminalPatternInventory();
    private final List<PendingInsert> pending = new ArrayList<>();
    private List<IPatternDetails> cachedPatterns = List.of();
    private TickableSubscription flushSubs;

    public SelfSynthesisChamberPartMachine(IMachineBlockEntity holder) {
        super(holder);
        this.nodeHolder = new GridNodeHolder(this);
        this.actionSource = IActionSource.ofMachine(this.nodeHolder.getMainNode()::getNode);
        this.patternInventory = new SinglePatternInventory(PATTERN_SLOTS);
        this.patternInventory.setFilter(stack -> stack.getItem() instanceof ProcessingPatternItem);
        this.patternInventory.setOnContentsChanged(this::onPatternsChanged);
        this.nodeHolder.getMainNode().addService(ICraftingProvider.class, this);
    }

    public static MachineDefinition register() {
        MachineDefinition def = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                ID.getPath(),
                MachineDefinition::createDefinition,
                SelfSynthesisChamberPartMachine::new,
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity)
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation("gtlcore", "block/casings/dimensionally_transcendent_casing"),
                        new ResourceLocation(GTDishanhaiMod.MOD_ID, "block/machine/part/self_synthesis_chamber")))
                .register();
        def.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(Component.literal("§6§l自合成仓"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7放入处理样板，由本仓自己完成 AE 合成"));
            tooltips.add(Component.literal("§bAE 仍要发配样板原料，配方不消耗这些发配物"));
            tooltips.add(Component.literal("§a发配物退回本仓所在 AE 网络，随后取消该合成任务"));
            tooltips.add(Component.literal("§e多方块结构页可快速编写到本仓，也可从 EAEP 样板上传选中"));
            tooltips.add(Component.literal("§d任何面接 ME 线缆均可连接，不需要多方块主机"));
        });
        return def;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
        onPatternsChanged();
        updateFlushSubscription();
    }

    @Override
    public void onUnload() {
        if (flushSubs != null) {
            flushSubs.unsubscribe();
            flushSubs = null;
        }
        super.onUnload();
    }

    @Override
    public void onRotated(Direction oldFacing, Direction newFacing) {
        super.onRotated(oldFacing, newFacing);
        getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
    }

    @Override
    public Widget createUIWidget() {
        WidgetGroup group = new WidgetGroup(0, 0, 176, 86);
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        for (int slot = 0; slot < PATTERN_SLOTS; slot++) {
            int col = slot % 9;
            int row = slot / 9;
            group.addWidget(new SlotWidget(patternInventory, slot, 7 + col * 18, 7 + row * 18)
                    .setBackground(new IGuiTexture[] {GuiTextures.SLOT, GuiTextures.PATTERN_OVERLAY}));
        }
        return group;
    }

    @Override
    public boolean isOnline() {
        return isOnline;
    }

    @Override
    public void setOnline(boolean online) {
        this.isOnline = online;
    }

    @Override
    public IManagedGridNode getMainNode() {
        return nodeHolder.getMainNode();
    }

    @Override
    @NotNull
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        if (!(getLevel() instanceof Level) || getLevel().isClientSide()) {
            return List.of();
        }
        if (cachedPatterns == null) {
            cachedPatterns = decodePatterns();
        }
        return cachedPatterns;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (getLevel() == null || getLevel().isClientSide() || patternDetails == null || isBusy()) {
            return false;
        }
        if (!ownsPattern(patternDetails) || !getMainNode().isActive()) {
            return false;
        }
        long times = SelfSynthesisPlan.craftTimes(perCraft(patternDetails), pushedTotals(inputHolder));
        if (times <= 0L) {
            return false;
        }
        List<PendingInsert> plan = buildPlan(inputHolder);
        if (!canAccept(plan)) {
            return false;
        }
        commit(plan);
        SelfSynthesisCraftCancel.mark();
        return true;
    }

    @Override
    public boolean isBusy() {
        return !pending.isEmpty();
    }

    @Override
    public @Nullable IGrid getGrid() {
        IGridNode node = getMainNode().getNode();
        return node == null ? null : node.getGrid();
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return terminalInventory;
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        return new PatternContainerGroup(
                AEItemKey.of(getDefinition().getItem()),
                Component.literal(SelfSynthesisUpload.DISPLAY_NAME),
                List.of(Component.translatable(getDefinition().getDescriptionId())));
    }

    public boolean hasEmptyPatternSlot() {
        for (int slot = 0; slot < patternInventory.getSlots(); slot++) {
            if (patternInventory.getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public List<String> storedRecipeTypeIds() {
        List<String> ids = new ArrayList<>();
        Level level = getLevel();
        if (level == null) {
            return ids;
        }
        for (int slot = 0; slot < patternInventory.getSlots(); slot++) {
            ItemStack stack = patternInventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String id = com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper.readRecipeTypeId(stack);
            if (id != null && !id.isBlank() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    public @Nullable PatternQuickUploadService.Target createUploadTarget(String recipeTypeId) {
        Level level = getLevel();
        if (level == null || !getMainNode().isActive() || !hasEmptyPatternSlot()) {
            return null;
        }
        String typeId = recipeTypeId == null || recipeTypeId.isBlank()
                ? SelfSynthesisUpload.RECIPE_TYPE_ID : recipeTypeId;
        ResourceLocation parsed = ResourceLocation.tryParse(typeId);
        if (parsed == null) {
            parsed = new ResourceLocation(GTDishanhaiMod.MOD_ID, "self_synthesis");
        }
        BlockPos pos = getPos().immutable();
        ResourceKey<Level> levelKey = level.dimension();
        return new PatternQuickUploadService.Target(
                levelKey,
                pos,
                getTerminalGroup().name(),
                parsed,
                PatternQuickUploadMetadata.recipeTypeName(parsed),
                getTerminalGroup().icon(),
                ID,
                List.of(pos));
    }

    @Override
    public void saveCustomPersistedData(@NotNull CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        ListTag list = new ListTag();
        for (PendingInsert insert : pending) {
            if (insert.key == null || insert.amount <= 0L) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.put("key", insert.key.toTagGeneric());
            entry.putLong("amount", insert.amount);
            list.add(entry);
        }
        tag.put("SelfSynthesisPending", list);
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        pending.clear();
        ListTag list = tag.getList("SelfSynthesisPending", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            AEKey key = AEKey.fromTagGeneric(entry.getCompound("key"));
            long amount = entry.getLong("amount");
            if (key != null && amount > 0L) {
                pending.add(new PendingInsert(key, amount));
            }
        }
    }

    private void onPatternsChanged() {
        cachedPatterns = null;
        if (getLevel() instanceof net.minecraft.server.level.ServerLevel && getMainNode().getNode() != null) {
            ICraftingProvider.requestUpdate(getMainNode());
        }
    }

    private List<IPatternDetails> decodePatterns() {
        List<IPatternDetails> decoded = new ArrayList<>();
        Level level = getLevel();
        if (level == null) {
            return List.of();
        }
        for (int slot = 0; slot < patternInventory.getSlots(); slot++) {
            ItemStack stack = patternInventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            try {
                IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level);
                if (details != null) {
                    decoded.add(details);
                }
            } catch (RuntimeException exception) {
                GTDishanhaiMod.LOGGER.debug("[自合成仓] 样板解码失败 slot={}", slot, exception);
            }
        }
        return List.copyOf(decoded);
    }

    private boolean ownsPattern(IPatternDetails details) {
        AEItemKey definition = details.getDefinition();
        if (definition == null) {
            return false;
        }
        for (IPatternDetails known : getAvailablePatterns()) {
            if (definition.equals(known.getDefinition())) {
                return true;
            }
        }
        return false;
    }

    private static long[] perCraft(IPatternDetails details) {
        IPatternDetails.IInput[] inputs = details.getInputs();
        long[] amounts = new long[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            amounts[i] = inputs[i].getMultiplier();
        }
        return amounts;
    }

    private static long[] pushedTotals(KeyCounter[] inputHolder) {
        if (inputHolder == null) {
            return new long[0];
        }
        long[] totals = new long[inputHolder.length];
        for (int i = 0; i < inputHolder.length; i++) {
            totals[i] = sum(inputHolder[i]);
        }
        return totals;
    }

    private static long sum(KeyCounter counter) {
        if (counter == null) {
            return 0L;
        }
        long total = 0L;
        for (var entry : counter) {
            total += entry.getLongValue();
        }
        return total;
    }

    private static List<PendingInsert> buildPlan(KeyCounter[] inputHolder) {
        List<PendingInsert> plan = new ArrayList<>();
        if (inputHolder == null) {
            return plan;
        }
        for (KeyCounter counter : inputHolder) {
            if (counter == null) {
                continue;
            }
            for (var entry : counter) {
                AEKey key = entry.getKey();
                long amount = entry.getLongValue();
                if (key != null && amount > 0L) {
                    plan.add(new PendingInsert(key, amount));
                }
            }
        }
        return plan;
    }

    private boolean canAccept(List<PendingInsert> plan) {
        IGrid grid = getGrid();
        if (grid == null || grid.getStorageService() == null) {
            return false;
        }
        MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) {
            return false;
        }
        for (PendingInsert insert : plan) {
            long accepted = storage.insert(insert.key, insert.amount, Actionable.SIMULATE, actionSource);
            if (accepted < insert.amount) {
                return false;
            }
        }
        return true;
    }

    private void commit(List<PendingInsert> plan) {
        pending.addAll(plan);
        updateFlushSubscription();
    }

    private void flushPending() {
        IGrid grid = getGrid();
        if (grid == null || grid.getStorageService() == null || grid.getEnergyService() == null) {
            updateFlushSubscription();
            return;
        }
        MEStorage storage = grid.getStorageService().getInventory();
        IEnergyService energy = grid.getEnergyService();
        if (storage == null) {
            updateFlushSubscription();
            return;
        }
        int operations = 0;
        var iterator = pending.iterator();
        while (iterator.hasNext() && operations < 64) {
            PendingInsert insert = iterator.next();
            if (insert.amount <= 0L || insert.key == null) {
                iterator.remove();
                continue;
            }
            long inserted = StorageHelper.poweredInsert(energy, storage, insert.key, insert.amount, actionSource);
            operations++;
            if (inserted <= 0L) {
                break;
            }
            insert.amount -= inserted;
            if (insert.amount <= 0L) {
                iterator.remove();
            }
        }
        updateFlushSubscription();
    }

    private void updateFlushSubscription() {
        if (!pending.isEmpty() && getLevel() != null && !getLevel().isClientSide()) {
            flushSubs = subscribeServerTick(flushSubs, this::flushPending);
        } else if (flushSubs != null) {
            flushSubs.unsubscribe();
            flushSubs = null;
        }
    }

    private final class TerminalPatternInventory implements InternalInventory {
        @Override
        public int size() {
            return patternInventory.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return patternInventory.getStackInSlot(slot);
        }

        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            patternInventory.setStackInSlot(slot, stack);
            patternInventory.onContentsChanged(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return patternInventory.isItemValid(slot, stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    }

    public static final class SinglePatternInventory extends ItemStackTransfer {
        public SinglePatternInventory() {
            this(PATTERN_SLOTS);
        }

        public SinglePatternInventory(int size) {
            super(size);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    }

    private static final class PendingInsert {
        private final AEKey key;
        private long amount;

        private PendingInsert(AEKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }
    }
}
