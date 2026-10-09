package net.minecraft.world.item.component;

import net.minecraft.nbt.CompoundTag;

/** 测试用假实现（有真实行为） */
public final class CustomData {

    public static final CustomData EMPTY = new CustomData(new CompoundTag());

    private final CompoundTag tag;

    private CustomData(CompoundTag tag) {
        this.tag = tag;
    }

    public static CustomData of(CompoundTag tag) {
        return new CustomData(tag.copy());
    }

    public boolean isEmpty() {
        return this.tag.isEmpty();
    }

    public boolean contains(String key) {
        return this.tag.contains(key);
    }

    /** 真实实现返回副本：改副本不影响原栈，直到 set() 写回 */
    public CompoundTag copyTag() {
        return this.tag.copy();
    }

    @Override
    public String toString() {
        return "CustomData" + this.tag;
    }
}
