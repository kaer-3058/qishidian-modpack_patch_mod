package com.wadjetfix.mixin;

import com.wadjetfix.WadjetMath;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 lionfishapi 的 DynamicChain.wrapAngles 无界循环。
 *
 * 原始实现（2.8 / 3.0 / 3.1 字节码一致）：
 *
 *     private static Vec3 wrapAngles(Vec3 v) {
 *         double x = v.x, y = v.y, z = v.z;
 *         while (x >  Math.PI) x -= 2 * Math.PI;   // 没有迭代上限
 *         while (x < -Math.PI) x += 2 * Math.PI;
 *         ... y、z 同理
 *         return new Vec3(x, y, z);
 *     }
 *
 * 当角度是 ±Infinity，或绝对值大到 double 无法表示 "减 2π" 这一步
 * （|a| ≳ 2^53·2π ≈ 5.6e16，此时 a - 2π == a），循环永远无法推进。
 * 该方法跑在渲染线程上，于是渲染线程 100% CPU 空转、画面彻底卡死。
 *
 * 重要：NaN 必须原样透传
 * javac 为 ">" 生成 dcmpl、为 "<" 生成 dcmpg，两者遇到 NaN 都会让循环直接退出。
 * 也就是说原实现对 NaN 是「安全穿透」的，而这不是巧合：
 * DynamicChain.updateChain 第 159 行正是用 Double.isNaN(this.p[1].x) 作为
 * 「链条算崩了，重建它」的判据。把 NaN 换成 0.0 会掐断这个信号，
 * 导致尾巴卡在错误角度接不上、并随真实值来回跳（一闪一闪）。
 *
 * 所以本修复只处理真正会死循环的情况（±Infinity / 荒谬的超大值），
 * 把它们转成 NaN 交回上游原本就有的重建路径；NaN 与正常值行为不变。
 */
@Mixin(targets = "com.github.L_Ender.lionfishapi.client.model.tools.DynamicChain")
public class DynamicChainMixin {

    /** 仅用于一次性诊断输出，判断 d[i] 是否退化成 0（除零 -> Infinity -> 卡死）。 */
    @Shadow
    private float[] d;

    /**
     * @author wadjetfix
     * @reason 原实现的 while 循环没有迭代上限，遇到 ±Infinity / 超大角度会永久空转。
     */
    @Overwrite
    private static Vec3 wrapAngles(Vec3 v) {
        return new Vec3(
                WadjetMath.wrap(v.x),
                WadjetMath.wrap(v.y),
                WadjetMath.wrap(v.z)
        );
    }

    @Inject(method = "setChain", at = @At("TAIL"))
    private void wadjetfix$reportSegments(CallbackInfo ci) {
        WadjetMath.reportSegments(this.d);
    }
}
