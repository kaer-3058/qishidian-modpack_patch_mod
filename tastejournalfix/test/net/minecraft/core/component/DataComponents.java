package net.minecraft.core.component;

import net.minecraft.world.item.component.CustomData;

/**
 * 测试用假实现（有真实行为）。
 * 真实字段：DataComponentType<FoodProperties> FOOD / DataComponentType<CustomData> CUSTOM_DATA
 */
public class DataComponents {

    public static final DataComponentType<Object> FOOD = new DataComponentType<Object>() {
        @Override
        public String toString() {
            return "minecraft:food";
        }
    };

    public static final DataComponentType<CustomData> CUSTOM_DATA = new DataComponentType<CustomData>() {
        @Override
        public String toString() {
            return "minecraft:custom_data";
        }
    };
}
