package com.dishanhai.gt_shanhai.common.machine.part;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferProxyPartMachine;
import org.jetbrains.annotations.Nullable;

public class RecipeTypePatternBufferProxyPartMachine extends MEPatternBufferProxyPartMachine {

    public RecipeTypePatternBufferProxyPartMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public void setBuffer(@Nullable BlockPos pos) {
        if (pos != null && getLevel() != null) {
            BlockGetter level = getLevel();
            MetaMachine machine = MEPatternBufferProxyPartMachine.getMachine(level, pos);
            if (!(machine instanceof RecipeTypePatternBufferPartMachine)) {
                return;
            }
        }
        super.setBuffer(pos);
    }
}
