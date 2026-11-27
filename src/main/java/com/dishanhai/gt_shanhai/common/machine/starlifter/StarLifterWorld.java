package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.StarLifterSyncPacket;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 汇总命令装置和已成型主机，并向客户端同步位置、比例和动画起始 tick。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID)
public final class StarLifterWorld {
    private static final String DATA_NAME = "gt_shanhai_star_lifters";
    private static final Map<ResourceLocation, List<StarLifterInstallation>> CLIENT = new ConcurrentHashMap<>();

    private StarLifterWorld() {}

    public static List<StarLifterInstallation> installations(Level level) {
        return level instanceof ServerLevel server ? data(server).snapshot
                : CLIENT.getOrDefault(level.dimension().location(), List.of());
    }

    public static List<StarLifterInstallation> acceptSnapshot(ResourceLocation dimension, List<StarLifterInstallation> installations) {
        return CLIENT.put(dimension, List.copyOf(installations));
    }

    public static void clearClient() {
        CLIENT.clear();
    }

    public static boolean isReplaced(Level level, BlockPos pos) {
        for (var installation : installations(level)) {
            if (StarLifterStructure.replaces(installation, pos)) return true;
        }
        return false;
    }

    static void activateController(ServerLevel level, BlockPos pos, Vec3 center, float heading, long startTick) {
        var data = data(level);
        var current = data.controllers.get(pos);
        if (current != null && current.heading() == heading) return;
        var installation = new StarLifterInstallation("controller:" + pos.asLong(), center,
                StarLifterInstallation.DEFAULT_RADIUS, heading, startTick);
        data.controllers.put(pos.immutable(), installation);
        data.refreshSnapshot();
        sync(level);
    }

    static void deactivateController(ServerLevel level, BlockPos pos) {
        var data = data(level);
        if (data.controllers.remove(pos) == null) return;
        data.refreshSnapshot();
        sync(level);
    }

    public static List<VoxelShape> collisions(Level level, Entity entity, AABB query) {
        if (entity != null && entity.isSpectator()) return List.of();
        List<VoxelShape> result = null;
        for (StarLifterInstallation installation : installations(level)) {
            if (!installation.bounds().intersects(query)) continue;
            if (result == null) result = new ArrayList<>();
            installation.collision(level.getGameTime()).append(query, result);
        }
        return result == null ? List.of() : result;
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("shanhai").then(Commands.literal("star_lifter")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("install").then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("center", Vec3Argument.vec3())
                                .executes(ctx -> install(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                        Vec3Argument.getVec3(ctx, "center"), StarLifterInstallation.DEFAULT_RADIUS, 0))
                                .then(Commands.argument("radius", FloatArgumentType.floatArg(
                                                StarLifterInstallation.MIN_RADIUS, StarLifterInstallation.MAX_RADIUS))
                                        .executes(ctx -> install(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                                Vec3Argument.getVec3(ctx, "center"), FloatArgumentType.getFloat(ctx, "radius"), 0))
                                        .then(Commands.argument("heading", FloatArgumentType.floatArg(-180, 180))
                                                .executes(ctx -> install(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                                        Vec3Argument.getVec3(ctx, "center"), FloatArgumentType.getFloat(ctx, "radius"),
                                                        FloatArgumentType.getFloat(ctx, "heading"))))))))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                data(ctx.getSource().getLevel()).entries.keySet(), builder))
                        .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))));
    }

    private static int install(CommandSourceStack source, String name, Vec3 center, float radius, float heading) {
        if (name.length() > 64 || !Level.isInSpawnableBounds(BlockPos.containing(center))) {
            source.sendFailure(Component.translatable("gt_shanhai.star_lifter.install.invalid"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        var data = data(level);
        var installation = new StarLifterInstallation(name, center, radius, heading, level.getGameTime());
        if (data.entries.putIfAbsent(name, installation) != null) {
            source.sendFailure(Component.translatable("gt_shanhai.star_lifter.install.exists", name));
            return 0;
        }
        data.changed();
        sync(level);
        source.sendSuccess(() -> Component.translatable("gt_shanhai.star_lifter.install.created", name, radius, heading), true);
        return 1;
    }

    private static int remove(CommandSourceStack source, String name) {
        ServerLevel level = source.getLevel();
        var data = data(level);
        if (data.entries.remove(name) == null) {
            source.sendFailure(Component.translatable("gt_shanhai.star_lifter.install.missing", name));
            return 0;
        }
        data.changed();
        sync(level);
        source.sendSuccess(() -> Component.translatable("gt_shanhai.star_lifter.install.removed", name), true);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        var entries = data(source.getLevel()).entries;
        source.sendSuccess(() -> Component.translatable(entries.isEmpty() ? "gt_shanhai.star_lifter.install.empty"
                : "gt_shanhai.star_lifter.install.list", String.join(", ", entries.keySet())), false);
        return entries.size();
    }

    private static void sync(ServerLevel level) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension), snapshot(level));
    }

    private static StarLifterSyncPacket snapshot(ServerLevel level) {
        return new StarLifterSyncPacket(level.dimension().location(), installations(level));
    }

    @SubscribeEvent
    public static void join(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) send(player);
    }

    @SubscribeEvent
    public static void changeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) send(player);
    }

    private static void send(ServerPlayer player) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), snapshot(player.serverLevel()));
    }

    private static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Data::load, Data::new, DATA_NAME);
    }

    private static final class Data extends SavedData {
        private final Map<String, StarLifterInstallation> entries = new LinkedHashMap<>();
        private final Map<BlockPos, StarLifterInstallation> controllers = new LinkedHashMap<>();
        private List<StarLifterInstallation> snapshot = List.of();

        static Data load(CompoundTag tag) {
            var data = new Data();
            for (Tag entry : tag.getList("Installations", Tag.TAG_COMPOUND)) {
                try {
                    var installation = StarLifterInstallation.load((CompoundTag) entry);
                    data.entries.putIfAbsent(installation.name(), installation);
                } catch (IllegalArgumentException exception) {
                    GTDishanhaiMod.LOGGER.warn("跳过损坏的摘星者装置记录", exception);
                }
            }
            data.refreshSnapshot();
            return data;
        }

        void changed() {
            refreshSnapshot();
            setDirty();
        }

        void refreshSnapshot() {
            var installations = new ArrayList<>(entries.values());
            installations.addAll(controllers.values());
            snapshot = List.copyOf(installations);
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            var list = new ListTag();
            entries.values().forEach(entry -> list.add(entry.save()));
            tag.put("Installations", list);
            return tag;
        }
    }
}
