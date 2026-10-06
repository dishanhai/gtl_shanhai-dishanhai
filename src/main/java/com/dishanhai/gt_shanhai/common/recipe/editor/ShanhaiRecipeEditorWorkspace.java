package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

/**
 * Common, side-neutral editor workspace state.
 *
 * <p>The holographic client only needs page geometry and cell snapshots; the
 * server owns the recipe and decides when a save is allowed. Keeping this
 * class free of LDLib widgets lets both the command path and the holo path
 * consume the same paging contract.</p>
 */
public final class ShanhaiRecipeEditorWorkspace {

    public static final int CELLS_PER_PAGE = 32;

    private final MinecraftServer server;
    private final Player player;
    private ShanhaiIoTable io = new ShanhaiIoTable();
    private int inputPage;
    private int outputPage;

    public ShanhaiRecipeEditorWorkspace(MinecraftServer server, Player player) {
        this.server = server;
        this.player = player;
    }

    public MinecraftServer server() { return server; }
    public Player player() { return player; }
    public ShanhaiIoTable io() { return io; }
    public int inPage() { return inputPage; }
    public int outPage() { return outputPage; }

    public int inPageCount() {
        return pageCountOf(io.inSection());
    }

    public int outPageCount() {
        return pageCountOf(io.outSection());
    }

    public int inCellIndex(int slot) {
        int index = inputPage * CELLS_PER_PAGE + slot;
        return index >= 0 && index < io.inSection() ? io.inIndex(index) : -1;
    }

    public int outCellIndex(int slot) {
        int index = outputPage * CELLS_PER_PAGE + slot;
        return index >= 0 && index < io.outSection() ? io.outIndex(index) : -1;
    }

    public ShanhaiIoTable.Cell cell(int index) {
        return io.cell(index);
    }

    public int cells() {
        return io.cellCount();
    }

    public boolean setInputPage(int page) {
        int next = clampPage(page, inPageCount());
        if (next == inputPage) return false;
        inputPage = next;
        return true;
    }

    public boolean setOutputPage(int page) {
        int next = clampPage(page, outPageCount());
        if (next == outputPage) return false;
        outputPage = next;
        return true;
    }

    public void loadBuffer(GTRecipe recipe, int[] typeCapacity) {
        int[] used = ShanhaiIoTable.usedBy(recipe);
        int[] shape = ShanhaiIoTable.shapeFor(typeCapacity, used);
        io = ShanhaiIoTable.fromRecipeWithShape(recipe, shape);
        inputPage = 0;
        outputPage = 0;
    }

    public String truncationRisk(GTRecipe recipe) {
        return ShanhaiIoTable.truncationRisk(ShanhaiIoTable.usedBy(recipe), io);
    }

    public String capacityText() {
        return "item-in=" + io.itemIn() + " fluid-in=" + io.fluidIn()
                + " item-out=" + io.itemOut() + " fluid-out=" + io.fluidOut();
    }

    public static int pageCountOf(int sectionSize) {
        return Math.max(1, (Math.max(0, sectionSize) + CELLS_PER_PAGE - 1) / CELLS_PER_PAGE);
    }

    private static int clampPage(int page, int count) {
        return Math.max(0, Math.min(Math.max(1, count) - 1, page));
    }
}
