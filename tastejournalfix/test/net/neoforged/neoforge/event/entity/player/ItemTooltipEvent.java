package net.neoforged.neoforge.event.entity.player;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** 测试用假实现（有真实行为） */
public class ItemTooltipEvent extends PlayerEvent {

    private final ItemStack stack;
    private final List<Component> tooltip = new ArrayList<>();

    public ItemTooltipEvent(ItemStack stack) {
        this.stack = stack;
    }

    public ItemStack getItemStack() {
        return this.stack;
    }

    public List<Component> getToolTip() {
        return this.tooltip;
    }
}
