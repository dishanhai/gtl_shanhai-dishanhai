package com.dishanhai.gt_shanhai.common.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.EncodedPatternItem;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.common.data.GTItems;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 編碼樣板根 NBT {@value #TAG} 為 true 時，這張樣板除主產物外的產出也可以被終端下單。
 * AE 的合成索引原本只登記 {@link IPatternDetails#getPrimaryOutput()}。
 */
public final class PatternByproductOrder {

    public static final String TAG = "gt_shanhai_order_byproducts";

    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static volatile Access access;
    private static volatile boolean unavailable;

    private PatternByproductOrder() {}

    public static boolean isMarked(CompoundTag tag) {
        return tag != null && tag.getBoolean(TAG);
    }

    public static boolean isMarked(ItemStack stack) {
        return stack != null && !stack.isEmpty() && isMarked(stack.getTag());
    }

    public static boolean isMarked(IPatternDetails pattern) {
        if (pattern == null) return false;
        AEItemKey definition = pattern.getDefinition();
        return definition != null && isMarked(definition.getTag());
    }

    public static boolean setMarked(ItemStack stack, boolean marked) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof EncodedPatternItem)) {
            return false;
        }
        if (marked) {
            stack.getOrCreateTag().putBoolean(TAG, true);
            return true;
        }
        CompoundTag tag = stack.getTag();
        if (tag != null) tag.remove(TAG);
        return true;
    }

    public static void appendTooltip(ItemStack stack, List<Component> lines) {
        if (stack == null || lines == null || !(stack.getItem() instanceof EncodedPatternItem)) return;
        boolean marked = isMarked(stack);
        String key = marked
                ? "gt_shanhai.tooltip.pattern_byproduct_order.enabled"
                : "gt_shanhai.tooltip.pattern_byproduct_order.disabled";
        for (int i = 0; i < lines.size(); i++) {
            ComponentContents contents = lines.get(i).getContents();
            if (contents instanceof TranslatableContents && key.equals(((TranslatableContents) contents).getKey())) {
                return;
            }
        }
        lines.add(Component.translatable(key).withStyle(marked ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    /**
     * 樣板總成在 {@code keepByProduct=false} 時會把網路樣板重編碼成只剩主產物，根標籤一併丟掉。
     * 有標識時改回保留全部產出，並把原樣板根標籤抄回去。
     */
    public static IPatternDetails withByproductOutputs(AEProcessingPattern pattern, Level level) {
        if (pattern == null || level == null || !isMarked(pattern)) return null;
        GenericStack[] originalInputs = pattern.getSparseInputs();
        GenericStack[] originalOutputs = pattern.getSparseOutputs();
        if (originalOutputs == null || originalOutputs.length <= 1) return null;
        ArrayList<GenericStack> inputs = withoutCircuit(originalInputs);
        if (inputs.isEmpty()) inputs = nonNull(originalInputs);
        try {
            ItemStack encoded = PatternDetailsHelper.encodeProcessingPattern(
                    inputs.toArray(new GenericStack[0]), originalOutputs);
            copyExtraTags(pattern.getDefinition() == null ? null : pattern.getDefinition().getTag(), encoded);
            setMarked(encoded, true);
            return PatternDetailsHelper.decodePattern(encoded, level);
        } catch (RuntimeException exception) {
            logFailure(exception);
            return null;
        }
    }

    public static int toggleCommand(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("gt_shanhai.message.pattern_byproduct_order.need_player"));
            return 0;
        }
        ItemStack stack = heldPattern(player);
        if (stack.isEmpty()) {
            source.sendFailure(Component.translatable("gt_shanhai.message.pattern_byproduct_order.need_pattern"));
            return 0;
        }
        boolean marked = !isMarked(stack);
        setMarked(stack, marked);
        if (player.containerMenu != null && player.containerMenu.getCarried() == stack) {
            player.containerMenu.setCarried(stack);
        }
        player.getInventory().setChanged();
        source.sendSuccess(() -> Component.translatable(marked
                ? "gt_shanhai.message.pattern_byproduct_order.on"
                : "gt_shanhai.message.pattern_byproduct_order.off"), false);
        return 1;
    }

    /**
     * 在 AE 已經登記主產物之後，把帶標識樣板的其餘產出掛到同一個樣板物件上。
     * {@code providerState} 必須是當前 {@code ProviderState}。
     */
    public static void index(Object methods, List<IPatternDetails> patterns, Object providerState, boolean mount) {
        if (methods == null || providerState == null || patterns == null || patterns.isEmpty()) return;
        Access resolved = access();
        if (resolved == null) return;
        try {
            for (int i = 0; i < patterns.size(); i++) {
                List<AEKey> keys = extraKeys(patterns.get(i));
                if (keys.isEmpty()) continue;
                Object info = resolved.newInfo(patterns.get(i), providerState);
                for (int k = 0; k < keys.size(); k++) {
                    resolved.link(methods, keys.get(k), info, mount);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logFailure(exception);
        }
    }

    static List<AEKey> extraKeys(IPatternDetails pattern) {
        if (!isMarked(pattern)) return List.of();
        GenericStack[] outputs = pattern.getOutputs();
        if (outputs == null || outputs.length <= 1) return List.of();
        GenericStack primary = pattern.getPrimaryOutput();
        if (primary == null || primary.what() == null) return List.of();
        AEKey primaryKey = primary.what();
        ArrayList<AEKey> keys = null;
        for (int i = 0; i < outputs.length; i++) {
            GenericStack output = outputs[i];
            if (output == null || output.what() == null || output.amount() <= 0L) continue;
            AEKey key = output.what();
            if (key.equals(primaryKey)) continue;
            if (keys == null) keys = new ArrayList<>();
            if (!keys.contains(key)) keys.add(key);
        }
        return keys == null ? List.of() : keys;
    }

    private static ArrayList<GenericStack> withoutCircuit(GenericStack[] inputs) {
        ArrayList<GenericStack> filtered = new ArrayList<>();
        if (inputs == null) return filtered;
        for (int i = 0; i < inputs.length; i++) {
            GenericStack input = inputs[i];
            if (input == null || input.what() == null) continue;
            AEKey what = input.what();
            if (what instanceof AEItemKey && ((AEItemKey) what).getItem() == GTItems.INTEGRATED_CIRCUIT.get()) {
                continue;
            }
            filtered.add(input);
        }
        return filtered;
    }

    private static ArrayList<GenericStack> nonNull(GenericStack[] inputs) {
        ArrayList<GenericStack> filtered = new ArrayList<>();
        if (inputs == null) return filtered;
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i] != null) filtered.add(inputs[i]);
        }
        return filtered;
    }

    private static void copyExtraTags(CompoundTag original, ItemStack encoded) {
        if (original == null || encoded == null || encoded.isEmpty()) return;
        CompoundTag tag = encoded.getOrCreateTag();
        for (String key : original.getAllKeys()) {
            if ("in".equals(key) || "out".equals(key) || tag.contains(key)) continue;
            Tag value = original.get(key);
            if (value != null) tag.put(key, value.copy());
        }
    }

    private static ItemStack heldPattern(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof EncodedPatternItem) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof EncodedPatternItem) return off;
        if (player.containerMenu != null) {
            ItemStack carried = player.containerMenu.getCarried();
            if (carried.getItem() instanceof EncodedPatternItem) return carried;
        }
        return ItemStack.EMPTY;
    }

    private static Access access() {
        if (unavailable) return null;
        Access local = access;
        if (local != null) return local;
        synchronized (PatternByproductOrder.class) {
            if (access != null) return access;
            if (unavailable) return null;
            try {
                access = new Access();
                return access;
            } catch (ReflectiveOperationException exception) {
                unavailable = true;
                logFailure(exception);
                return null;
            }
        }
    }

    private static void logFailure(Throwable exception) {
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            GTDishanhaiMod.LOGGER.error("[样板副产物] 无法登记副产物下单索引", exception);
        }
    }

    private static final class Access {
        private final Field craftableItems;
        private final Field craftableItemsList;
        private final Field patternSet;
        private final Field needsSorting;
        private final Constructor<?> patternsForKeyCtor;
        private final Constructor<?> patternInfoCtor;

        private Access() throws ReflectiveOperationException {
            Class<?> providers = Class.forName("appeng.me.service.helpers.NetworkCraftingProviders");
            Class<?> forKey = Class.forName(providers.getName() + "$PatternsForKey");
            Class<?> info = Class.forName(providers.getName() + "$PatternInfo");
            Class<?> state = Class.forName(providers.getName() + "$ProviderState");
            this.craftableItems = providers.getDeclaredField("craftableItems");
            this.craftableItemsList = providers.getDeclaredField("craftableItemsList");
            this.patternSet = forKey.getDeclaredField("patterns");
            this.needsSorting = forKey.getDeclaredField("needsSorting");
            this.patternsForKeyCtor = forKey.getDeclaredConstructor();
            this.patternInfoCtor = info.getDeclaredConstructor(IPatternDetails.class, state);
            this.craftableItems.setAccessible(true);
            this.craftableItemsList.setAccessible(true);
            this.patternSet.setAccessible(true);
            this.needsSorting.setAccessible(true);
            this.patternsForKeyCtor.setAccessible(true);
            this.patternInfoCtor.setAccessible(true);
        }

        private Object newInfo(IPatternDetails pattern, Object providerState) throws ReflectiveOperationException {
            return this.patternInfoCtor.newInstance(pattern, providerState);
        }

        @SuppressWarnings("unchecked")
        private void link(Object methods, AEKey key, Object info, boolean mount) throws ReflectiveOperationException {
            Map<AEKey, Object> items = (Map<AEKey, Object>) this.craftableItems.get(methods);
            KeyCounter list = (KeyCounter) this.craftableItemsList.get(methods);
            if (mount) {
                Object bucket = items.get(key);
                if (bucket == null) {
                    bucket = this.patternsForKeyCtor.newInstance();
                    items.put(key, bucket);
                }
                if (patterns(bucket).add(info)) {
                    list.add(key, 1L);
                    this.needsSorting.setBoolean(bucket, true);
                }
                return;
            }
            Object bucket = items.get(key);
            if (bucket == null) return;
            Set<Object> patterns = patterns(bucket);
            if (!patterns.remove(info)) return;
            list.remove(key, 1L);
            this.needsSorting.setBoolean(bucket, true);
            if (patterns.isEmpty()) items.remove(key, bucket);
        }

        @SuppressWarnings("unchecked")
        private Set<Object> patterns(Object bucket) throws ReflectiveOperationException {
            Set<Object> patterns = (Set<Object>) this.patternSet.get(bucket);
            return patterns == null ? Collections.emptySet() : patterns;
        }
    }
}
