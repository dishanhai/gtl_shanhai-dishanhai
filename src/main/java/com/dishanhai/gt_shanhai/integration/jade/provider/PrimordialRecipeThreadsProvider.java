package com.dishanhai.gt_shanhai.integration.jade.provider;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadedRecipeLogic;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import org.gtlcore.gtlcore.utils.NumberUtils;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.IElement;

/** 山海：將在途批次獨立顯示於 Jade 左側，保留原生機器詳情框。 */
public enum PrimordialRecipeThreadsProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "primordial_recipe_threads");
    private static final ResourceLocation THREAD_ELEMENT_UID = new ResourceLocation("gt_shanhai", "primordial_thread_element");
    private static final String DATA = "sh_primordial_threads_display";
    private static final int PAGE_SIZE = 3;

    public static String getDataKey() { return DATA; }

    @Override public ResourceLocation getUid() { return UID; }
    @Override public int getDefaultPriority() { return 4999; }
    public ResourceLocation getThreadElementUid() { return THREAD_ELEMENT_UID; }

    @Override public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity holder)
                || !(holder.getMetaMachine() instanceof IRecipeLogicMachine machine)
                || !(machine.getRecipeLogic() instanceof PrimordialThreadedRecipeLogic logic)) return;
        int count = logic.getActiveRecipeThreadCount();
        int pages = Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = (int) Math.floorMod(machine.self().getOffsetTimer() / 100L, (long) pages);
        CompoundTag view = logic.getRecipeThreadDisplay(page * PAGE_SIZE, PAGE_SIZE);
        view.putInt("page", page + 1);
        view.putInt("pages", pages);
        data.put(DATA, view);
    }

    @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!accessor.getServerData().contains(DATA)) return;
        CompoundTag data = accessor.getServerData().getCompound(DATA);
        var helper = tooltip.getElementHelper();
        addThreadText(tooltip, Component.translatable("gt_shanhai.primordial.threads.panel_title")
                .withStyle(ChatFormatting.AQUA));
        addThreadText(tooltip, Component.translatable("gt_shanhai.primordial.threads.summary", data.getInt("active"),
                capacityText(data.getLong("capacity"))).withStyle(ChatFormatting.AQUA));
        addThreadText(tooltip, Component.translatable("gt_shanhai.primordial.threads.activity",
                data.getInt("running"), data.getInt("waiting"))
                .withStyle(ChatFormatting.GRAY));
        addThreadText(tooltip, Component.translatable("gt_shanhai.primordial.threads.parallel",
                NumberUtils.formatLong(data.getLong("reserved")), capacityText(data.getLong("parallelCapacity")))
                .withStyle(ChatFormatting.GRAY));
        addThreadText(tooltip, Component.translatable("gt_shanhai.primordial.threads.total_consumption",
                data.getString("consumption")).withStyle(ChatFormatting.GOLD));
        addThreadText(tooltip, Component.translatable("gt_shanhai.primordial.threads.total_production",
                data.getString("production")).withStyle(ChatFormatting.GOLD));
        if (!data.getBoolean("enabled")) {
            addThreadText(tooltip, Component.translatable("gt_shanhai.primordial.threads.suspended")
                    .withStyle(ChatFormatting.RED));
        }
        if (data.getBoolean("draining")) {
            addThreadText(tooltip, Component.translatable("gt_shanhai.primordial.threads.draining")
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (data.getInt("active") == 0) {
            addThreadText(tooltip, Component.translatable("gt_shanhai.primordial.threads.empty")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (data.getInt("pages") > 1) {
            addThreadText(tooltip, Component.translatable("gt_shanhai.primordial.threads.page", data.getInt("page"),
                    data.getInt("pages")).withStyle(ChatFormatting.DARK_GRAY));
        }
        boolean firstThread = true;
        for (var element : data.getList("threads", 10)) {
            if (firstThread) tooltip.add(helper.spacer(0, 4).tag(THREAD_ELEMENT_UID));
            else tooltip.add(PrimordialRecipeThreadText.separator(helper.spacer(0, 7)).tag(THREAD_ELEMENT_UID));
            firstThread = false;
            CompoundTag row = (CompoundTag) element;
            int progress = row.getInt("progress"), duration = row.getInt("duration");
            boolean working = "WORKING".equals(row.getString("status"));
            Component status = Component.translatable("gt_shanhai.primordial.threads.status."
                    + row.getString("status").toLowerCase(java.util.Locale.ROOT));
            Component label = threadColor(row, Component.translatable("gt_shanhai.primordial.threads.lane",
                    row.getLong("index") + 1, status));
            IElement progressBar = helper.progress(duration <= 0 ? 0 : Math.min(1F, (float) progress / duration), label,
                    helper.progressStyle().color(working ? 0xFF3D9B8F : 0xFF99764C).textColor(0xFFFFFFFF),
                    BoxStyle.DEFAULT, true).tag(THREAD_ELEMENT_UID);
            tooltip.add(progressBar);
            appendThreadOutputs(tooltip, row);
            float fraction = duration <= 0 ? 0 : Math.max(0, Math.min(1F, (float) progress / duration));
            addThreadDetail(tooltip, row, Component.translatable("gt_shanhai.primordial.threads.timing",
                    String.format(java.util.Locale.ROOT, "%.1f", fraction * 100), progress, duration,
                    String.format(java.util.Locale.ROOT, "%.1f", row.getInt("remainingTicks") / 20.0)));
            var recipes = row.getList("recipes", 10);
            if (recipes.isEmpty()) {
                addThreadDetail(tooltip, row, Component.translatable("gt_shanhai.primordial.threads.unknown_recipe"));
            }
            for (var source : recipes) {
                CompoundTag identity = (CompoundTag) source;
                String typeId = identity.getString("recipeType");
                ResourceLocation recipeType = typeId.isBlank() ? null : ResourceLocation.tryParse(typeId);
                if (recipeType != null) addThreadDetail(tooltip, row,
                        Component.translatable("gt_shanhai.primordial.threads.recipe",
                                Component.translatable(recipeType.toLanguageKey())));
                addThreadDetail(tooltip, row, Component.translatable("gt_shanhai.primordial.threads.recipe_id",
                        identity.getString("recipeId")));
            }
            if (row.getInt("moreRecipes") > 0) {
                addThreadDetail(tooltip, row, Component.translatable("gt_shanhai.primordial.threads.more_recipes",
                        row.getInt("moreRecipes")));
            }
            addThreadDetail(tooltip, row, Component.translatable("gt_shanhai.primordial.threads.batch",
                    NumberUtils.formatLong(row.getLong("reserved"))));
            addThreadDetail(tooltip, row, Component.translatable("gt_shanhai.primordial.threads.batch_energy",
                    row.getString("consumption"), row.getString("production")));
            if (row.contains("reason")) {
                Component reason = Component.Serializer.fromJson(row.getString("reason"));
                if (reason != null) addThreadText(tooltip, reason.copy().withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    private static void appendThreadOutputs(ITooltip tooltip, CompoundTag row) {
        var helper = tooltip.getElementHelper();
        for (var outputElement : row.getList("outputs", 10)) {
            CompoundTag output = (CompoundTag) outputElement;
            Component name;
            IElement icon = null;
            if (output.contains("item")) {
                ItemStack stack = ItemStack.of(output.getCompound("item"));
                icon = helper.smallItem(stack);
                name = stack.getHoverName();
            } else {
                FluidStack stack = FluidStack.loadFromTag(output.getCompound("fluid"));
                name = stack.getDisplayName();
            }
            Component line = threadColor(row, Component.translatable(output.contains("item")
                            ? "gt_shanhai.primordial.threads.item_output" : "gt_shanhai.primordial.threads.fluid_output",
                    NumberUtils.formatLong(output.getLong("amount")), name));
            tooltip.add(new PrimordialRecipeThreadText(line, helper.text(line), icon).tag(THREAD_ELEMENT_UID));
        }
        if (row.getInt("moreOutputs") > 0) {
            addThreadDetail(tooltip, row, Component.translatable("gt_shanhai.primordial.threads.more_outputs",
                    row.getInt("moreOutputs")));
        }
    }

    private static void addThreadText(ITooltip tooltip, Component text) {
        tooltip.add(new PrimordialRecipeThreadText(text, tooltip.getElementHelper().text(text), null)
                .tag(THREAD_ELEMENT_UID));
    }

    private static Component threadColor(CompoundTag row, Component text) {
        String key = "gt_shanhai.primordial.threads.thread_color." + Math.floorMod(row.getLong("index"), 6);
        var result = Component.translatable(key, text);
        // 將語言檔的 § 色碼同步為繼承樣式，讓 %s 裏的配方名稱等子元件也有相同顏色。
        String format = Component.translatable(key, "").getString();
        if (format.length() >= 2 && format.charAt(0) == '\u00a7') {
            ChatFormatting color = ChatFormatting.getByCode(format.charAt(1));
            if (color != null && color.isColor()) result.withStyle(color);
        }
        return result;
    }

    private static void addThreadDetail(ITooltip tooltip, CompoundTag row, Component text) {
        addThreadText(tooltip, threadColor(row, text));
    }

    private static Object capacityText(long capacity) {
        return capacity == Long.MAX_VALUE ? Component.translatable("gt_shanhai.primordial.threads.unlimited")
                : NumberUtils.formatLong(capacity);
    }
}
