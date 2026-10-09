package net.minecraft.core.component;

import net.minecraft.world.item.component.CustomData;

/**
 * 【仅编译期桩类 —— 不要打进 jar】运行时由 Minecraft 提供。
 * 真实字段类型（javap 实测）：
 *   public static final DataComponentType<FoodProperties> FOOD;
 *   public static final DataComponentType<CustomData>   CUSTOM_DATA;
 * 这里 FOOD 用 Object 当类型参数只为省掉一个 FoodProperties 桩类 ——
 * 泛型参数不参与字节码描述符，运行时解析到的仍是真字段。
 */
public class DataComponents {

    public static final DataComponentType<Object> FOOD = null;

    public static final DataComponentType<CustomData> CUSTOM_DATA = null;
}
