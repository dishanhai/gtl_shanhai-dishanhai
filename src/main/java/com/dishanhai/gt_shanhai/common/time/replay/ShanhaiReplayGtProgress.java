package com.dishanhai.gt_shanhai.common.time.replay;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 山海重构 · <b>GTCEu 机器进度的访问器（隔离类）</b>。
 *
 * <h2>1. 为什么单独一个类</h2>
 * 本工程其它类<b>不</b>硬依赖 gtceu：一旦这个类里的 GT 类型在运行期缺失，
 * JVM 只会在"真正调用到这个类"时抛 {@code NoClassDefFoundError}，
 * {@link ShanhaiReplayProgressRecorder} 在调用点 {@code catch (Throwable)} 接住即可 ⇒
 * 没有 GT 的整合包只是"这一层不生效"，不是起不来。
 *
 * <h2>2. 字段核对（javap 实证，{@code gtceu-1.20.1-1.4.4.jar}）</h2>
 * <pre>
 *   com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity#getMetaMachine()  → MetaMachine
 *   com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine#getRecipeLogic() → RecipeLogic
 *   com.gregtechceu.gtceu.api.machine.trait.RecipeLogic#getProgress():int
 *   com.gregtechceu.gtceu.api.machine.trait.RecipeLogic#setProgress(int):void      ← 只 putfield，不 markDirty
 *   com.gregtechceu.gtceu.api.machine.trait.RecipeLogic#getMaxProgress():int
 * </pre>
 * 🔴 {@code setProgress} 只写字段（不 markDirty）⇒ GT 机器的进度<b>不会</b>触发
 * {@code BlockEntity#setChanged}，所以必须由 {@link ShanhaiReplayProgressRecorder} 每 tick 主动读。
 */
public final class ShanhaiReplayGtProgress {

    /** 这台方块实体是不是"能读能写进度"的 GT 机器（{@code false} 不代表它不是机器，只代表没有配方进度）。 */
    public static boolean supports(BlockEntity be) {
        if (!(be instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity machineBe)) {
            return false;
        }
        final com.gregtechceu.gtceu.api.machine.MetaMachine machine = machineBe.getMetaMachine();
        return machine instanceof com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
    }

    /**
     * GT 的那一套类在不在（armed 自证用）。
     *
     * <p>用 {@code Class.forName} 而不是直接引用类型：这里<b>不能</b>因为 GT 不在就抛
     * {@code NoClassDefFoundError}。没有 GT 的整合包 = 这一层不生效，不是起不来。
     */
    public static boolean available() {
        try {
            Class.forName("com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity", false,
                    ShanhaiReplayGtProgress.class.getClassLoader());
            Class.forName("com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine", false,
                    ShanhaiReplayGtProgress.class.getClassLoader());
            Class.forName("com.gregtechceu.gtceu.api.machine.trait.RecipeLogic", false,
                    ShanhaiReplayGtProgress.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 供日志点名用（GT 不在时也安全）。 */
    public static String accessorLine() {
        return "gtceu=MetaMachineBlockEntity#getMetaMachine/IRecipeLogicMachine#getRecipeLogic"
                + "/RecipeLogic#getProgress|setProgress available=" + available();
    }

    /** 读进度（{@code supports()==false} 时返回 {@code Integer.MIN_VALUE}）。 */
    public static int read(BlockEntity be) {
        final com.gregtechceu.gtceu.api.machine.trait.RecipeLogic logic = logicOf(be);
        return logic == null ? Integer.MIN_VALUE : logic.getProgress();
    }

    /** 写进度（回退用）。 */
    public static boolean write(BlockEntity be, int progress) {
        final com.gregtechceu.gtceu.api.machine.trait.RecipeLogic logic = logicOf(be);
        if (logic == null) {
            return false;
        }
        logic.setProgress(progress);
        return true;
    }

    /** 机器类型名（{@code progress_unsupported} 报告里点名用）。 */
    public static String typeName(BlockEntity be) {
        try {
            return be.getType().toString();
        } catch (Throwable ignored) {
            return be.getClass().getName();
        }
    }

    /** 最大进度（诊断/日志用；{@code <= 0} = 没有在跑的配方）。 */
    public static int maxProgress(BlockEntity be) {
        final com.gregtechceu.gtceu.api.machine.trait.RecipeLogic logic = logicOf(be);
        return logic == null ? 0 : logic.getMaxProgress();
    }

    private static com.gregtechceu.gtceu.api.machine.trait.RecipeLogic logicOf(BlockEntity be) {
        if (!(be instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity machineBe)) {
            return null;
        }
        final com.gregtechceu.gtceu.api.machine.MetaMachine machine = machineBe.getMetaMachine();
        if (!(machine instanceof com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine logicMachine)) {
            return null;
        }
        return logicMachine.getRecipeLogic();
    }

    private ShanhaiReplayGtProgress() {
    }
}
