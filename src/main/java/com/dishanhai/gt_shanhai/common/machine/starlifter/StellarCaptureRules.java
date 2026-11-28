package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID)
public final class StellarCaptureRules extends SimplePreparableReloadListener<StellarCaptureRules.Rules> {
    private static final ResourceLocation FILE = new ResourceLocation(GTDishanhaiMod.MOD_ID, "star_lifter/capture.json");
    private static volatile Rules current = new Rules(144_000, Map.of());

    record Rules(long workTicks, Map<Item, Integer> items) {}

    static Rules current() {
        return current;
    }

    @SubscribeEvent
    public static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new StellarCaptureRules());
    }

    @Override
    protected Rules prepare(ResourceManager manager, ProfilerFiller profiler) {
        try (var reader = manager.openAsReader(FILE)) {
            return parse(GsonHelper.convertToJsonObject(JsonParser.parseReader(reader), "capture"));
        } catch (IOException | JsonSyntaxException failure) {
            throw new IllegalStateException("Invalid Star Lifter capture rules at " + FILE, failure);
        }
    }

    static Rules parse(JsonObject json) {
        long ticks = positiveInteger(json, "work_ticks", Integer.MAX_VALUE);
        Map<Item, Integer> items = new LinkedHashMap<>();
        for (var entry : GsonHelper.getAsJsonArray(json, "reactivation_items")) {
            var object = GsonHelper.convertToJsonObject(entry, "reactivation_items");
            var id = ResourceLocation.tryParse(GsonHelper.getAsString(object, "item"));
            var item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            int count = positiveInteger(object, "count", 2304);
            if (item == Items.AIR || items.putIfAbsent(item, count) != null) {
                throw new JsonSyntaxException("Invalid or duplicate reactivation item: " + id);
            }
        }
        if (items.isEmpty() || items.size() > 8) throw new JsonSyntaxException("Expected 1..8 reactivation items");
        int slots = items.entrySet().stream()
                .mapToInt(entry -> (entry.getValue() + entry.getKey().getMaxStackSize() - 1) / entry.getKey().getMaxStackSize())
                .sum();
        if (slots > 36) throw new JsonSyntaxException("Reactivation items must fit in the main player inventory");
        return new Rules(ticks, java.util.Collections.unmodifiableMap(items));
    }

    private static int positiveInteger(JsonObject json, String key, int max) {
        if (json.get(key) instanceof JsonPrimitive value && value.isNumber()) {
            try {
                int number = value.getAsBigDecimal().intValueExact();
                if (number > 0 && number <= max) return number;
            } catch (ArithmeticException ignored) {
                // Report fractions and overflows with the same field-specific message.
            }
        }
        throw new JsonSyntaxException(key + " must be an integer in 1.." + max);
    }

    @Override
    protected void apply(Rules rules, ResourceManager manager, ProfilerFiller profiler) {
        current = rules;
    }
}
