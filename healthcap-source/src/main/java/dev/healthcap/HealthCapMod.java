package dev.healthcap;

import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;

@Mod(HealthCapMod.ID)
public final class HealthCapMod {
    public static final String ID = "healthcap";

    // A fixed value on both sides keeps vanilla attribute synchronization consistent.
    private static final double HARD_CAP = 16_000_000.0;

    public HealthCapMod(IEventBus modBus) {
        modBus.addListener(this::raiseMaxHealthLimit);
    }

    private void raiseMaxHealthLimit(EntityAttributeModificationEvent event) {
        RangedAttribute maxHealth = (RangedAttribute) Attributes.MAX_HEALTH.value();
        maxHealth.maxValue = Math.max(maxHealth.getMaxValue(), HARD_CAP);
    }
}
