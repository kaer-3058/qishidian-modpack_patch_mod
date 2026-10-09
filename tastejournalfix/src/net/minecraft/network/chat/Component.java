package net.minecraft.network.chat;

/**
 * 【仅编译期桩类 —— 不要打进 jar】运行时由 Minecraft 提供。
 *
 * 真实声明（javap 实测）：
 *     public interface net.minecraft.network.chat.Component
 *             extends com.mojang.brigadier.Message, net.minecraft.network.chat.FormattedText
 *
 * **这里是 interface，不是 class。** 这一点至关重要：
 * 如果桩写成 class，javac 会把 Component.literal 编成 Methodref/invokevirtual，
 * 而 JVM 要求接口的调用必须用 InterfaceMethodref，于是在游戏里
 * （用到 tooltip 的时候）抛：
 *     java.lang.IncompatibleClassChangeError:
 *     Method '...Component.literal(String)' must be InterfaceMethodref constant
 * 编译期和本地单元测试都发现不了 —— 见 verify.ps1。
 *
 * literal 的真实返回类型是 MutableComponent（是个 class），返回类型参与方法描述符。
 */
public interface Component {

    static MutableComponent literal(String text) {
        throw new UnsupportedOperationException("stub");
    }

    static MutableComponent translatable(String key, Object... args) {
        throw new UnsupportedOperationException("stub");
    }
}
