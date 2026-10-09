package net.minecraft.world.phys;

/**
 * 【仅编译期使用的桩类 —— 绝对不要打进 jar 里】
 *
 * 目的只是让 javac 能算出正确的字节码描述符：
 *     (Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;
 * 运行时会链接到 Minecraft 真正的 Vec3（同包同名 + 同字段同构造器）。
 */
public class Vec3 {
    public final double x;
    public final double y;
    public final double z;

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
