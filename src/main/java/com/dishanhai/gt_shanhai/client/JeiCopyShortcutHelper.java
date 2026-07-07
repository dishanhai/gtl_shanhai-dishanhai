package com.dishanhai.gt_shanhai.client;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;
import java.util.stream.Stream;

public final class JeiCopyShortcutHelper {
    public static final int COPY_NAME = 1;
    public static final int COPY_TAG = 2;
    public static final int SHARE_TO_CHAT = 3;
    public static final int COPY_ID = 4;
    private static final ResourceLocation INFINITY_CELL_ID = ResourceLocation.tryParse("expatternprovider:infinity_cell");

    private JeiCopyShortcutHelper() {}

    public static boolean handle(ITypedIngredient<?> typedIngredient, IIngredientManager ingredientManager, int action) {
        if (typedIngredient == null || ingredientManager == null) return false;

        String text;
        String label;
        if (action == COPY_NAME) {
            text = getDisplayName(typedIngredient, ingredientManager);
            label = "名称";
        } else if (action == COPY_TAG) {
            text = getFirstTag(typedIngredient, ingredientManager);
            label = "标签";
        } else if (action == SHARE_TO_CHAT) {
            text = getShareText(typedIngredient, ingredientManager);
            label = "聊天链接";
        } else if (action == COPY_ID) {
            text = getId(typedIngredient, ingredientManager);
            label = "ID";
        } else {
            return false;
        }

        if (text == null || text.isEmpty()) {
            showMessage(Component.literal("[山海JEI] 没有可复制的" + label).withStyle(ChatFormatting.RED));
            return true;
        }

        if (action == SHARE_TO_CHAT) {
            Minecraft.getInstance().setScreen(new ChatScreen(text));
        } else {
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
        }
        showMessage(Component.literal("[山海JEI] 已复制" + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(text).withStyle(ChatFormatting.YELLOW)));
        return true;
    }

    public static ItemStack createInfinityCellStack(ITypedIngredient<?> typedIngredient) {
        InfinityCellTarget target = resolveInfinityCellTarget(typedIngredient);
        if (target == null || INFINITY_CELL_ID == null) return ItemStack.EMPTY;

        Item item = ForgeRegistries.ITEMS.getValue(INFINITY_CELL_ID);
        if (item == null) return ItemStack.EMPTY;

        ItemStack stack = new ItemStack(item);
        stack.setTag(buildInfinityCellTag(target));
        return stack;
    }

    static InfinityCellTarget resolveInfinityCellTarget(ITypedIngredient<?> typedIngredient) {
        if (typedIngredient == null) return null;

        Object ingredient = typedIngredient.getIngredient();
        if (ingredient instanceof ItemStack stack) {
            if (stack.isEmpty()) return null;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id == null ? null : new InfinityCellTarget(id.toString(), false);
        }
        if (ingredient instanceof FluidStack stack) {
            if (stack.isEmpty()) return null;
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
            return id == null ? null : new InfinityCellTarget(id.toString(), true);
        }
        return null;
    }

    static CompoundTag buildInfinityCellTag(InfinityCellTarget target) {
        if (target == null || target.id() == null || target.id().isEmpty()) return null;

        CompoundTag tag = new CompoundTag();
        CompoundTag record = new CompoundTag();
        record.putString("#c", target.fluid() ? "ae2:f" : "ae2:i");
        record.putString("id", target.id());
        tag.put("record", record);
        return tag;
    }

    private static String getDisplayName(ITypedIngredient<?> typedIngredient, IIngredientManager ingredientManager) {
        Object ingredient = typedIngredient.getIngredient();
        if (ingredient instanceof ItemStack stack) return stack.getHoverName().getString();
        if (ingredient instanceof FluidStack stack) return stack.getDisplayName().getString();
        return getHelperDisplayName(typedIngredient, ingredientManager);
    }

    private static String getId(ITypedIngredient<?> typedIngredient, IIngredientManager ingredientManager) {
        Object ingredient = typedIngredient.getIngredient();
        if (ingredient instanceof ItemStack stack) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id == null ? "" : id.toString();
        }
        if (ingredient instanceof FluidStack stack) {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
            return id == null ? "" : id.toString();
        }
        return getHelperResourceLocation(typedIngredient, ingredientManager).map(ResourceLocation::toString).orElse("");
    }

    private static String getFirstTag(ITypedIngredient<?> typedIngredient, IIngredientManager ingredientManager) {
        Object ingredient = typedIngredient.getIngredient();
        if (ingredient instanceof ItemStack stack) {
            return stack.getTags().map(JeiCopyShortcutHelper::formatTag).sorted().findFirst().orElse("");
        }
        if (ingredient instanceof FluidStack stack) {
            return stack.getFluid().builtInRegistryHolder().tags().map(JeiCopyShortcutHelper::formatTag).sorted().findFirst().orElse("");
        }
        return getHelperTags(typedIngredient, ingredientManager).map(id -> "#" + id).sorted().findFirst().orElse("");
    }

    private static String getShareText(ITypedIngredient<?> typedIngredient, IIngredientManager ingredientManager) {
        String name = getDisplayName(typedIngredient, ingredientManager);
        String id = getId(typedIngredient, ingredientManager);
        if (name.isEmpty()) return id;
        if (id.isEmpty()) return name;
        return "[" + name + "] " + id;
    }

    private static String formatTag(TagKey<?> tag) {
        return "#" + tag.location();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String getHelperDisplayName(ITypedIngredient<?> typedIngredient, IIngredientManager ingredientManager) {
        IIngredientHelper helper = ingredientManager.getIngredientHelper(typedIngredient.getType());
        Object ingredient = typedIngredient.getIngredient();
        return helper.getDisplayName(ingredient);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<ResourceLocation> getHelperResourceLocation(ITypedIngredient<?> typedIngredient, IIngredientManager ingredientManager) {
        IIngredientHelper helper = ingredientManager.getIngredientHelper(typedIngredient.getType());
        Object ingredient = typedIngredient.getIngredient();
        return Optional.ofNullable(helper.getResourceLocation(ingredient));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Stream<ResourceLocation> getHelperTags(ITypedIngredient<?> typedIngredient, IIngredientManager ingredientManager) {
        IIngredientHelper helper = ingredientManager.getIngredientHelper(typedIngredient.getType());
        Object ingredient = typedIngredient.getIngredient();
        return helper.getTagStream(ingredient);
    }

    private static void showMessage(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(message, true);
    }

    record InfinityCellTarget(String id, boolean fluid) {}
}
