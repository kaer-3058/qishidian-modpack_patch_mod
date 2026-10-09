package net.neoforged.neoforge.event.entity.player;

import net.minecraft.world.item.ItemStack;

/** 测试用假实现（有真实行为）：真实类同名字、同 getter */
public abstract class PlayerEvent {

    public static class ItemCraftedEvent extends PlayerEvent {
        private final ItemStack crafting;

        public ItemCraftedEvent(ItemStack crafting) {
            this.crafting = crafting;
        }

        public ItemStack getCrafting() {
            return this.crafting;
        }
    }

    public static class ItemSmeltedEvent extends PlayerEvent {
        private final ItemStack smelting;

        public ItemSmeltedEvent(ItemStack smelting) {
            this.smelting = smelting;
        }

        public ItemStack getSmelting() {
            return this.smelting;
        }
    }
}
