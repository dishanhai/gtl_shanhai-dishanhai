package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.mojang.logging.LogUtils;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海：歸墟彭羅斯黑洞遏制場（被動模組，不跑配方）。
 *
 * <p>只認歸墟視界樞核作主機。每秒一次：收種子／坍縮器 → 扣時空流體 → 推進
 * {@link GuixuContainmentField}；坍縮時通知主機鎖定。增益由主機讀 {@link #field()} 算出。
 * 冗餘（不是主機選中的那一個）時狀態凍結。
 */
public class GuixuPenroseContainmentModule extends PrimordialOmegaEngineModuleBase {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final ResourceLocation SEED = new ResourceLocation("gtladditions", "black_hole_seed");
    static final ResourceLocation HYPER_SEED = new ResourceLocation("dishanhai", "bhd_hyper_seed");
    static final ResourceLocation COLLAPSER = new ResourceLocation("dishanhai", "bhd_collapser");
    static final ResourceLocation SPACETIME = new ResourceLocation("gtceu", "spacetime");
    private static final String NBT_KEY = "ShanhaiContainment";
    private static boolean missingSpacetimeWarned;

    private final GuixuContainmentField field = new GuixuContainmentField();
    private TickableSubscription containmentSubs;

    public GuixuPenroseContainmentModule(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    public GuixuContainmentField field() {
        return field;
    }

    @Override
    public boolean isValidHost(MetaMachine machine) {
        return machine instanceof GuixuVisionCoreMachine && super.isValidHost(machine);
    }

    /** 不跑配方：不需要每 5 tick 輪詢配方。 */
    @Override
    public boolean keepSubscribing() {
        return false;
    }

    /** 物質模塊槽與線程槽對本模組沒有意義。 */
    @Override
    protected boolean showsModuleAndThreadSlots() {
        return false;
    }

    private GuixuVisionCoreMachine guixuHost() {
        return getHost() instanceof GuixuVisionCoreMachine guixu && guixu.isFormed() ? guixu : null;
    }

    // ------------------------------------------------------------------ 生命週期

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        if (!isRemote()) {
            containmentSubs = subscribeServerTick(containmentSubs, this::containmentTick);
        }
    }

    @Override
    public void onStructureInvalid() {
        if (!isRemote() && field.forceCollapse() == GuixuContainmentField.Event.COLLAPSED) {
            // 鎖定那條不要求主機已成型
            GuixuVisionCoreMachine guixu = getHost() instanceof GuixuVisionCoreMachine g ? g : null;
            LOGGER.info("[SHANHAI-CONTAINMENT] 遏制場 @{} 失穩期間結構失效，視同坍縮", getPos());
            if (guixu != null) guixu.startContainmentLock();
            markDirty();
        }
        cancelContainment();
        super.onStructureInvalid();
    }

    @Override
    public void onUnload() {
        cancelContainment();
        super.onUnload();
    }

    private void cancelContainment() {
        if (containmentSubs != null) {
            containmentSubs.unsubscribe();
            containmentSubs = null;
        }
    }

    private void containmentTick() {
        if (getOffsetTimer() % 20 != 0) return;
        GuixuVisionCoreMachine guixu = guixuHost();
        if (guixu == null || guixu.getEffectiveContainment() != this || guixu.isContainmentLocked()) return;
        consumeControlItem();
        GuixuContainmentField.Status status = field.status();
        if (status != GuixuContainmentField.Status.CONTAINED && status != GuixuContainmentField.Status.UNSTABLE) return;
        long need = field.requiredSpacetime();
        GuixuContainmentField.Event event = field.tickSecond(need > 0L && drainSpacetime(need));
        if (event == GuixuContainmentField.Event.DESTABILIZED) {
            LOGGER.info("[SHANHAI-CONTAINMENT] 遏制場 @{} 失穩", getPos());
        } else if (event == GuixuContainmentField.Event.COLLAPSED) {
            LOGGER.info("[SHANHAI-CONTAINMENT] 遏制場 @{} 坍縮", getPos());
            guixu.startContainmentLock();
        }
        markDirty();
    }

    // ------------------------------------------------------------------ 倉室：物品與時空流體

    /** 每次最多處理一個物品：種子開啟、坍縮器關閉；不適用的物品原樣留著。 */
    private void consumeControlItem() {
        for (var part : getParts()) {
            if (!(part instanceof MetaMachine machine)) continue;
            for (var trait : machine.getTraits()) {
                if (!(trait instanceof NotifiableItemStackHandler handler) || handler.handlerIO != IO.IN) continue;
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack stack = handler.getStackInSlot(i);
                    if (stack.isEmpty()) continue;
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    boolean isSeed = SEED.equals(id) || HYPER_SEED.equals(id);
                    boolean isCollapser = COLLAPSER.equals(id);
                    // 先按物品 id 判斷這次投入會不會成立，不成立的物品原樣留著
                    if (isSeed ? field.status() != GuixuContainmentField.Status.CLOSED
                            : !isCollapser || !field.isOpen()) continue;
                    // 走內部路徑真正扣除（直接寫回槽位對 ME 總線不會扣 AE 庫存）；扣成功才推進狀態機
                    ItemStack taken = handler.extractItemInternal(i, 1, false);
                    if (taken.isEmpty()) continue;
                    GuixuContainmentField.Event event = isSeed
                            ? field.open(HYPER_SEED.equals(id)) : field.shutDown();
                    LOGGER.info("[SHANHAI-CONTAINMENT] 遏制場 @{} {}（{}）", getPos(),
                            event == GuixuContainmentField.Event.OPENED ? "開啟" : "主動關閉", id);
                    markDirty();
                    return;
                }
            }
        }
    }

    /** 先模擬加總所有輸入流體倉能扣的時空流體，夠才逐倉實扣；不夠就一滴不扣。 */
    private boolean drainSpacetime(long need) {
        Fluid spacetime = ForgeRegistries.FLUIDS.getValue(SPACETIME);
        if (spacetime == null || spacetime == Fluids.EMPTY) {
            if (!missingSpacetimeWarned) {
                missingSpacetimeWarned = true;
                LOGGER.warn("[SHANHAI-CONTAINMENT] 找不到流體 {}，視為時空流體不足", SPACETIME);
            }
            return false;
        }
        List<NotifiableFluidTank> tanks = new ArrayList<>();
        long available = 0L;
        for (var part : getParts()) {
            if (!(part instanceof MetaMachine machine)) continue;
            for (var trait : machine.getTraits()) {
                if (!(trait instanceof NotifiableFluidTank tank) || tank.handlerIO != IO.IN) continue;
                tanks.add(tank);
                long amount = tank.drainInternal(FluidStack.create(spacetime, need), true).getAmount();
                available = available > Long.MAX_VALUE - amount ? Long.MAX_VALUE : available + amount;
            }
        }
        if (available < need) return false;
        long remaining = need;
        for (NotifiableFluidTank tank : tanks) {
            if (remaining <= 0L) break;
            // 內部路徑：會真正扣 ME 輸入倉的 AE 庫存（直接寫回儲罐不會）
            FluidStack drained = tank.drainInternal(FluidStack.create(spacetime, remaining), false);
            remaining -= drained.getAmount();
        }
        return true;
    }

    // ------------------------------------------------------------------ 存檔

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forSyncing) {
        super.saveCustomPersistedData(tag, forSyncing);
        if (forSyncing) return;
        CompoundTag data = new CompoundTag();
        data.putInt("Status", field.status().ordinal());
        data.putFloat("Stability", field.stability());
        data.putLong("CostModifier", field.costModifier());
        data.putInt("Catalysis", field.catalysisCounter());
        tag.put(NBT_KEY, data);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (!tag.contains(NBT_KEY)) return;
        CompoundTag data = tag.getCompound(NBT_KEY);
        field.restore(data.getInt("Status"), data.getFloat("Stability"),
                data.getLong("CostModifier"), data.getInt("Catalysis"));
    }

    // ------------------------------------------------------------------ 顯示

    @Override
    public void addDisplayText(List<Component> textList) {
        if (!isFormed()) {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure").withStyle(ChatFormatting.RED));
        } else {
            addHostStatusDisplay(textList);
            GuixuVisionCoreMachine guixu = guixuHost();
            if (guixu == null) {
                textList.add(Component.literal("需安裝在歸墟視界樞核的模組位").withStyle(ChatFormatting.RED));
            } else if (guixu.getEffectiveContainment() != this) {
                textList.add(Component.literal("冗餘（本機已有生效的遏制場）").withStyle(ChatFormatting.GRAY));
            } else {
                for (String line : GuixuContainmentText.moduleLines(field, guixu.getContainmentLockSeconds())) {
                    textList.add(Component.literal(line).withStyle(ChatFormatting.LIGHT_PURPLE));
                }
            }
        }
        textList.add(Component.translatable("gt_shanhai.machine.guixu_penrose_containment_field.name")
                .withStyle(ChatFormatting.GOLD));
    }
}
