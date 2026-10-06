package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.ingredient.Target;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Ghost slot that turns a JEI drop into a recipe-id search.
 * The query packet only matches ids, so this does not pretend to be a reverse lookup.
 */
public final class ShanhaiQuerySlotWidget extends Widget implements IGhostIngredientTarget {

    private static final int ACT_SET = 1;
    private static final int ACT_CLEAR = 2;
    public static final int CELL = 22;

    private final Consumer<String> onId;
    private ItemStack shownItem = ItemStack.EMPTY;
    private FluidStack shownFluid = FluidStack.empty();

    public ShanhaiQuerySlotWidget(int x, int y, Consumer<String> onId) {
        super(x, y, CELL, CELL);
        this.onId = onId;
    }

    public void clear() {
        shownItem = ItemStack.EMPTY;
        shownFluid = FluidStack.empty();
    }

    @Override
    public List<Target> getPhantomTargets(Object ingredient) {
        Object normalized = normalize(ingredient);
        if (normalized == null) return List.of();
        return List.of(new Target() {
            @Override
            public Rect2i getArea() {
                return new Rect2i(getPositionX(), getPositionY(), CELL, CELL);
            }

            @Override
            public void accept(Object accepted) {
                Object value = normalize(accepted);
                String id = idOf(value);
                if (id.isEmpty()) return;
                remember(value);
                if (onId != null) onId.accept(id);
                writeClientAction(ACT_SET, buffer -> {
                    buffer.writeBoolean(value instanceof ItemStack);
                    buffer.writeUtf(id, 256);
                    if (value instanceof ItemStack stack) buffer.writeItem(stack);
                    else ((FluidStack) value).writeToBuf(buffer);
                });
            }
        });
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOverElement(mouseX, mouseY) || button != 1) return false;
        clear();
        if (onId != null) onId.accept("");
        writeClientAction(ACT_CLEAR, buffer -> {});
        return true;
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (id == ACT_CLEAR) {
            clear();
            if (onId != null) onId.accept("");
            return;
        }
        if (id != ACT_SET) return;
        boolean item = buffer.readBoolean();
        String registryId = buffer.readUtf(256);
        if (item) remember(buffer.readItem());
        else remember(FluidStack.readFromBuf(buffer));
        if (onId != null) onId.accept(registryId);
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int x = getPositionX();
        int y = getPositionY();
        boolean filled = !shownItem.isEmpty() || !shownFluid.isEmpty();
        graphics.fill(x, y, x + CELL, y + CELL, filled ? 0xFF69E8FF : ShanhaiIOWidget.FRAME_ITEM);
        graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xFF20374B);
        if (!shownItem.isEmpty()) {
            new ItemStackTexture(shownItem).draw(graphics, mouseX, mouseY, x + 3, y + 3, 16, 16);
        } else if (!shownFluid.isEmpty()) {
            graphics.fill(x + 4, y + 4, x + CELL - 4, y + CELL - 4, ShanhaiIOWidget.FRAME_FLUID);
        } else {
            graphics.drawString(net.minecraft.client.Minecraft.getInstance().font,
                    "查", x + 7, y + 7, 0xFF8AA6B9, false);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        setHoverTooltips(List.of(
                Component.literal("拖入物品或流体，按其注册 ID 搜索配方 ID"),
                Component.literal("右键清空。这不是原料反查")));
    }

    private void remember(Object value) {
        if (value instanceof ItemStack stack) {
            shownItem = stack.copyWithCount(1);
            shownFluid = FluidStack.empty();
        } else if (value instanceof FluidStack fluid) {
            shownItem = ItemStack.EMPTY;
            shownFluid = fluid.copy();
        }
    }

    private static Object normalize(Object ingredient) {
        Object item = ShanhaiIOWidget.normalize(ingredient, true);
        if (item != null) return item;
        return ShanhaiIOWidget.normalize(ingredient, false);
    }

    private static String idOf(Object value) {
        if (value instanceof ItemStack stack && !stack.isEmpty()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id == null ? "" : id.toString();
        }
        if (value instanceof FluidStack fluid && !fluid.isEmpty()) {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid.getFluid());
            return id == null ? "" : id.toString();
        }
        return "";
    }
}
