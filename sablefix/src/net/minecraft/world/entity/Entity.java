package net.minecraft.world.entity;

/**
 * 【仅编译期使用的桩类 —— 绝对不要打进 jar 里】
 * 运行时由 Minecraft 本体提供真正的 net.minecraft.world.entity.Entity。
 *
 * mixin 处理器的参数类型检查走的是描述符，泛型参数（CallbackInfoReturnable<SubLevel>）
 * 不参与匹配，这里只是为了让它能编译过。
 */
public class Entity {
}
