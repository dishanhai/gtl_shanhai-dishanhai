package com.dishanhai.gt_shanhai.integration.gtladditions;

import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.common.data.GTMachines;
import com.gtladd.gtladditions.api.pattern.PatternPredicatePatchKt;
import com.gtladd.gtladditions.common.machine.GTLAddMachines;
import com.gtladd.gtladditions.common.machine.multiblock.MultiBlockMachine;
import net.minecraftforge.fml.ModList;

/** Extends only the explicit input-bus predicate of the packaged GTLAdditions controller. */
public final class DraconicCollapseInputCompat {

    private DraconicCollapseInputCompat() {
    }

    public static void install() {
        // The upstream release and the pack's Custom build have different patterns and APIs.
        if (!ModList.get().getModContainerById("gtladditions")
                .map(container -> "3.3.1Custom".equals(container.getModInfo().getVersion().toString()))
                .orElse(false)) {
            return;
        }

        Custom.install();
    }

    // Avoid linking the patch API on older GTLAdditions builds.
    private static final class Custom {
        private static void install() {
            var stellar = DShanhaiMachines.RECIPE_TYPE_PATTERN_BUFFER;
            if (stellar == null) {
                return;
            }

            // L lists these three blocks explicitly. O is output-ability based and does not
            // include the normal input bus, even when its candidate set contains a stock buffer.
            var input = PatternPredicatePatchKt.patternPredicateSelector(() -> GTMachines.ITEM_IMPORT_BUS[0].getBlock())
                    .containsBlock(() -> GTLAddMachines.INSTANCE.getME_SUPER_PATTERN_BUFFER().getBlock())
                    .containsBlock(() -> GTLAddMachines.INSTANCE.getME_SUPER_PATTERN_BUFFER_PROXY().getBlock());
            PatternPredicatePatchKt.patchPatternPredicates(
                    MultiBlockMachine.INSTANCE.getDRACONIC_COLLAPSE_CORE(),
                    "gt_shanhai:draconic_collapse_stellar_input",
                    input,
                    () -> Predicates.blocks(stellar.getBlock()),
                    false);
        }
    }
}
