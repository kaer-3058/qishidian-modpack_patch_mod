package net.neoforged.neoforge.event.entity.player;

import net.minecraft.world.item.ItemStack;

/** 【仅编译期桩类 —— 不要打进 jar】运行时由 NeoForge 提供 */
public abstract class PlayerEvent {

    public static class ItemCraftedEvent {
        public ItemStack getCrafting() {
            throw new UnsupportedOperationException("stub");
        }
    }

    public static class ItemSmeltedEvent {
        public ItemStack getSmelting() {
            throw new UnsupportedOperationException("stub");
        }
    }
}
