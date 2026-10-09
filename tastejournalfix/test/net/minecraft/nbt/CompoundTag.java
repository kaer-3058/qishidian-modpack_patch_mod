package net.minecraft.nbt;

import java.util.LinkedHashMap;
import java.util.Map;

/** 测试用假实现（有真实行为） */
public class CompoundTag {

    private final Map<String, String> values = new LinkedHashMap<>();

    public CompoundTag put(String key, String value) {
        this.values.put(key, value);
        return this;
    }

    public boolean contains(String key) {
        return this.values.containsKey(key);
    }

    public boolean contains(String key, int type) {
        return this.values.containsKey(key);
    }

    public void remove(String key) {
        this.values.remove(key);
    }

    public boolean isEmpty() {
        return this.values.isEmpty();
    }

    public CompoundTag copy() {
        CompoundTag copy = new CompoundTag();
        copy.values.putAll(this.values);
        return copy;
    }

    public java.util.Set<String> keys() {
        return this.values.keySet();
    }

    public String get(String key) {
        return this.values.get(key);
    }

    @Override
    public String toString() {
        return this.values.toString();
    }
}
