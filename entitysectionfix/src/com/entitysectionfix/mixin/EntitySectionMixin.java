package com.entitysectionfix.mixin;

import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.entity.EntitySection;

/**
 * 把 {@code EntitySection.getEntities()} 的返回值换成快照流。
 *
 * <p>原版（1.21.1）：
 * <pre>
 *   private final ClassInstanceMultiMap&lt;T&gt; storage;
 *   public Stream&lt;T&gt; getEntities() { return this.storage.stream(); }   // 实时流
 * </pre>
 *
 * <p>{@code storage} 是 {@code ClassInstanceMultiMap}，底层 ArrayList。原版
 * {@code PersistentEntitySectionManager.lambda$updateChunkStatus$6} 会拿着这个实时流
 * 遍历实体并调用 start/stopTracking；被遍历的实体若在此刻被另一个 mod 移出该段
 * （传送、跟随移动平台），ArrayList 被结构性修改 → ConcurrentModificationException。
 *
 * <p>改法：在 RETURN 处把实时的流物化成不可变列表，再返回基于该列表的流。
 * 快照在遍历开始时取定，迭代过程中底层列表怎么变都不会再抛 CME，
 * 而遍历到的那批实体与原版意图一致。
 *
 * <p>只影响无参的 {@code getEntities()}（区块状态变更 / 卸载路径）。
 * AABB 查询用的两个 {@code getEntities(AABB, ...)} / {@code getEntities(TypeTest, AABB, ...)}
 * 重载没有改动，实体碰撞查询那条热路径不受影响。
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin {

    @Inject(
            method = "getEntities()Ljava/util/stream/Stream;",
            at = @At("RETURN"),
            cancellable = true,
            require = 1
    )
    private void entitysectionfix$snapshot(CallbackInfoReturnable<Stream<?>> cir) {
        Stream<?> live = cir.getReturnValue();
        if (live != null) {
            // toList() 会把当前实体引用全部复制进一个不可变列表，
            // 随后返回的流与 storage 再无关联。
            cir.setReturnValue(live.toList().stream());
        }
    }
}
