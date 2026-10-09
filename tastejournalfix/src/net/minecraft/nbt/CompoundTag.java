package net.minecraft.nbt;

/**
 * 【仅编译期桩类 —— 不要打进 jar】运行时由 Minecraft 提供。
 * 这里只声明本修复用到的三个方法（javap 实测均存在）。
 */
public class CompoundTag {

    public boolean contains(String key) {
        throw new UnsupportedOperationException("stub");
    }

    public void remove(String key) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean isEmpty() {
        throw new UnsupportedOperationException("stub");
    }
}
