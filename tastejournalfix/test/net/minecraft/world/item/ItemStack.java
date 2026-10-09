package net.minecraft.world.item;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponentType;

/** 测试用假实现（有真实行为） */
public class ItemStack {

    private final Map<DataComponentType<?>, Object> components = new IdentityHashMap<>();
    private final String name;
    private UseAnim anim = UseAnim.NONE;

    public ItemStack(String name) {
        this.name = name;
    }

    public ItemStack anim(UseAnim a) {
        this.anim = a;
        return this;
    }

    public ItemStack with(DataComponentType<?> type, Object value) {
        this.components.put(type, value);
        return this;
    }

    public boolean isEmpty() {
        return this.name == null;
    }

    public UseAnim getUseAnimation() {
        return this.anim;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(DataComponentType<? extends T> type) {
        return (T) this.components.get(type);
    }

    public <T> T getOrDefault(DataComponentType<? extends T> type, T fallback) {
        T value = this.get(type);
        return value != null ? value : fallback;
    }

    public boolean has(DataComponentType<?> type) {
        return this.components.containsKey(type);
    }

    @SuppressWarnings("unchecked")
    public <T> T set(DataComponentType<? super T> type, T value) {
        T old = (T) this.components.get(type);
        this.components.put((DataComponentType<?>) type, value);
        return old;
    }

    @SuppressWarnings("unchecked")
    public <T> T remove(DataComponentType<? extends T> type) {
        return (T) this.components.remove(type);
    }

    public String name() {
        return this.name;
    }

    @Override
    public String toString() {
        return "ItemStack[" + this.name + "]";
    }
}
