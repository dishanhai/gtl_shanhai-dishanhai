package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.PatternDetailsHelper;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorScale;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 星律通配样板：右键打开后分两组。
 * 自动生成只展示生成器底稿；手动更改用整页格子放入编码样板，匹配配方 ID。
 * 方块自己要打开界面时仍优先，例如右键机器不会被这张样板抢走。
 */
public final class SelectedRecipePatternBehavior implements IItemUIFactory, IAddInformation {
    public static final SelectedRecipePatternBehavior INSTANCE = new SelectedRecipePatternBehavior();

    private static final String IMPORT_TAG = "gt_shanhai_selected_recipe_imports";
    private static final int SLOT = 18;
    private static final int LEFT = 8;
    private static final int COLUMNS = 14;
    private static final int MANUAL_ROWS = 5;
    /** 手动组占满预览区，不再和 100 多张自动配方挤同一排。旧存档不足此格数时只读已有格。 */
    private static final int IMPORT_SLOTS = COLUMNS * MANUAL_ROWS;
    private static final int VISIBLE_ROWS = 5;
    private static final int SCROLL = 4;
    private static final int GROUP_Y = 42;
    private static final int GROUP_H = 14;
    private static final int PREVIEW_TOP = GROUP_Y + GROUP_H + 4;
    private static final int PREVIEW_WIDTH = COLUMNS * SLOT + SCROLL;
    private static final int PREVIEW_HEIGHT = VISIBLE_ROWS * SLOT;
    private static final int WIDTH = LEFT + PREVIEW_WIDTH + 8;
    private static final int INV_COLUMNS = 9;
    private static final int INV_TOP = PREVIEW_TOP + PREVIEW_HEIGHT + 6;
    private static final int HOTBAR_TOP = INV_TOP + (3 * SLOT) + 4;
    private static final int HEIGHT = HOTBAR_TOP + SLOT + 6;
    private static final int ROW_LEFT = (WIDTH - (INV_COLUMNS * SLOT)) / 2;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_W = (PREVIEW_WIDTH - BUTTON_GAP) / 2;

    private SelectedRecipePatternBehavior() {}

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        ItemStack stack = holder.getHeld();
        ItemStackTransfer imports = loadImports(stack);
        int[] unmatched = { 0 };
        boolean[] showManual = { preferManual(stack, imports) };
        WidgetGroup previewHost = new WidgetGroup(0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT);
        WidgetGroup generatedPage = new WidgetGroup(LEFT, PREVIEW_TOP, PREVIEW_WIDTH, PREVIEW_HEIGHT);
        WidgetGroup manualPage = manualPage(imports);
        generatedPage.addWidget(previewHost);
        if (!player.level().isClientSide() && needsSync(stack, imports)) {
            syncImports(stack, imports, player.level(), unmatched);
        }
        fillPreview(previewHost, stack, player.level());
        imports.setOnContentsChanged(() -> {
            if (player.level().isClientSide()) return;
            ItemStack held = holder.getHeld();
            saveImports(held, imports);
            try {
                syncImports(held, imports, player.level(), unmatched);
                fillPreview(previewHost, held, player.level());
            } catch (RuntimeException ignored) {
                // 匹配失败时样板仍留在放入格，下次打开再试。
            }
            holder.markAsDirty();
        });
        generatedPage.setVisible(!showManual[0]);
        manualPage.setVisible(showManual[0]);

