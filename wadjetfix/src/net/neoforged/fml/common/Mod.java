package net.neoforged.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 【仅编译期使用的桩类 —— 绝对不要打进 jar 里】
 * 运行时由 NeoForge 提供真正的 net.neoforged.fml.common.Mod。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();
}
