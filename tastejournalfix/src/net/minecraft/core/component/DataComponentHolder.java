package net.minecraft.core.component;

/**
 * 【仅编译期桩类 —— 不要打进 jar】运行时由 Minecraft 提供。
 * 真实定义（javap 实测，注意三个方法都是 default）：
 *   public interface DataComponentHolder {
 *       DataComponentMap getComponents();
 *       default <T> T get(DataComponentType<? extends T>);
 *       default <T> T getOrDefault(DataComponentType<? extends T>, T);
 *       default boolean has(DataComponentType<?>);
 *   }
 * ItemStack implements DataComponentHolder，所以 get/has 编译成 invokeinterface。
 */
public interface DataComponentHolder {

    default <T> T get(DataComponentType<? extends T> type) {
        throw new UnsupportedOperationException("stub");
    }

    default <T> T getOrDefault(DataComponentType<? extends T> type, T fallback) {
        throw new UnsupportedOperationException("stub");
    }

    default boolean has(DataComponentType<?> type) {
        throw new UnsupportedOperationException("stub");
    }
}
