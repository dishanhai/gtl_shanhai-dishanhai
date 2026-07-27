package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.PatternDetailsHelper;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import org.gtlcore.gtlcore.common.item.PatternBoxBehavior;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MECraftPatternContainerPartMachine;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 高级样板包装箱：GTLCore 样板包装箱（{@code gtlcore:pattern_box}）的超集。
 *
 * <p>相对基础版的两点扩展：</p>
 * <ul>
 * <li>容量由配置决定（{@code advanced_pattern_box} 段），不再是写死的 72 格；</li>
 * <li>支持 ME 样板核心（{@code gtceu:me_craft_pattern_container}）——它只实现 GTLCore 自己的
 * {@link org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftPatternContainer}，不实现 AE2 的
 * {@code PatternContainer}，所以基础包装箱看不见它。</li>
 * </ul>
 *
 * <p>AE 样板供应器 / ME 样板总成 / ExtendedAE 装配矩阵这些基础版已覆盖的目标，直接委托给
 * {@link PatternBoxBehavior#handleBlockUse}，不重复实现一遍解析链；NBT 也刻意沿用同一个
 * {@code PatternInv} 键与 {@link ItemStackTransfer} 序列化格式，两种箱子的存档互通。</p>
 */
public final class AdvancedPatternBoxBehavior implements IItemUIFactory, IAddInformation {

    public static final AdvancedPatternBoxBehavior INSTANCE = new AdvancedPatternBoxBehavior();

    /** 与 GTLCore 样板包装箱同键：两种箱子的样板存档互通。 */
    private static final String INV_TAG = "PatternInv";
    /** {@link ItemStackTransfer#serializeNBT()} 写入的槽位数键。 */
    private static final String SIZE_KEY = "Size";

    private static final int SLOT_SIZE = 18;
    private static final int LEFT = 7;
    private static final int PATTERN_TOP = 42;
    /** 玩家背包宽度（9 格），GUI 至少要这么宽。 */
    private static final int MIN_WIDTH = 176;
    private static final int PLAYER_INV_WIDTH = 162;
    private static final int PAGE_BUTTON_Y = 24;
    private static final int PAGE_BUTTON_SIZE = 16;

    private AdvancedPatternBoxBehavior() {}

    // ------------------------------------------------------------------ 容量 / 存取

    /** 配置声明的容量（每行 × 每页行数 × 页数）。 */
    public static int configuredSlotCount() {
        DShanhaiConfig.ConfigValues common = DShanhaiConfig.COMMON;
        return patternsPerRow() * rowsPerPage() * Math.max(1, common.advancedPatternBoxMaxPages.get());
    }

    private static int patternsPerRow() {
        return Math.max(1, DShanhaiConfig.COMMON.advancedPatternBoxPatternsPerRow.get());
    }

    private static int rowsPerPage() {
        return Math.max(1, DShanhaiConfig.COMMON.advancedPatternBoxRowsPerPage.get());
    }

    /**
     * 读出箱内库存。槽位数取「配置容量」和「存档里最后一个非空槽 + 1」的较大值——
     * 配置调大立刻扩容，调小也不会把高位槽里的样板吞掉。
     */
    public static ItemStackTransfer getInventory(ItemStack box) {
        int configured = configuredSlotCount();
        CompoundTag tag = box.getTag();
        if (tag == null || !tag.contains(INV_TAG)) {
            return createTransfer(configured);
        }

        // ItemStackTransfer.deserializeNBT 会按存档里的 Size 自行 setSize，这里的初值只是占位。
        ItemStackTransfer stored = new ItemStackTransfer(configured);
        stored.deserializeNBT(tag.getCompound(INV_TAG));

        int size = Math.max(configured, lastOccupiedSlot(stored) + 1);
        if (size == stored.getSlots()) {
            applyPatternFilter(stored);
            return stored;
        }
        // setSize 会直接换掉整个 NonNullList（清空全部内容），只能新建再逐格搬。
        ItemStackTransfer resized = createTransfer(size);
        int copy = Math.min(size, stored.getSlots());
        for (int slot = 0; slot < copy; slot++) {
            resized.setStackInSlot(slot, stored.getStackInSlot(slot));
        }
        return resized;
    }

    public static void saveInventory(ItemStack box, ItemStackTransfer inventory) {
        box.getOrCreateTag().put(INV_TAG, inventory.serializeNBT());
    }

    /**
     * 把当前容量写回 NBT。委托给 GTLCore 之前必须做一次：基础版 {@code getInventory} 对无 NBT 的
     * 箱子按 72 格建表，不先归一化的话新箱子会被它按 72 格存回去。
     *
     * <p>存档尺寸已经对得上就直接返回——右键任意方块都会走一次这里，不能每次都重写 NBT
     * 触发无谓的物品同步。</p>
     */
    private static void normalizeInventoryTag(ItemStack box) {
        int savedSize = readSavedSlotCount(box);
        if (savedSize == configuredSlotCount()) return;
        ItemStackTransfer inventory = getInventory(box);
        if (savedSize == inventory.getSlots()) return;
        saveInventory(box, inventory);
    }

    private static int readSavedSlotCount(ItemStack box) {
        CompoundTag tag = box.getTag();
        if (tag == null || !tag.contains(INV_TAG)) return -1;
        CompoundTag saved = tag.getCompound(INV_TAG);
        return saved.contains(SIZE_KEY) ? saved.getInt(SIZE_KEY) : -1;
    }

    private static ItemStackTransfer createTransfer(int size) {
        ItemStackTransfer transfer = new ItemStackTransfer(size);
        applyPatternFilter(transfer);
        return transfer;
    }

    private static void applyPatternFilter(ItemStackTransfer transfer) {
        transfer.setFilter(stack -> stack.isEmpty() || PatternDetailsHelper.isEncodedPattern(stack));
    }

    private static int lastOccupiedSlot(ItemStackTransfer transfer) {
        for (int slot = transfer.getSlots() - 1; slot >= 0; slot--) {
            if (!transfer.getStackInSlot(slot).isEmpty()) return slot;
        }
        return -1;
    }

    // ------------------------------------------------------------------ GUI

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        ItemStack box = holder.getHeld();
        ItemStackTransfer inventory = getInventory(box);
        // 先把 Size 落进 NBT：GUI 布局按 inventory.getSlots() 算，两端读的是同一份物品 NBT。
        if (readSavedSlotCount(box) != inventory.getSlots()) {
            saveInventory(box, inventory);
        }
        inventory.setOnContentsChanged(() -> {
            saveInventory(box, inventory);
            holder.markAsDirty();
        });

        int perRow = patternsPerRow();
        int rows = rowsPerPage();
        int total = inventory.getSlots();
        int perPage = perRow * rows;
        int pages = Math.max(1, ((total + perPage) - 1) / perPage);

        int width = Math.max(MIN_WIDTH, (LEFT * 2) + (perRow * SLOT_SIZE));
        int patternHeight = rows * SLOT_SIZE;
        int invTop = PATTERN_TOP + patternHeight + 8;
        int hotbarTop = invTop + (3 * SLOT_SIZE) + 4;
        int height = hotbarTop + SLOT_SIZE + 6;

        WidgetGroup group = new WidgetGroup(0, 0, width, height);
        group.addWidget(new LabelWidget(LEFT, 6,
                () -> Component.translatable("item.gt_shanhai.advanced_pattern_box").getString()));

        int[] page = { 0 };
        WidgetGroup patternPage = new WidgetGroup(0, 0, width, PATTERN_TOP + patternHeight);
        rebuildPatternPage(patternPage, inventory, page, perRow, perPage, pages, width);
        group.addWidget(patternPage);

        Inventory playerInv = player.getInventory();
        int playerLeft = (width - PLAYER_INV_WIDTH) / 2;
        int heldSlot = holder.getHand() == InteractionHand.MAIN_HAND ? playerInv.selected : -1;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + ((row + 1) * 9);
                group.addWidget(new SlotWidget(playerInv, index,
                        playerLeft + (col * SLOT_SIZE), invTop + (row * SLOT_SIZE), true, true)
                                .setBackgroundTexture(GuiTextures.SLOT));
            }
        }
        for (int col = 0; col < 9; col++) {
            boolean locked = col == heldSlot;
            group.addWidget(new SlotWidget(playerInv, col,
                    playerLeft + (col * SLOT_SIZE), hotbarTop, !locked, !locked)
                            .setBackgroundTexture(GuiTextures.SLOT));
        }
        return new ModularUI(width, height, holder, player).widget(group).background(GuiTextures.BACKGROUND);
    }

    private static void rebuildPatternPage(WidgetGroup patternPage, ItemStackTransfer inventory,
                                           int[] page, int perRow, int perPage, int pages, int width) {
        patternPage.clearAllWidgets();
        int startSlot = page[0] * perPage;
        int endSlot = Math.min(startSlot + perPage, inventory.getSlots());
        int slotsLeft = (width - (perRow * SLOT_SIZE)) / 2;
        for (int slot = startSlot; slot < endSlot; slot++) {
            int slotInPage = slot - startSlot;
            int row = slotInPage / perRow;
            int col = slotInPage % perRow;
            patternPage.addWidget(new SlotWidget(inventory, slot,
                    slotsLeft + (col * SLOT_SIZE), PATTERN_TOP + (row * SLOT_SIZE), true, true)
                            .setBackgroundTexture(GuiTextures.SLOT)
                            .setIngredientIO(IngredientIO.INPUT));
        }
        patternPage.addWidget(new ButtonWidget(width - 40, PAGE_BUTTON_Y, PAGE_BUTTON_SIZE, PAGE_BUTTON_SIZE,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("<<")), clickData -> {
                    if (page[0] > 0) {
                        page[0] = page[0] - 1;
                        rebuildPatternPage(patternPage, inventory, page, perRow, perPage, pages, width);
                    }
                }));
        patternPage.addWidget(new LabelWidget(width - 72, 29, (page[0] + 1) + " / " + pages));
        patternPage.addWidget(new ButtonWidget(width - 22, PAGE_BUTTON_Y, PAGE_BUTTON_SIZE, PAGE_BUTTON_SIZE,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(">>")), clickData -> {
                    if (page[0] < pages - 1) {
                        page[0] = page[0] + 1;
                        rebuildPatternPage(patternPage, inventory, page, perRow, perPage, pages, width);
                    }
                }));
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public InteractionResultHolder<ItemStack> use(Item item, Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        if (player.isShiftKeyDown()) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            HeldItemUIFactory.INSTANCE.openUI(serverPlayer, usedHand);
        }
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
    }

    @Override
    public boolean sneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        return true;
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack itemStack, UseOnContext context) {
        InteractionResult result = handleBlockUse(itemStack, context);
        return result == InteractionResult.PASS
                ? InteractionResult.PASS
                : InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return handleBlockUse(context.getItemInHand(), context);
    }

    public static InteractionResult handleBlockUse(ItemStack box, UseOnContext context) {
        MECraftPatternContainerPartMachine core = resolveCraftPatternCore(context);
        if (core == null) {
            // 其余目标（AE 样板供应器 / ME 样板总成 / 装配矩阵…）交给 GTLCore 原版解析链。
            if (context.getPlayer() instanceof ServerPlayer) {
                normalizeInventoryTag(box);
            }
            return PatternBoxBehavior.handleBlockUse(box, context);
        }
        if (!(context.getPlayer() instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        ItemStackTransfer boxInv = getInventory(box);
        ItemStackTransfer coreInv = core.getPatternInventory();
        int moved;
        String messageKey;
        if (serverPlayer.isShiftKeyDown()) {
            moved = moveBoxToCore(boxInv, coreInv);
            messageKey = moved > 0
                    ? "message.gt_shanhai.advanced_pattern_box.core_inserted"
                    : "message.gt_shanhai.advanced_pattern_box.core_insert_failed";
        } else {
            moved = moveCoreToBox(coreInv, boxInv);
            messageKey = moved > 0
                    ? "message.gt_shanhai.advanced_pattern_box.core_extracted"
                    : "message.gt_shanhai.advanced_pattern_box.core_extract_failed";
        }
        if (moved > 0) {
            saveInventory(box, boxInv);
            core.markDirty();
        }
        serverPlayer.displayClientMessage(Component.translatable(messageKey, moved), true);
        return InteractionResult.SUCCESS;
    }

    /** 只认「直接点在 ME 样板核心方块上」；点控制器/其他部件仍走基础包装箱的解析链。 */
    @Nullable
    private static MECraftPatternContainerPartMachine resolveCraftPatternCore(UseOnContext context) {
        MetaMachine machine = MetaMachine.getMachine(context.getLevel(), context.getClickedPos());
        return machine instanceof MECraftPatternContainerPartMachine core ? core : null;
    }

    private static int moveCoreToBox(ItemStackTransfer coreInv, ItemStackTransfer boxInv) {
        int moved = 0;
        for (int slot = 0; slot < coreInv.getSlots(); slot++) {
            ItemStack stack = coreInv.getStackInSlot(slot);
            if (stack.isEmpty() || !PatternDetailsHelper.isEncodedPattern(stack)) continue;
            int target = findEmptySlot(boxInv);
            if (target < 0) break;
            boxInv.setStackInSlot(target, stack.copy());
            coreInv.setStackInSlot(slot, ItemStack.EMPTY);
            moved++;
        }
        return moved;
    }

    private static int moveBoxToCore(ItemStackTransfer boxInv, ItemStackTransfer coreInv) {
        int moved = 0;
        for (int slot = 0; slot < boxInv.getSlots(); slot++) {
            ItemStack stack = boxInv.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            // 样板核心只收分子装配机能跑的样板（AEUtils.molecularFilter），加工样板会被 isItemValid 挡下。
            int target = findInsertableSlot(coreInv, stack);
            if (target < 0) continue;
            coreInv.setStackInSlot(target, stack.copy());
            boxInv.setStackInSlot(slot, ItemStack.EMPTY);
            moved++;
        }
        return moved;
    }

    private static int findEmptySlot(ItemStackTransfer transfer) {
        for (int slot = 0; slot < transfer.getSlots(); slot++) {
            if (transfer.getStackInSlot(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private static int findInsertableSlot(ItemStackTransfer transfer, ItemStack stack) {
        for (int slot = 0; slot < transfer.getSlots(); slot++) {
            if (transfer.getStackInSlot(slot).isEmpty() && transfer.isItemValid(slot, stack)) return slot;
        }
        return -1;
    }

    // ------------------------------------------------------------------ tooltip

    @Override
    public void appendHoverText(ItemStack itemStack, @Nullable Level level,
                                List<Component> list, TooltipFlag tooltipFlag) {
        int stored = 0;
        int capacity = configuredSlotCount();
        CompoundTag tag = itemStack.getTag();
        if (tag != null && tag.contains(INV_TAG)) {
            ItemStackTransfer inventory = getInventory(itemStack);
            capacity = inventory.getSlots();
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                if (!inventory.getStackInSlot(slot).isEmpty()) stored++;
            }
        }
        list.add(Component.translatable("tooltip.gt_shanhai.advanced_pattern_box.open", stored, capacity));
        list.add(Component.translatable("tooltip.gt_shanhai.advanced_pattern_box.extract"));
        list.add(Component.translatable("tooltip.gt_shanhai.advanced_pattern_box.insert"));
        list.add(Component.translatable("tooltip.gt_shanhai.advanced_pattern_box.core"));
    }
}
