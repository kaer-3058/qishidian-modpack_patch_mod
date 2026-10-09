package net.minecraft.network.chat;

/**
 * 测试用假实现（有真实行为）。
 * 形状必须与真实 API 一致：Component 是 interface（MutableComponent 才是 class），
 * 否则这里测试通过、游戏里照样崩 —— 这正是 1.1.0 崩掉的原因。
 */
public interface Component {

    static MutableComponent literal(String text) {
        return new MutableComponent(text);
    }
}
