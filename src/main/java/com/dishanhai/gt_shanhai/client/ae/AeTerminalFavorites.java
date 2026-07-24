package com.dishanhai.gt_shanhai.client.ae;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 客户端全局 AE 终端收藏，按收藏时间保序。 */
public final class AeTerminalFavorites {

    private static final String TAG_FAVORITES = "favorites";
    private static final Path FILE = FMLPaths.CONFIGDIR.get()
            .resolve("gt_shanhai")
            .resolve("ae_terminal_favorites.nbt");
    private static final LinkedHashSet<AEKey> favorites = new LinkedHashSet<>();
    private static boolean loaded;

    private AeTerminalFavorites() {}

    public static boolean contains(AEKey key) {
        ensureLoaded();
        return isSupported(key) && favorites.contains(key);
    }

    public static List<AEKey> getOrderedKeys() {
        ensureLoaded();
        return new ArrayList<>(favorites);
    }

    /** @return 切换后是否处于收藏状态。 */
    public static boolean toggle(AEKey key) {
        ensureLoaded();
        if (!isSupported(key)) return false;
        boolean added;
        if (favorites.remove(key)) {
            added = false;
        } else {
            favorites.add(key);
            added = true;
        }
        save();
        return added;
    }

    public static boolean isSupported(AEKey key) {
        return key instanceof AEItemKey || key instanceof AEFluidKey;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(FILE)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(FILE.toFile());
            ListTag list = root.getList(TAG_FAVORITES, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                try {
                    AEKey key = AEKey.fromTagGeneric(list.getCompound(i));
                    if (isSupported(key)) favorites.add(key);
                } catch (RuntimeException e) {
                    GTDishanhaiMod.LOGGER.warn("[AE终端收藏] 跳过无法解析的第 {} 条记录", i, e);
                }
            }
        } catch (IOException | RuntimeException e) {
            favorites.clear();
            GTDishanhaiMod.LOGGER.warn("[AE终端收藏] 读取失败: {}", FILE, e);
        }
    }

    private static void save() {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (AEKey key : favorites) {
            try {
                list.add(key.toTagGeneric());
            } catch (RuntimeException e) {
                GTDishanhaiMod.LOGGER.warn("[AE终端收藏] 跳过无法保存的收藏: {}", key, e);
            }
        }
        root.put(TAG_FAVORITES, list);

        Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        try {
            Files.createDirectories(FILE.getParent());
            NbtIo.writeCompressed(root, temp.toFile());
            try {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            GTDishanhaiMod.LOGGER.warn("[AE终端收藏] 保存失败: {}", FILE, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }
}
