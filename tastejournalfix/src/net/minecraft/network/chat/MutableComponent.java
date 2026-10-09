package net.minecraft.network.chat;

/**
 * 【仅编译期桩类 —— 不要打进 jar】运行时由 Minecraft 提供。
 *
 * 真实声明（javap 实测）：public class MutableComponent implements Component
 * 它是 class（Component 才是 interface），所以这里必须 implements 而不是 extends。
 */
public class MutableComponent implements Component {
}
