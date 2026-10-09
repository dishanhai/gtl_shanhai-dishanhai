package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Native LDLib entry point for the layered recipe editor.
 *
 * <p>The holder is a singleton because the real state lives in the widget and
 * on the server-side recipe packets. This keeps the factory payload tiny and
 * makes the holo bridge usable without constructing a fake item holder.</p>
 */
public final class ShanhaiRecipeEditorFactory extends UIFactory<ShanhaiRecipeEditorFactory>
        implements IUIHolder {

    public static final ShanhaiRecipeEditorFactory INSTANCE = new ShanhaiRecipeEditorFactory();
    private static boolean registered;
    private static final ThreadLocal<String[]> OPEN_TARGET = new ThreadLocal<>();

    private ShanhaiRecipeEditorFactory() {
        super(new net.minecraft.resources.ResourceLocation(
                GTDishanhaiMod.MOD_ID, "shanhai_recipe_editor"));
    }

    public static void register() {
        if (registered) return;
        registered = true;
        UIFactory.register(INSTANCE);
    }

    /** Stable reflection entry used by the holo menu and command path. */
    public static boolean open(Object ignored, ServerPlayer player) {
        return open(player, "", "");
    }

    /** Opens the editor and lands on one recipe. Empty ids keep the normal entry. */
    public static boolean open(ServerPlayer player, String recipeTypeId, String recipeId) {
        if (player == null) return false;
        OPEN_TARGET.set(new String[] {
                recipeTypeId == null ? "" : recipeTypeId,
                recipeId == null ? "" : recipeId
        });
        try {
            return INSTANCE.openUI(INSTANCE, player);
        } finally {
            OPEN_TARGET.remove();
        }
    }

    @Override
    protected ModularUI createUITemplate(
            ShanhaiRecipeEditorFactory holder, Player player) {
        return new ModularUI(ShanhaiRecipeEditorWidget.WIDTH, ShanhaiRecipeEditorWidget.HEIGHT, holder, player)
                .widget(new ShanhaiRecipeEditorWidget(player))
                .background(GuiTextures.BACKGROUND);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    protected ShanhaiRecipeEditorFactory readHolderFromSyncData(FriendlyByteBuf buffer) {
        ShanhaiRecipeEditorLaunch.arm(buffer.readUtf(256), buffer.readUtf(256));
        return INSTANCE;
    }

    @Override
    protected void writeHolderToSyncData(FriendlyByteBuf buffer,
                                         ShanhaiRecipeEditorFactory holder) {
        String[] target = OPEN_TARGET.get();
        String typeId = target == null || target.length < 1 || target[0] == null ? "" : target[0];
        String recipeId = target == null || target.length < 2 || target[1] == null ? "" : target[1];
        buffer.writeUtf(typeId, 256);
        buffer.writeUtf(recipeId, 256);
    }

    @Override
    public ModularUI createUI(Player player) {
        return createUITemplate(this, player);
    }

    @Override
    public boolean isInvalid() {
        return false;
    }

    @Override
    public boolean isRemote() {
        return LDLib.isRemote();
    }

    @Override
    public void markAsDirty() {
        // Recipe edits are persisted by ShanhaiRecipeEditorOps.
    }
}
