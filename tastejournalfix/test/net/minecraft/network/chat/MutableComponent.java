package net.minecraft.network.chat;

/** 测试用假实现（有真实行为）。真实声明：public class MutableComponent implements Component */
public class MutableComponent implements Component {

    private final String text;

    public MutableComponent(String text) {
        this.text = text;
    }

    public String text() {
        return this.text;
    }

    @Override
    public String toString() {
        return this.text;
    }
}
