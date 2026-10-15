package com.dishanhai.gt_shanhai.common.item;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorScale;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 星律通配样板：右键打开只读样板格，悬停写出数量、类型、倍率和前几条配方。
 * 方块自己要打开界面时仍优先，例如右键机器不会被这张样板抢走。
 */
public final class SelectedRecipePatternBehavior implements IItemUIFactory, IAddInformation {
    public static final SelectedRecipePatternBehavior INSTANCE = new SelectedRecipePatternBehavior();

    private static final int SLOT = 18;
    private static final int LEFT = 8;
    private static final int COLUMNS = 14;
    private static final int VISIBLE_ROWS = 5;
    private static final int SCROLL = 4;
    private static final int PREVIEW_TOP = 48;
    private static final int PREVIEW_WIDTH = COLUMNS * SLOT + SCROLL;
    private static final int PREVIEW_HEIGHT = VISIBLE_ROWS * SLOT;
    private static final int WIDTH = LEFT + PREVIEW_WIDTH + 8;
    private static final int HEIGHT = PREVIEW_TOP + PREVIEW_HEIGHT + 8;

    private SelectedRecipePatternBehavior() {}

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        ItemStack stack = holder.getHeld();
        SelectedRecipePattern.Contents contents = SelectedRecipePattern.read(stack.getTag());
        int total = contents == null ? 0 : contents.recipes().size();
        List<ItemStack> shown = SelectedRecipePattern.previewStacks(
                stack, player.level(), SelectedRecipePattern.PREVIEW_LIMIT);
        WidgetGroup group = new WidgetGroup(0, 0, WIDTH, HEIGHT);
        group.addWidget(new LabelWidget(LEFT, 6,
                () -> Component.translatable("item.gt_shanhai.selected_recipe_pattern").getString()));
        group.addWidget(new LabelWidget(LEFT, 18, () -> summary(holder.getHeld())));
        group.addWidget(new LabelWidget(LEFT, 32, () -> Component.translatable(
                "gui.gt_shanhai.selected_recipe_pattern.shown", shown.size(), total).getString()));
        group.addWidget(preview(shown));
        return new ModularUI(WIDTH, HEIGHT, holder, player).widget(group).background(GuiTextures.BACKGROUND);
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

    /** 格子只展示，不让玩家把通配样板当成容器取出。 */
    private static DraggableScrollableWidgetGroup preview(List<ItemStack> stacks) {
        int count = stacks.size();
        ItemStackTransfer transfer = new ItemStackTransfer(Math.max(1, count));
        transfer.setFilter(ignored -> false);
        for (int index = 0; index < count; index++) {
            transfer.setStackInSlot(index, stacks.get(index));
        }
        DraggableScrollableWidgetGroup scroll = new DraggableScrollableWidgetGroup(
                LEFT, PREVIEW_TOP, PREVIEW_WIDTH, PREVIEW_HEIGHT)
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
