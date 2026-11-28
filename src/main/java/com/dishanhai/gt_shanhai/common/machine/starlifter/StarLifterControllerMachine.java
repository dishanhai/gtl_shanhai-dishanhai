package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.common.block.DShanhaiBlocks;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;

import java.util.Map;

public final class StarLifterControllerMachine extends WorkableElectricMultiblockMachine implements IMachineLife {
    private static final ManagedFieldHolder FIELDS = new ManagedFieldHolder(
            StarLifterControllerMachine.class, MANAGED_FIELD_HOLDER);
    @Persisted private boolean showEnabled = true;
    @Persisted private long startTick = -1;
    private Direction captureFront;
    private StarLifterWorld.CaptureReserve captureReserve;
    private volatile BlockPos failedPosition;
    private StarLifterVisualState visual;
    private long completedBatches;

    public StarLifterControllerMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return FIELDS;
    }

    @Override
    public void onLoad() {
        if (getLevel() instanceof ServerLevel) {
            // The persisted flag alone does not rebuild a fresh pattern's dependency index or part handlers.
            isFormed = false;
            failedPosition = null;
            deactivate();
        }
        super.onLoad();
    }

    @Override
    public ModularUI createUI(Player player) {
        return new ModularUI(StarLifterTerminal.WIDTH + 8, StarLifterTerminal.HEIGHT + 94, this, player)
                .widget(new StarLifterTerminal.Frame(this));
    }

    @Override
    public Widget createMainPage(FancyMachineUIWidget frame) {
        return new StarLifterTerminal(this, (StarLifterTerminal.Frame) frame);
    }

    BlockPos failedPosition() {
        return failedPosition;
    }

    long visualStartTick() {
        return startTick;
    }

    boolean isStructureAvailable() {
        return !isInValid() && isRecipeLogicAvailable();
    }

    StarLifterVisualState visualState(ServerLevel level) {
        boolean formed = isStructureAvailable();
        var mode = StarLifterVisualState.Mode.IDLE;
        if (formed && isWorkingEnabled()) {
            var logic = getRecipeLogic();
            if (captureDepleted() || logic.isWaiting()) {
                mode = StarLifterVisualState.Mode.FAULT;
            } else if (logic.isWorking()) {
                var recipe = logic.getLastRecipe();
                mode = recipe != null && (recipe.parallels > 1 || RecipeHelper.getInputEUt(recipe) >= GTValues.V[GTValues.UEV])
                        ? StarLifterVisualState.Mode.HIGH_LOAD : StarLifterVisualState.Mode.RUNNING;
            }
        } else if (!formed && failedPosition != null) {
            mode = StarLifterVisualState.Mode.FAULT;
        }
        boolean linked = formed && StellarResonanceLinks.get(level.getServer()).endpoint(level, getPos(), true).resonant();
        int reserve = StarLifterVisualState.reserveLevel(captureRemaining(), StellarCaptureRules.current().workTicks());
        long tick = level.getGameTime();
        visual = visual == null ? new StarLifterVisualState(mode, tick, linked, reserve,
                (float) reserve / StarLifterVisualState.FULL_RESERVE, tick) : visual.update(mode, tick, linked, reserve);
        return visual;
    }

    boolean isShowEnabled() {
        return showEnabled;
    }

    boolean canUseTerminal(ServerPlayer player) {
        return !isInvalid() && player.level() == getLevel()
                && player.distanceToSqr(Vec3.atCenterOf(getPos())) <= 64
                && MetaMachine.getMachine(player.level(), getPos()) == this;
    }

    long captureRemaining() {
        return captureReserve().remaining();
    }

    boolean captureDepleted() {
        return captureReserve().depleted();
    }

    long completedBatches() {
        return completedBatches;
    }

    public void recordCompletedBatch() {
        completedBatches++;
    }

    private StarLifterWorld.CaptureReserve captureReserve() {
        Direction front = getFrontFacing();
        if (captureReserve == null || front != captureFront) {
            captureReserve = StarLifterWorld.captureReserve((ServerLevel) getLevel(),
                    BlockPos.containing(StarLifterStructure.center(getPos(), front)));
            captureFront = front;
        }
        return captureReserve;
    }

    @Override
    public boolean beforeWorking(GTRecipe recipe) {
        int cost = recipe == null ? 1 : Math.max(1, recipe.parallels);
        var reserve = captureReserve();
        if (reserve.remaining() >= cost) return super.beforeWorking(recipe);
        reserve.block(cost);
        return false;
    }

    @Override
    protected RecipeLogic createRecipeLogic(Object... args) {
        return new RecipeLogic(this) {
            @Override
            public void findAndHandleRecipe() {
                if (!captureDepleted()) super.findAndHandleRecipe();
            }

            @Override
            public void handleRecipeWorking() {
                var recipe = getLastRecipe();
                int cost = Math.max(1, recipe.parallels);
                var reserve = captureReserve();
                if (reserve.remaining() < cost) {
                    if (isWorking()) recipe.postWorking(this.machine);
                    setWaiting(Component.translatable(StarLifterTerminal.TEXT + "capture.depleted"));
                    reserve.block(cost);
                    return;
                }
                int previous = getProgress();
                super.handleRecipeWorking();
                if (getProgress() > previous) reserve.extract(cost);
            }

            @Override
            public void onRecipeFinish() {
                var recipe = getLastRecipe();
                if (recipe != null) {
                    // Simulate the actual output route before GTLCore attempts any insertion.
                    var output = RecipeRunnerHelper.handleRecipe(IO.OUT, this.machine, recipe.outputs,
                            Map.of(), true, recipe, true);
                    if (!output.isSuccess()) {
                        setWaiting(output.reason());
                        return;
                    }
                }
                super.onRecipeFinish();
            }
        };
    }

    String reactivateCapture(ServerPlayer player) {
        if (!isStructureAvailable()) return "capture.invalid";
        if (!captureDepleted()) return "capture.ready";
        var rules = StellarCaptureRules.current();
        if (rules.items().isEmpty()) return "capture.no_rules";
        var inventory = player.getInventory();
        for (var entry : rules.items().entrySet()) {
            int held = 0;
            for (var stack : inventory.items) {
                if (stack.is(entry.getKey())) held += stack.getCount();
            }
            if (held < entry.getValue()) return "capture.missing";
        }
        for (var entry : rules.items().entrySet()) {
            int left = entry.getValue();
            for (var stack : inventory.items) {
                if (left == 0) break;
                if (stack.is(entry.getKey())) {
                    int count = Math.min(left, stack.getCount());
                    stack.shrink(count);
                    left -= count;
                }
            }
        }
        inventory.setChanged();
        captureReserve().replenish();
        recipeLogic.updateTickSubscription();
        return "capture.restored";
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        failedPosition = null;
        activate();
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        failedPosition = null;
        startTick = -1;
        deactivate();
    }

    /** GT already matched every other position when this dependency was indexed. */
    public boolean checkChangedPosition(ServerLevel level, BlockPos pos) {
        char symbol = StarLifterStructure.symbolAt(getPos(), getFrontFacing(), pos);
        if (StarLifterStructure.matches(symbol, level.getBlockState(pos))) {
            // An accepted hatch replacement changes the recipe handlers even when the pattern is valid.
            return symbol != 'K';
        }
        onStructureInvalid();
        failedPosition = pos.immutable();
        var structures = MultiblockWorldSavedData.getOrCreate(level);
        structures.removeMapping(getMultiblockState());
        structures.addAsyncLogic(this);
        return true;
    }

    @Override
    public void asyncCheckPattern(long periodId) {
        BlockPos failed = failedPosition;
        if (failed != null && getLevel() instanceof ServerLevel level) {
            var chunk = level.getChunkSource().getChunkForLighting(SectionPos.blockToSectionCoord(failed.getX()),
                    SectionPos.blockToSectionCoord(failed.getZ()));
            if (chunk == null || !StarLifterStructure.matches(StarLifterStructure.symbolAt(getPos(), getFrontFacing(), failed),
                    chunk.getBlockState(failed))) return;
        }
        super.asyncCheckPattern(periodId);
    }

    @Override
    public void onPartUnload() {
        super.onPartUnload();
        deactivate();
    }

    @Override
    public void onUnload() {
        deactivate();
        super.onUnload();
    }

    @Override
    public void onMachineRemoved() {
        deactivate();
        if (getLevel() instanceof ServerLevel level) {
            StellarResonanceLinks.get(level.getServer()).unlink(GlobalPos.of(level.dimension(), getPos()));
        }
    }

    @Override
    protected InteractionResult onSoftMalletClick(Player player, InteractionHand hand, Direction side, BlockHitResult hit) {
        if (!isRemote()) {
            setShowEnabled(!showEnabled);
            player.displayClientMessage(Component.translatable("gt_shanhai.multiblock.star_lifter."
                    + (showEnabled ? "enabled" : "disabled")), true);
        }
        return InteractionResult.sidedSuccess(isRemote());
    }

    void setShowEnabled(boolean enabled) {
        if (showEnabled == enabled) return;
        showEnabled = enabled;
        if (enabled) {
            activate();
        } else {
            startTick = -1;
            deactivate();
        }
        markDirty();
    }

    private void activate() {
        if (!(getLevel() instanceof ServerLevel level) || !showEnabled || !isStructureAvailable()) return;
        if (startTick < 0) {
            startTick = level.getGameTime();
            markDirty();
        }
        var front = getFrontFacing();
        StarLifterWorld.activateController(level, getPos(), StarLifterStructure.center(getPos(), front),
                StarLifterStructure.heading(front), startTick, visualState(level));
    }

    private void deactivate() {
        if (getLevel() instanceof ServerLevel level) StarLifterWorld.deactivateController(level, getPos());
    }

    public static MultiblockMachineDefinition register() {
        return GTDishanhaiRegistration.REGISTRATE.multiblock(StarLifterStructure.ID, StarLifterControllerMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .allowExtendedFacing(false)
                .allowFlip(false)
                .tier(GTValues.UEV)
                .recipeTypes(DShanhaiRecipeTypes.STELLAR_DECONSTRUCTION)
                .pattern(StarLifterStructure::pattern)
                .appearanceBlock(() -> DShanhaiBlocks.STAR_LIFTER_TERMINAL.get())
                .workableCasingRenderer(new ResourceLocation("gt_shanhai", "block/star_lifter/socket"),
                        new ResourceLocation("gtceu", "block/multiblock/fusion_reactor"))
                .tooltips(Component.translatable("gt_shanhai.star_lifter.dossier.eyebrow").withStyle(ChatFormatting.GOLD),
                        Component.translatable("gt_shanhai.star_lifter.dossier.class").withStyle(ChatFormatting.GOLD),
                        Component.translatable("gt_shanhai.star_lifter.dossier.motto"),
                        Component.translatable("gt_shanhai.star_lifter.dossier.intro"),
                        Component.translatable("gt_shanhai.star_lifter.dossier.purpose"),
                        Component.translatable("gt_shanhai.star_lifter.dossier.quote").withStyle(ChatFormatting.GOLD),
                        Component.translatable("gt_shanhai.star_lifter.dossier.shift").withStyle(ChatFormatting.GRAY),
                        Component.translatable("gt_shanhai.star_lifter.dossier.ctrl").withStyle(ChatFormatting.GRAY))
                .register();
    }
}
