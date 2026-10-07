package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

/** One recipe row on the select page. Clicking it opens the edit page. */
public final class ShanhaiRecipeCardWidget extends Widget {

    public static final int CH = 28;

    private static final long ENTER_MS = 200L;

    private final ShanhaiRecipeQuery.Card card;
    private final Runnable open;
    private final int order;
    private final long born = ShanhaiRecipeEditorAnimation.nowMs();
    private ItemStackTexture itemIcon;

    public ShanhaiRecipeCardWidget(int x, int y, int width,
                                   ShanhaiRecipeQuery.Card card, Runnable open) {
        this(x, y, width, card, 0, open);
    }

    public ShanhaiRecipeCardWidget(int x, int y, int width,
                                   ShanhaiRecipeQuery.Card card, int order, Runnable open) {
        super(x, y, width, CH);
        this.card = card;
        this.order = Math.max(0, order);
        this.open = open;
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        float enter = enterProgress();
        int x = getPositionX();
        int y = getPositionY();
        int width = getSizeWidth();
        boolean hover = isMouseOverElement(mouseX, mouseY);
        graphics.pose().pushPose();
        graphics.pose().translate(0f, (1f - enter) * 8f, 0f);
        float fade = 0.2f + 0.8f * enter;
        RenderSystem.setShaderColor(1f, 1f, 1f, fade);
        graphics.fill(x, y, x + width, y + CH, hover ? 0xFF69E8FF : 0xFF29445A);
        graphics.fill(x + 1, y + 1, x + width - 1, y + CH - 1, hover ? 0xFF19324A : 0xFF111B2D);
        graphics.fill(x + 4, y + 4, x + 22, y + 22, 0xFF20374B);
        drawOutputIcon(graphics, x + 5, y + 6);
        RenderSystem.setShaderColor(1f, 1f, 1f, fade);
        var font = Minecraft.getInstance().font;
        String mark = "可编辑";
        int markX = x + width - 8 - font.width(mark);
        String name = fit(font, recipeLabel(card.recipeId()), Math.max(8, markX - (x + 28) - 4));
        graphics.drawString(font, name, x + 28, y + 4, 0xFFEAF7FF, false);
        String brief = ioBrief();
        int briefX = x + 28 + font.width(name) + 6;
        if (!brief.isEmpty() && briefX + 12 < markX) {
            graphics.drawString(font, fit(font, brief, markX - briefX - 4), briefX, y + 4, 0xFF8AA6B9, false);
        }
        graphics.drawString(font, trim(card.recipeTypeId() + "  " + card.duration()
                + "t  " + card.eut() + " EU/t", 48), x + 28, y + 15, 0xFF8AA6B9, false);
        graphics.drawString(font, mark, markX, y + 10, 0xFF91F7BC, false);
        graphics.pose().popPose();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private float enterProgress() {
        long elapsed = ShanhaiRecipeEditorAnimation.nowMs() - born - order * 28L;
        if (elapsed <= 0L) return 0f;
        float linear = Math.min(1f, elapsed / (float) ENTER_MS);
        return linear * linear * (3f - 2f * linear);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isMouseOverElement(mouseX, mouseY)) return false;
        if (open != null) open.run();
        return true;
    }

    private void drawOutputIcon(GuiGraphics graphics, int x, int y) {
        String kind = card.iconKind();
        ResourceLocation id = ResourceLocation.tryParse(card.iconId());
        if (id == null || kind == null || kind.isEmpty()) return;
        if ("fluid".equals(kind)) {
            Fluid fluid = BuiltInRegistries.FLUID.get(id);
            if (fluid != null && fluid != Fluids.EMPTY) drawFluidSprite(graphics, fluid, x, y);
            return;
        }
        Item item = resolveItem(kind, id);
        if (item == null || item == Items.AIR) return;
        if (itemIcon == null) itemIcon = new ItemStackTexture(new ItemStack(item));
        itemIcon.draw(graphics, 0, 0, x, y, 16, 16);
    }

    private static Item resolveItem(String kind, ResourceLocation id) {
        if ("tag".equals(kind)) {
            TagKey<Item> key = TagKey.create(Registries.ITEM, id);
            var named = BuiltInRegistries.ITEM.getTag(key);
            if (named.isEmpty()) return Items.AIR;
            for (Holder<Item> holder : named.get()) return holder.value();
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    private static void drawFluidSprite(GuiGraphics graphics, Fluid fluid, int x, int y) {
        ResourceLocation still = null;
        int tint = 0xFFFFFFFF;
        try {
            IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid);
            still = ext.getStillTexture();
            tint = ext.getTintColor();
        } catch (Throwable ignored) {
            graphics.fill(x, y, x + 16, y + 16, 0xC0000000 | (tint & 0x00FFFFFF));
            return;
        }
        if (still == null) {
            graphics.fill(x, y, x + 16, y + 16, 0xC0000000 | (tint & 0x00FFFFFF));
            return;
        }
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
        blitSprite(graphics, sprite, x, y, tint);
    }

    private static void blitSprite(GuiGraphics graphics, TextureAtlasSprite sprite, int x, int y, int tint) {
        if (tint < 0) tint = 0xFFFFFF;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(((tint >> 16) & 0xFF) / 255f,
                ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f, 1f);
        graphics.blit(x, y, 0, 16, 16, sprite);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    private String ioBrief() {
        if (card.inputBrief().isEmpty() && card.outputBrief().isEmpty()) return "";
        String inputs = card.inputBrief().isEmpty() ? "-" : card.inputBrief();
        String outputs = card.outputBrief().isEmpty() ? "-" : card.outputBrief();
        return "入 " + inputs + " → 出 " + outputs;
    }

    private static String fit(net.minecraft.client.gui.Font font, String value, int width) {
        if (value == null || value.isEmpty() || width < 8) return "";
        if (font.width(value) <= width) return value;
        String ellipsis = "…";
        int keep = value.length();
        while (keep > 0 && font.width(value.substring(0, keep) + ellipsis) > width) keep--;
        return keep <= 0 ? "" : value.substring(0, keep) + ellipsis;
    }

    /** Full recipe id, namespace included: {@code gtceu:qft/qft_hyper_stable_self_healing_adhesive}. */
    private static String recipeLabel(String value) {
        if (value == null || value.isEmpty()) return "未命名配方";
        return value;
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
