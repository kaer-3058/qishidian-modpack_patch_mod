package net.neoforged.neoforge.event.entity.player;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 【仅编译期桩类 —— 不要打进 jar】运行时由 NeoForge 提供。
 * 真实类同名字、同 getter；这里只声明本修复用到的两个方法。
 */
public class ItemTooltipEvent extends PlayerEvent {

    public ItemStack getItemStack() {
        throw new UnsupportedOperationException("stub");
    }

    public List<Component> getToolTip() {
        throw new UnsupportedOperationException("stub");
    }
}