        WidgetGroup group = new WidgetGroup(0, 0, WIDTH, HEIGHT);
        group.addWidget(new LabelWidget(LEFT, 6,
                () -> Component.translatable("item.gt_shanhai.selected_recipe_pattern").getString()));
        group.addWidget(new LabelWidget(LEFT, 18, () -> summary(holder.getHeld())));
        group.addWidget(new LabelWidget(LEFT, 30, () -> importStatus(holder.getHeld(), unmatched[0])));
        group.addWidget(groupButton(LEFT, false, showManual, generatedPage, manualPage));
        group.addWidget(groupButton(LEFT + BUTTON_W + BUTTON_GAP, true, showManual, generatedPage, manualPage));
        group.addWidget(generatedPage);
        group.addWidget(manualPage);
        addPlayerInventory(group, holder, player);
        return new ModularUI(WIDTH, HEIGHT, holder, player).widget(group).background(GuiTextures.BACKGROUND);
    }

    /** 两组共用同一块区域。点哪组就显示哪组，格子不会叠在一起抢位置。 */
    private static ButtonWidget groupButton(int x, boolean manual, boolean[] showManual,
            WidgetGroup generatedPage, WidgetGroup manualPage) {
        String key = manual
                ? "gui.gt_shanhai.selected_recipe_pattern.group_manual"
                : "gui.gt_shanhai.selected_recipe_pattern.group_generated";
        String hint = manual
                ? "gui.gt_shanhai.selected_recipe_pattern.group_manual_hint"
                : "gui.gt_shanhai.selected_recipe_pattern.group_generated_hint";
        ButtonWidget button = new ButtonWidget(x, GROUP_Y, BUTTON_W, GROUP_H,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(() -> {
                    String label = Component.translatable(key).getString();
                    return showManual[0] == manual ? label : "§7" + label;
                }).setDropShadow(true)),
                click -> {
                    showManual[0] = manual;
                    generatedPage.setVisible(!manual);
                    manualPage.setVisible(manual);
                });
        button.setHoverBorderTexture(1, 0xFFFFFFFF);
        button.setHoverTooltips(Component.translatable(hint));
        return button;
    }

    private static WidgetGroup manualPage(ItemStackTransfer imports) {
        WidgetGroup page = new WidgetGroup(LEFT, PREVIEW_TOP, PREVIEW_WIDTH, PREVIEW_HEIGHT);
        page.setBackground(GuiTextures.DISPLAY);
        for (int index = 0; index < IMPORT_SLOTS; index++) {
            int x = (index % COLUMNS) * SLOT;
            int y = (index / COLUMNS) * SLOT;
            page.addWidget(new SlotWidget(imports, index, x, y, true, true)
                    .setBackgroundTexture(GuiTextures.SLOT)
                    .setHoverTooltips(Component.translatable("gui.gt_shanhai.selected_recipe_pattern.import")));
        }
        return page;
    }

    /** 没有自动配方、但放入格里已经有样板时，直接打开手动组。 */
    private static boolean preferManual(ItemStack stack, ItemStackTransfer imports) {
        if (!SelectedRecipePattern.generatedRecipeIds(stack == null ? null : stack.getTag()).isEmpty()) {
            return false;
        }
        for (int index = 0; index < imports.getSlots(); index++) {
            if (!imports.getStackInSlot(index).isEmpty()) return true;
        }
        return false;
    }

    private static void addPlayerInventory(WidgetGroup group, HeldItemUIFactory.HeldItemHolder holder, Player player) {
        Inventory playerInv = player.getInventory();
        int heldSlot = holder.getHand() == InteractionHand.MAIN_HAND ? playerInv.selected : -1;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + ((row + 1) * 9);
                group.addWidget(new SlotWidget(playerInv, index,
                        ROW_LEFT + (col * SLOT), INV_TOP + (row * SLOT), true, true)
                        .setBackgroundTexture(GuiTextures.SLOT));
            }
        }
        for (int col = 0; col < 9; col++) {
            boolean locked = col == heldSlot;
            group.addWidget(new SlotWidget(playerInv, col,
                    ROW_LEFT + (col * SLOT), HOTBAR_TOP, !locked, !locked)
                    .setBackgroundTexture(GuiTextures.SLOT));
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
            HeldItemUIFactory.INSTANCE.openUI(serverPlayer, context.getHand());
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        SelectedRecipePattern.appendTooltip(stack, level, lines);
    }

    private static String summary(ItemStack stack) {
        SelectedRecipePattern.Contents contents = SelectedRecipePattern.read(stack == null ? null : stack.getTag());
        if (contents == null) {
            return Component.translatable("gui.gt_shanhai.selected_recipe_pattern.empty").getString();
        }
        int output = PatternGeneratorScale.outputFactor(contents.scale(), contents.outputScale());
        ResourceLocation typeId = contents.recipeType().isEmpty()
                ? null : ResourceLocation.tryParse(contents.recipeType());
        if (typeId == null) {
            return Component.translatable("gui.gt_shanhai.selected_recipe_pattern.summary_no_type",
                    contents.recipes().size(), contents.scale(), output).getString();
        }
        return Component.translatable("gui.gt_shanhai.selected_recipe_pattern.summary",
                contents.recipes().size(),
                Component.translatable(typeId.getNamespace() + "." + typeId.getPath()),
                contents.scale(), output).getString();
    }

    private static ItemStackTransfer loadImports(ItemStack bundle) {
        ItemStackTransfer transfer = new ItemStackTransfer(IMPORT_SLOTS);
        transfer.setFilter(SelectedRecipePatternBehavior::acceptsImport);
        CompoundTag tag = bundle == null ? null : bundle.getTag();
        if (tag == null || !tag.contains(IMPORT_TAG, Tag.TAG_COMPOUND)) return transfer;
        ItemStackTransfer stored = new ItemStackTransfer(IMPORT_SLOTS);
        stored.deserializeNBT(tag.getCompound(IMPORT_TAG));
        int copy = Math.min(IMPORT_SLOTS, stored.getSlots());
        for (int index = 0; index < copy; index++) {
            ItemStack stack = stored.getStackInSlot(index);
            if (acceptsImport(stack)) transfer.setStackInSlot(index, stack);
        }
        return transfer;
    }

    private static void saveImports(ItemStack bundle, ItemStackTransfer imports) {
        if (bundle == null || bundle.isEmpty()) return;
        bundle.getOrCreateTag().put(IMPORT_TAG, imports.serializeNBT());
    }

    private static boolean needsSync(ItemStack bundle, ItemStackTransfer imports) {
        CompoundTag tag = bundle == null ? null : bundle.getTag();
        if (tag != null && tag.contains(SelectedRecipePattern.MANUAL_TAG)) return true;
        for (int index = 0; index < imports.getSlots(); index++) {
            if (!imports.getStackInSlot(index).isEmpty()) return true;
        }
        return false;
    }

    private static void syncImports(ItemStack bundle, ItemStackTransfer imports, Level level, int[] unmatched) {
        List<String> ids = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (int index = 0; index < imports.getSlots(); index++) {
            ItemStack stack = imports.getStackInSlot(index);
            GTRecipe recipe = stack.isEmpty() ? null : SelectedRecipePattern.matchEncodedPattern(stack, level);
            if (recipe == null || recipe.id == null) {
                ids.add("");
                types.add("");
                continue;
            }
            ids.add(recipe.id.toString());
            types.add(recipe.recipeType == null || recipe.recipeType.registryName == null
                    ? "" : recipe.recipeType.registryName.toString());
        }
        SelectedRecipePattern.syncManualRecipes(bundle.getOrCreateTag(), ids, types);
        SelectedRecipePattern.Contents stored = SelectedRecipePattern.read(bundle.getTag());
        int missed = 0;
        for (int index = 0; index < imports.getSlots(); index++) {
            ItemStack stack = imports.getStackInSlot(index);
            if (stack.isEmpty()) continue;
            String id = ids.get(index);
            if (id.isEmpty() || stored == null || !stored.recipes().contains(id)) missed++;
        }
        unmatched[0] = missed;
    }

    private static boolean acceptsImport(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        if (DShanhaiItems.SELECTED_RECIPE_PATTERN != null
                && stack.getItem() == DShanhaiItems.SELECTED_RECIPE_PATTERN.get()) {
            return false;
        }
        try {
            return PatternDetailsHelper.isEncodedPattern(stack);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String importStatus(ItemStack stack, int unmatched) {
        if (unmatched > 0) {
            return Component.translatable("gui.gt_shanhai.selected_recipe_pattern.unmatched", unmatched).getString();
        }
        CompoundTag tag = stack == null ? null : stack.getTag();
        int generated = SelectedRecipePattern.generatedRecipeIds(tag).size();
        int manual = SelectedRecipePattern.manualRecipeIds(tag).size();
        int shown = Math.min(generated, SelectedRecipePattern.PREVIEW_LIMIT);
        return Component.translatable("gui.gt_shanhai.selected_recipe_pattern.shown", shown, generated, manual)
                .getString();
    }

    private static void fillPreview(WidgetGroup host, ItemStack bundle, Level level) {
        host.clearAllWidgets();
        host.addWidget(preview(SelectedRecipePattern.previewStacks(
                bundle, level, SelectedRecipePattern.PREVIEW_LIMIT, true)));
    }

    /** 已写入的配方只展示，取出请从上方放入格拿回原样板。 */
    private static DraggableScrollableWidgetGroup preview(List<ItemStack> stacks) {
        int count = stacks.size();
        ItemStackTransfer transfer = new ItemStackTransfer(Math.max(1, count));
        transfer.setFilter(ignored -> false);
        for (int index = 0; index < count; index++) {
            transfer.setStackInSlot(index, stacks.get(index));
        }
        DraggableScrollableWidgetGroup scroll = new DraggableScrollableWidgetGroup(
                0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT)
                .setBackground(GuiTextures.DISPLAY)
                .setYScrollBarWidth(SCROLL)
                .setYBarStyle(null, ColorPattern.T_WHITE.rectTexture().setRadius(1));
        int rows = Math.max(VISIBLE_ROWS, (count + COLUMNS - 1) / COLUMNS);
        for (int index = 0; index < rows * COLUMNS; index++) {
            int x = (index % COLUMNS) * SLOT;
            int y = (index / COLUMNS) * SLOT;
            if (index < count) {
                scroll.addWidget(new SlotWidget(transfer, index, x, y, false, false)
                        .setBackgroundTexture(GuiTextures.SLOT)
                        .setIngredientIO(IngredientIO.RENDER_ONLY));
            } else {
                scroll.addWidget(new ImageWidget(x, y, SLOT, SLOT, GuiTextures.SLOT));
            }
        }
        return scroll;
    }
}
