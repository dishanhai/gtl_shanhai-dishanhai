package com.dishanhai.gt_shanhai.common.machine.wave;

import com.dishanhai.gt_shanhai.common.machine.part.ProgrammableHatchPartMachine;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleWirelessRecipesLogic;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.gtladd.gtladditions.common.data.ParallelData;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 引力波天线发射器配方逻辑
 * <p>
 * 模式由机器本身管理，本类不参与模式切换逻辑。
 * 配方类型由 {@link GravitationalWaveAntennaTransmitter#getRecipeType()} 根据当前模式返回。
 * 输入匹配由父类 {@code handleRecipeInput} 在运行时处理。
 * <p>
 * 使用 {@code MATCH_ALL} 不过滤输入——避免空闲机器因无输入物而搜不到配方。
 */
public class GravitationalWaveAntennaTransmitterLogic extends GTLAddMultipleWirelessRecipesLogic {

    private static final Predicate<GTRecipe> MATCH_ALL = r -> true;
    private static final long LOOKUP_CACHE_TICKS = 20L;

    private GTRecipeType cachedRecipeType;
    private Set<GTRecipe> cachedRecipes;
    private long cachedRecipeTick = Long.MIN_VALUE;

    public GravitationalWaveAntennaTransmitterLogic(GravitationalWaveAntennaTransmitter machine) {
        super(machine);
    }

    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        GravitationalWaveAntennaTransmitter machine = (GravitationalWaveAntennaTransmitter) getMachine();
        GTRecipeType recipeType = ProgrammableHatchPartMachine.getEffectiveRecipeType(machine, machine.getRecipeType());
        long tick = machine.getOffsetTimer();
        if (cachedRecipes != null && cachedRecipeType == recipeType
                && tick - cachedRecipeTick >= 0 && tick - cachedRecipeTick < LOOKUP_CACHE_TICKS) {
            return cachedRecipes;
        }

        Set<GTRecipe> result = new ObjectOpenHashSet<>();
        Iterator<GTRecipe> iter = recipeType.getLookup()
                .getRecipeIterator((IRecipeCapabilityHolder) getMachine(), MATCH_ALL);
        while (iter.hasNext()) {
            result.add(iter.next());
        }
        cachedRecipeType = recipeType;
        cachedRecipes = result;
        cachedRecipeTick = tick;
        return result;
    }

    @Override
    public int getMultipleThreads() {
        return 1; // 必须 ≥1，否则父类 calculateParallels 中 maxOps = maxParallel * getMultipleThreads() = 0
    }

    @Override
    protected WirelessGTRecipe buildFinalWirelessRecipe(ParallelData data, IWirelessNetworkEnergyHandler handler) {
        WirelessGTRecipe result = super.buildFinalWirelessRecipe(data, handler);
        if (result != null || data == null) return result;

        // 父类因 hasOutputs() 门禁返回 null：广播模式消耗配方无物品/流体产出，
        // 但广播效果本身就是"输出"。用空输出列表 + 正确 EU 重建配方。
        List<GTRecipe> recipes = data.getOriginRecipeList();
        if (recipes == null || recipes.isEmpty()) return null;

        long eut = getWirelessRecipeEut(recipes.get(0));
        BigInteger totalEu = BigInteger.valueOf(eut).multiply(BigInteger.valueOf(data.getParallels()[0]));
        return buildWirelessRecipe(new ObjectArrayList<>(), new ObjectArrayList<>(), totalEu);
    }
}
