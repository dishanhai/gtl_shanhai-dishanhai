package com.dishanhai.gt_shanhai.common.machine.nebula;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.common.machine.multiblock.part.FluidHatchPartMachine;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Locale;

/**
 * 天界虹吸矩阵 — IV 级无线电网取电。
 * 未注入 256 并行；真空零点能与物质流按档位提升，每 5 刻消耗 250 mB，只取仓内最高档。
 */
public class NebulaSiphonMachine extends GTLAddWirelessWorkableElectricMultipleRecipesMachine {

    private static final long INTERVAL = 5;
    private static final int MB = 250;
    private static final int BASE_PARALLEL = 256;

    /** 下标 0 为最高档。旧三档并行保持原值，高级之上每档翻倍。 */
    private static final Fuel[] FUELS = {
            new Fuel("matter_fluid_ultimate", 5_242_880, "创造物质流", 7),
            new Fuel("matter_fluid_eternal", 2_621_440, "永恒物质流", 5),
            new Fuel("matter_fluid_transcend", 1_310_720, "超限物质流", 6),
            new Fuel("matter_fluid_peak", 655_360, "巅峰物质流", 0),
            new Fuel("matter_fluid_ascension", 327_680, "升维物质流", 6),
            new Fuel("matter_fluid_transmutation", 163_840, "嬗变物质流", 4),
            new Fuel("matter_fluid_transition", 81_920, "虚数跃迁物质流", 5),
            new Fuel("matter_fluid_darkstar", 40_960, "暗星物质流", 4),
            new Fuel("matter_fluid_zero", 20_480, "归零物质流", 0),
            new Fuel("matter_fluid_advanced", 10_240, "高级物质流", 3),
            new Fuel("matter_fluid_virtual", 4_096, "虚像物质流", 2),
            new Fuel("matter_fluid_basic", 2_048, "初级物质流", 1),
            new Fuel("zero_point_energy", 1_024, "真空零点能", 0),
    };

    private static Fluid[] primaryFluids;
    private static Fluid[] alternateFluids;

    private int parallel = BASE_PARALLEL;
    private String activeLabel = "未注入";
    private long lastTick = 0;
    private TickableSubscription subs;

    public NebulaSiphonMachine(IMachineBlockEntity holder, Object... args) { super(holder, args); }

    @Override
    public int getMaxParallel() {
        return parallel;
    }

    public static void appendBoostTooltip(List<Component> tooltips) {
        tooltips.add(Component.literal("")
                .append(Component.literal("§7未注入"))
                .append(Component.literal(" §7→ "))
                .append(DShanhaiTextUtil.createElectricText(formatParallel(BASE_PARALLEL)))
                .append(Component.literal(" §7并行")));
        for (int i = FUELS.length - 1; i >= 0; i--) {
            Fuel fuel = FUELS[i];
            tooltips.add(line(fuel.label, fuel.style, fuel.parallel, i == 0));
        }
    }

    @Override public NebulaSiphonLogic createRecipeLogic(Object... args) { return new NebulaSiphonLogic(this); }
    @Override public NebulaSiphonLogic getRecipeLogic() { return (NebulaSiphonLogic) super.getRecipeLogic(); }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        subs = subscribeServerTick(subs, this::tick);
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        if (subs != null) { subs.unsubscribe(); subs = null; }
        parallel = BASE_PARALLEL;
        activeLabel = "未注入";
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        if (!isFormed()) return;
        textList.add(Component.literal("§7虹吸: §f" + activeLabel
                + " §7· §b并行 §f" + formatParallel(parallel)));
    }

    private void tick() {
        if (getLevel() == null) return;
        long now = getLevel().getGameTime();
        if (now - lastTick < INTERVAL) return;
        lastTick = now;

        int best = -1;
        FluidHatchPartMachine bestHatch = null;
        int bestSlot = -1;
        FluidStack bestStack = null;
        Fluid bestFluid = Fluids.EMPTY;

        for (var part : getParts()) {
            if (!(part instanceof FluidHatchPartMachine hatch)) continue;
            NotifiableFluidTank tank = hatch.tank;
            for (int i = 0; i < tank.getTanks(); i++) {
                FluidStack inTank = tank.getFluidInTank(i);
                if (inTank == null || inTank.getAmount() < MB) continue;
                int idx = fuelIndex(inTank.getFluid());
                if (idx < 0 || (best >= 0 && idx >= best)) continue;
                best = idx;
                bestHatch = hatch;
                bestSlot = i;
                bestStack = inTank;
                bestFluid = inTank.getFluid();
            }
        }

        if (best < 0 || bestHatch == null || bestStack == null) {
            parallel = BASE_PARALLEL;
            activeLabel = "未注入";
            return;
        }

        long remaining = bestStack.getAmount() - MB;
        if (remaining <= 0) {
            bestHatch.tank.setFluidInTank(bestSlot, FluidStack.empty());
        } else {
            bestHatch.tank.setFluidInTank(bestSlot, FluidStack.create(bestFluid, remaining));
        }
        Fuel fuel = FUELS[best];
        parallel = fuel.parallel;
        activeLabel = fuel.label;
    }

    private static int fuelIndex(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) return -1;
        ensureFluids();
        if (primaryFluids == null) return -1;
        for (int i = 0; i < FUELS.length; i++) {
            if (fluid == primaryFluids[i] || fluid == alternateFluids[i]) return i;
        }
        return -1;
    }

    private static void ensureFluids() {
        if (primaryFluids != null) return;
        Fluid[] primary = new Fluid[FUELS.length];
        Fluid[] alternate = new Fluid[FUELS.length];
        boolean any = false;
        for (int i = 0; i < FUELS.length; i++) {
            primary[i] = registered("dishanhai", FUELS[i].id);
            alternate[i] = registered("shanhai", FUELS[i].id);
            if (primary[i] != Fluids.EMPTY || alternate[i] != Fluids.EMPTY) any = true;
        }
        if (!any) return;
        primaryFluids = primary;
        alternateFluids = alternate;
    }

    private static Fluid registered(String namespace, String id) {
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(namespace, id));
        return fluid == null ? Fluids.EMPTY : fluid;
    }

    private static Component line(String label, int style, int parallel, boolean rainbowNumber) {
        Component number = rainbowNumber
                ? DShanhaiTextUtil.createUltimateRainbow(formatParallel(parallel))
                : DShanhaiTextUtil.createElectricText(formatParallel(parallel));
        return Component.literal("")
                .append(styled(style, label))
                .append(Component.literal(" §7→ "))
                .append(number)
                .append(Component.literal(" §7并行"));
    }

    private static Component styled(int style, String text) {
        return switch (style) {
            case 1 -> DShanhaiTextUtil.createNatureText(text);
            case 2 -> DShanhaiTextUtil.createMagicText(text);
            case 3 -> DShanhaiTextUtil.createLavaText(text);
            case 4 -> DShanhaiTextUtil.createAuroraText(text);
            case 5 -> DShanhaiTextUtil.createNeonText(text);
            case 6 -> DShanhaiTextUtil.createUltimateRainbow(text);
            case 7 -> DShanhaiTextUtil.createGoldenText(text);
            default -> DShanhaiTextUtil.createWaterText(text);
        };
    }

    private static String formatParallel(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private record Fuel(String id, int parallel, String label, int style) {}
}
