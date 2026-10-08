package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;

import com.dishanhai.gt_shanhai.common.item.PatternNotConsumableFilter;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotState;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import org.gtlcore.gtlcore.integration.ae2.handler.SlotCacheManager;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.ArrayList;
import java.util.List;

@Mixin(targets = "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase$InternalSlot", remap = false)
public class GTLCorePatternInternalSlotVirtualProviderMixin implements VirtualPatternBufferSlotAccess {

    @Shadow
    @Final
    private Object2LongOpenHashMap<AEItemKey> itemInventory;

    @Shadow
    @Final
    private Object2LongOpenHashMap<AEFluidKey> fluidInventory;

    @Shadow
    private ObjectSet<AEItemKey> virtualItemSupply;

    @Shadow
    private ObjectSet<AEFluidKey> virtualFluidSupply;

    @Shadow
    private ObjectSet<AEItemKey> configuredVirtualItems;

    @Shadow
    private ObjectSet<AEFluidKey> configuredVirtualFluids;

    @Override
    public void gtShanhai$addVirtualTarget(AEKey key, long amount) {
        if (key instanceof AEItemKey itemKey) {
            VirtualPatternBufferSlotState.addVirtualTarget(itemInventory, itemKey, amount);
            gtShanhai$rememberVirtualItem(itemKey);
        } else if (key instanceof AEFluidKey fluidKey) {
            VirtualPatternBufferSlotState.addVirtualTarget(fluidInventory, fluidKey, amount);
            gtShanhai$rememberVirtualFluid(fluidKey);
        }
    }

    @Override
    public void gtShanhai$restoreVirtualTarget(AEKey key, long amount) {
        if (key instanceof AEItemKey itemKey) {
            VirtualPatternBufferSlotState.registerVirtualTarget(
                    itemInventory, itemKey, amount, gtShanhai$getItemCatalystInventory());
            gtShanhai$rememberVirtualItem(itemKey);
        } else if (key instanceof AEFluidKey fluidKey) {
            VirtualPatternBufferSlotState.registerVirtualTarget(
                    fluidInventory, fluidKey, amount, gtShanhai$getFluidCatalystInventory());
            gtShanhai$rememberVirtualFluid(fluidKey);
        }
    }

