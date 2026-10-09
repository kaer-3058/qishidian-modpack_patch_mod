package net.minecraft.world.item.component;

import net.minecraft.nbt.CompoundTag;

/**
 * 【仅编译期桩类 —— 不要打进 jar】运行时由 Minecraft 提供。
 * javap 实测存在：public static final CustomData EMPTY; / public static CustomData of(CompoundTag);
 *                  public boolean isEmpty(); / public CompoundTag copyTag();
 */
public final class CustomData {

    public static final CustomData EMPTY = null;

    public static CustomData of(CompoundTag tag) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean isEmpty() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean contains(String key) {
        throw new UnsupportedOperationException("stub");
    }

    public CompoundTag copyTag() {
        throw new UnsupportedOperationException("stub");
    }
}
