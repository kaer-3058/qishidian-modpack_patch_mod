package net.minecraft.world.item;

/**
 * 【仅编译期桩类 —— 不要打进 jar】运行时由 Minecraft 提供。
 * 真实定义：public final class ItemStack implements DataComponentHolder
 * get/has 由 DataComponentHolder 提供；set/remove 是本类自己的方法。
 */
public class ItemStack implements net.minecraft.core.component.DataComponentHolder {

    public boolean isEmpty() {
        throw new UnsupportedOperationException("stub");
    }

    public UseAnim getUseAnimation() {
        throw new UnsupportedOperationException("stub");
    }

    public <T> T set(net.minecraft.core.component.DataComponentType<? super T> type, T value) {
        throw new UnsupportedOperationException("stub");
    }

    public <T> T remove(net.minecraft.core.component.DataComponentType<? extends T> type) {
        throw new UnsupportedOperationException("stub");
    }
}