    @Override
    public boolean gtShanhai$hasVirtualTarget(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            return VirtualPatternBufferSlotState.getVirtualTargets(itemInventory).containsKey(itemKey);
        }
        if (key instanceof AEFluidKey fluidKey) {
            return VirtualPatternBufferSlotState.getVirtualTargets(fluidInventory).containsKey(fluidKey);
        }
        return false;
    }

    @Override
    public void gtShanhai$syncVirtualTargetsToCatalyst() {
        VirtualPatternBufferSlotState.copyVirtualTargets(itemInventory, gtShanhai$getItemCatalystInventory());
        VirtualPatternBufferSlotState.copyVirtualTargets(fluidInventory, gtShanhai$getFluidCatalystInventory());
    }

    @Override
    public void gtShanhai$stripVirtualTargetsFromCatalyst() {
        VirtualPatternBufferSlotState.removeVirtualTargets(itemInventory, gtShanhai$getItemCatalystInventory());
        VirtualPatternBufferSlotState.removeVirtualTargets(fluidInventory, gtShanhai$getFluidCatalystInventory());
    }

    @Override
    public void gtShanhai$stripVirtualTargets() {
        gtShanhai$stripVirtualItems();
        gtShanhai$stripVirtualFluids();
        gtShanhai$clearCatalystsIfDepleted();
    }

    @Override
    public void gtShanhai$clearVirtualTargetsIfDepleted() {
        gtShanhai$clearCatalystsIfDepleted();
    }

    @Inject(method = "serializeNBT", at = @At("RETURN"), remap = false)
    private void gtShanhai$saveVirtualIdentity(CallbackInfoReturnable<CompoundTag> cir) {
        VirtualPatternBufferSlotState.writeVirtualTargets(itemInventory, fluidInventory, cir.getReturnValue());
    }

    @Inject(method = "deserializeNBT", at = @At("RETURN"), remap = false)
    private void gtShanhai$loadVirtualIdentity(CompoundTag tag, CallbackInfo ci) {
        VirtualPatternBufferSlotState.readVirtualTargets(itemInventory, fluidInventory, tag,
                gtShanhai$getItemCatalystInventory(), gtShanhai$getFluidCatalystInventory());
        gtShanhai$syncVirtualTargetsToCatalyst();
    }

    @Unique
    private void gtShanhai$stripVirtualItems() {
        VirtualPatternBufferSlotState.removeVirtualTargets(itemInventory, gtShanhai$getItemCatalystInventory());
        // 配方执行后剥离虚拟目标，但保留不消耗催化剂（chance==0）：让它撑住这一单的后续执行，
        // 不再执行一次即被清空。退料/下单结束走无谓词版全清，不残留。
        VirtualPatternBufferSlotState.stripVirtualTargets(itemInventory,
                PatternNotConsumableFilter::isKeyNotConsumableForActiveRecipe);
    }

    @Unique
    private void gtShanhai$stripVirtualFluids() {
        VirtualPatternBufferSlotState.removeVirtualTargets(fluidInventory, gtShanhai$getFluidCatalystInventory());
        VirtualPatternBufferSlotState.stripVirtualTargets(fluidInventory,
                PatternNotConsumableFilter::isKeyNotConsumableForActiveRecipe);
    }

    /**
     * 收尾清理：若这个槽位的真实消耗料已全部耗尽（item/fluid 两仓减去虚拟目标后都为 0，即只剩催化剂
     * 虚拟凑数），说明这一单已经做完，把常驻的催化剂虚拟目标也一并清空——否则催化剂会永久残留、
     * 让 isActive() 恒真。执行期间只要还有一份真实消耗料没用完，就不会触发，催化剂照常保留在场。
     */
    @Unique
    private void gtShanhai$clearCatalystsIfDepleted() {
        if (gtShanhai$inventoryHasNoRealStock(itemInventory) && gtShanhai$inventoryHasNoRealStock(fluidInventory)) {
            gtShanhai$stripVirtualTargetsFromCatalyst();
            gtShanhai$clearVirtualCircuitCache();
            VirtualPatternBufferSlotState.stripVirtualTargets(itemInventory);
            VirtualPatternBufferSlotState.stripVirtualTargets(fluidInventory);
        }
    }

    @Unique
    private void gtShanhai$clearVirtualCircuitCache() {
        int config = VirtualPatternBufferSlotState.getVirtualCircuit(itemInventory);
        if (config < 0
                && !gtShanhai$hasVirtualCircuitTarget()) return;
        if (config >= 0) {
            gtShanhai$removeVirtualCircuitPresence(config);
        }
        SlotCacheManager cacheManager = getCacheManager();
        if (cacheManager instanceof SlotCacheManagerAccessor accessor) {
            accessor.gtShanhai$setCircuitCacheRaw(-1);
            accessor.gtShanhai$setCircuitStackRaw(ItemStack.EMPTY);
        }
        VirtualPatternBufferSlotState.clearVirtualCircuit(itemInventory);
    }

    @Unique
    private void gtShanhai$removeVirtualCircuitPresence(int config) {
        if (config < 0 || config > IntCircuitBehaviour.CIRCUIT_MAX) return;
        AEItemKey circuitKey = AEItemKey.of(IntCircuitBehaviour.stack(config));
        gtShanhai$subtractAmount(itemInventory, circuitKey, 1L);
        gtShanhai$subtractAmount(gtShanhai$getItemCatalystInventory(), circuitKey, 1L);
    }

    @Unique
    private static <T> void gtShanhai$subtractAmount(Object2LongMap<T> inventory, T key, long amount) {
        if (inventory == null || key == null || amount <= 0L) return;
        long remaining = inventory.getLong(key) - amount;
        if (remaining > 0L) {
            inventory.put(key, remaining);
        } else {
            inventory.removeLong(key);
        }
    }

    @Unique
    private boolean gtShanhai$hasVirtualCircuitTarget() {
        for (AEItemKey key : VirtualPatternBufferSlotState.getVirtualTargets(itemInventory).keySet()) {
            if (key != null && IntCircuitBehaviour.isIntegratedCircuit(key.toStack())) return true;
        }
        return false;
    }

    @Unique
    private <T extends AEKey> boolean gtShanhai$inventoryHasNoRealStock(Object2LongOpenHashMap<T> inventory) {
        if (inventory.isEmpty()) return true;
        Object2LongMap<T> targets = VirtualPatternBufferSlotState.getVirtualTargets(inventory);
        for (Object2LongMap.Entry<T> entry : inventory.object2LongEntrySet()) {
            long real = entry.getLongValue() - targets.getLong(entry.getKey());
            if (real > 0) return false; // 尚有真实消耗料，这一单没做完，保留催化剂
        }
        return true;
    }

    /**
     * GTLCore 1.2.3.2 只認自己的 virtualItemSupply。山海的在場目標不登記進去時，
     * 普通樣板總成的配方搜索看不到這些物品。
     */
    @Unique
    private void gtShanhai$rememberVirtualItem(AEItemKey key) {
        if (key != null && virtualItemSupply != null) {
            virtualItemSupply.add(key);
        }
    }

    @Unique
    private void gtShanhai$rememberVirtualFluid(AEFluidKey key) {
        if (key != null && virtualFluidSupply != null) {
            virtualFluidSupply.add(key);
        }
    }

    /**
     * 虛擬在場可以蓋住消耗輸入，但不能因此讓整張配方失敗。
     * 只有「不是山海在場目標」的虛擬供應才維持 GTLCore 的拒絕。
     */
    @Inject(method = "testVirtualItemInternal", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$presenceDoesNotFailItemRecipe(GTRecipe recipe, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        if (gtShanhai$consumedItemsArePresence(recipe)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "testVirtualFluidInternal", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$presenceDoesNotFailFluidRecipe(GTRecipe recipe, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        if (gtShanhai$consumedFluidsArePresence(recipe)) {
            cir.setReturnValue(true);
        }
    }

    @ModifyVariable(method = "handleItemInternal", at = @At("HEAD"), argsOnly = true, remap = false)
    private Object2LongMap<Ingredient> gtShanhai$exemptPresenceItems(Object2LongMap<Ingredient> left) {
        gtShanhai$removePresenceItems(left);
        return left;
    }

    @ModifyVariable(method = "handleFluidInternal", at = @At("HEAD"), argsOnly = true, remap = false)
    private Object2LongMap<FluidIngredient> gtShanhai$exemptPresenceFluids(Object2LongMap<FluidIngredient> left) {
        gtShanhai$removePresenceFluids(left);
        return left;
    }

    @Unique
    private boolean gtShanhai$consumedItemsArePresence(GTRecipe recipe) {
        if (recipe == null) return false;
        Object2LongMap<AEItemKey> targets = VirtualPatternBufferSlotState.getVirtualTargets(itemInventory);
        for (Content content : recipe.getInputContents(ItemRecipeCapability.CAP)) {
            if (content == null || content.chance <= 0 || !(content.getContent() instanceof Ingredient ingredient)) {
                continue;
            }
            for (ItemStack stack : ingredient.getItems()) {
                if (stack == null || stack.isEmpty()) continue;
                AEItemKey key = AEItemKey.of(stack);
                if (key == null || !gtShanhai$suppliesItem(key)) continue;
                if (!targets.containsKey(key)) return false;
            }
        }
        return true;
    }

    @Unique
    private boolean gtShanhai$consumedFluidsArePresence(GTRecipe recipe) {
        if (recipe == null) return false;
        Object2LongMap<AEFluidKey> targets = VirtualPatternBufferSlotState.getVirtualTargets(fluidInventory);
        for (Content content : recipe.getInputContents(FluidRecipeCapability.CAP)) {
            if (content == null || content.chance <= 0
                    || !(content.getContent() instanceof FluidIngredient ingredient)) {
                continue;
            }
            for (FluidStack stack : ingredient.getStacks()) {
                if (stack == null || stack.isEmpty()) continue;
                AEFluidKey key = AEFluidKey.of(stack.getFluid());
                if (key == null || !gtShanhai$suppliesFluid(key)) continue;
                if (!targets.containsKey(key)) return false;
            }
        }
        return true;
    }

    @Unique
    private boolean gtShanhai$suppliesItem(AEItemKey key) {
        return virtualItemSupply != null && virtualItemSupply.contains(key)
                || configuredVirtualItems != null && configuredVirtualItems.contains(key);
    }

    @Unique
    private boolean gtShanhai$suppliesFluid(AEFluidKey key) {
        return virtualFluidSupply != null && virtualFluidSupply.contains(key)
                || configuredVirtualFluids != null && configuredVirtualFluids.contains(key);
    }

    @Unique
    private void gtShanhai$removePresenceItems(Object2LongMap<Ingredient> left) {
        if (left == null || left.isEmpty()) return;
        Object2LongMap<AEItemKey> targets = VirtualPatternBufferSlotState.getVirtualTargets(itemInventory);
        if (targets.isEmpty()) return;
        List<Ingredient> covered = null;
        for (Object2LongMap.Entry<Ingredient> entry : left.object2LongEntrySet()) {
            Ingredient ingredient = entry.getKey();
            if (gtShanhai$ingredientIsPresence(ingredient, targets)) {
                if (covered == null) covered = new ArrayList<>();
                covered.add(ingredient);
            }
        }
        if (covered == null) return;
        for (int i = 0; i < covered.size(); i++) {
            left.removeLong(covered.get(i));
        }
    }

    @Unique
    private void gtShanhai$removePresenceFluids(Object2LongMap<FluidIngredient> left) {
        if (left == null || left.isEmpty()) return;
        Object2LongMap<AEFluidKey> targets = VirtualPatternBufferSlotState.getVirtualTargets(fluidInventory);
        if (targets.isEmpty()) return;
        List<FluidIngredient> covered = null;
        for (Object2LongMap.Entry<FluidIngredient> entry : left.object2LongEntrySet()) {
            FluidIngredient ingredient = entry.getKey();
            if (gtShanhai$fluidIsPresence(ingredient, targets)) {
                if (covered == null) covered = new ArrayList<>();
                covered.add(ingredient);
            }
        }
        if (covered == null) return;
        for (int i = 0; i < covered.size(); i++) {
            left.removeLong(covered.get(i));
        }
    }

    @Unique
    private static boolean gtShanhai$ingredientIsPresence(Ingredient ingredient, Object2LongMap<AEItemKey> targets) {
        if (ingredient == null) return false;
        for (AEItemKey target : targets.keySet()) {
            if (target != null && target.matches(ingredient)) return true;
        }
        return false;
    }

    @Unique
    private static boolean gtShanhai$fluidIsPresence(FluidIngredient ingredient, Object2LongMap<AEFluidKey> targets) {
        if (ingredient == null) return false;
        for (FluidStack stack : ingredient.getStacks()) {
            if (stack == null || stack.isEmpty()) continue;
            AEFluidKey key = AEFluidKey.of(stack.getFluid());
            if (key != null && targets.containsKey(key)) return true;
        }
        return false;
    }

    @Shadow
    public Object2LongMap<AEItemKey> getItemCatalystInventory() {
        throw new AssertionError();
    }

    @Shadow
    public Object2LongMap<AEFluidKey> getFluidCatalystInventory() {
        throw new AssertionError();
    }

    @Shadow
    public SlotCacheManager getCacheManager() {
        throw new AssertionError();
    }

    @Shadow
    public void add(AEKey what, long amount) {
        throw new AssertionError();
    }

    @Shadow
    public Runnable getOnContentsChanged() {
        throw new AssertionError();
    }

    // ===== 槽位成员桥接实现：机器级 mixin 经此访问，取代反射 =====

    @Override
    public Object2LongOpenHashMap<AEItemKey> gtShanhai$itemInventory() {
        return itemInventory;
    }

    @Override
    public Object2LongOpenHashMap<AEFluidKey> gtShanhai$fluidInventory() {
        return fluidInventory;
    }

    @Override
    public Object2LongMap<AEItemKey> gtShanhai$itemCatalystInventory() {
        return getItemCatalystInventory();
    }

    @Override
    public Object2LongMap<AEFluidKey> gtShanhai$fluidCatalystInventory() {
        return getFluidCatalystInventory();
    }

    @Override
    public SlotCacheManager gtShanhai$cacheManager() {
        return getCacheManager();
    }

    @Override
    public void gtShanhai$add(AEKey what, long amount) {
        add(what, amount);
    }

    @Override
    public void gtShanhai$notifyContentsChanged() {
        Runnable callback = getOnContentsChanged();
        if (callback != null) callback.run();
    }

    @Unique
    private Object2LongMap<AEItemKey> gtShanhai$getItemCatalystInventory() {
        return getItemCatalystInventory();
    }

    @Unique
    private Object2LongMap<AEFluidKey> gtShanhai$getFluidCatalystInventory() {
        return getFluidCatalystInventory();
    }
}
