package com.voidscapefix;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 屏蔽虚空工艺（Voidscape）的全部联网请求。
 *
 * <p>原因：{@code DonatorHandler} 会在每次客户端 tick 的 Pre 事件里调用
 * {@code getDonators()/isDonator()}，这两个方法直接对
 * {@code CompletableFuture.join()}，而该 future 由 {@code run()} 里的
 * {@code URL.openConnection().getInputStream()} 填充。
 * 国内网络访问 {@code gh.tamaized.com} 时连接建立后读不到数据，
 * 于是渲染线程永远卡在 join() 上 —— 游戏冻结（不是崩溃）。
 *
 * <p>本 mod 用两个 mixin 解决：
 * <ul>
 *   <li>{@code DonatorHandler.run} 不再发请求，直接返回空名单
 *       （与原代码 catch 分支的 {@code Collections.emptyList()} 完全等价）</li>
 *   <li>{@code DonatorHandler.getDonators} 直接返回空名单，杜绝一切 join() 阻塞</li>
 *   <li>{@code ModUpdateURLInterceptor} 构造时不再往 ModInfo 里塞更新检查 URL</li>
 * </ul>
 */
@Mod("voidscapefix")
public class VoidscapeFix {

    public static final String MOD_ID = "voidscapefix";
    public static final Logger LOGGER = LoggerFactory.getLogger("VoidscapeFix");

    public VoidscapeFix() {
        LOGGER.info("[voidscapefix] Voidscape 联网检查已屏蔽：donator.properties 与 update.json 都不会再被请求。");
    }
}
