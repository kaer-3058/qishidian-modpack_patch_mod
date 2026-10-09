# voidscapefix — 屏蔽虚空工艺（Voidscape）的全部联网请求

**版本**：1.0.0　|　**产物**：`artifacts\voidscapefix-1.0.0.jar`（3,796 字节）
**针对**：`Voidscape-1.21.1-1.9.588.jar`（1.21.1 / NeoForge 21.1.249）

---

## 一、为什么需要它

游戏卡死（不是崩溃）的线程快照证据：

```
"Render thread" WAITING (parking)                    ← 渲染线程卡住 171 秒
  at java.util.concurrent.CompletableFuture.join
  at DonatorHandler.isDonator(DonatorHandler.java:33)
  at DonatorSettings.lambda$postConstruct$1(DonatorSettings.java:44)
  at ClientHooks.fireClientTickPre                   ← 每次客户端 tick 都会走
  at Minecraft.tick
```

它在等的线程：

```
"ForkJoinPool.commonPool-worker-4"
  at sun.nio.ch.SocketDispatcher.read0                ← 卡在 socket 读取，永远读不完
  at HttpsClient.afterConnect
  at HttpURLConnection.getInputStream0
  at DonatorHandler.run(DonatorHandler.java:52)
```

访问的地址（从 jar 字节码里提取）：

```
https://gh.tamaized.com/Tamaized/Voidscape/donator.properties
https://gh.tamaized.com/Tamaized/Voidscape/update.json?m=%s&l=%s&v=%s
```

`DonatorHandler` 用 `CompletableFuture.supplyAsync` 异步拉捐赠者名单，却在**渲染线程**上
`join()` 等结果。国内网络连得上 `gh.tamaized.com` 但读不到数据，HTTP 请求永不返回，
`join()` 就永不返回 —— 游戏画面定住，只能强杀进程。

## 二、这个 mod 做了什么

三个注入点，全部在 `tamaized.voidscape.network` 包内（Voidscape 自己的类）：

| 目标 | 方式 | 效果 |
|---|---|---|
| `DonatorHandler.run()` | HEAD 注入 + 直接返回 `Collections.emptyList()` | 根本不发 HTTP 请求；`CompletableFuture` 立即正常完成 |
| `DonatorHandler.getDonators()` | HEAD 注入 + 直接返回 `Collections.emptyList()` | 双保险：任何调用者都不会执行 `join()` |
| `ModUpdateURLInterceptor.<init>()` | HEAD 注入 + `cancel()` | 不再把 `update.json` 地址塞进 NeoForge 的 `ModInfo`，更新检查不会发生 |

**为什么返回空列表是安全的**：`run()` 原本的 `catch` 分支就是
`LOGGER.error(...); return Collections.emptyList();` —— "请求失败"和"没有捐赠者"在逻辑上
本来就是同一个状态。所以这个改动只等于"把请求失败的路径提前固定下来"，不改变任何游戏逻辑。
副作用仅是捐赠者名单功能失效（本来也加载不出来）。

## 三、验证状态

构建脚本自带三轮自动校验，全部通过：

- **preflight**：用 `javap` 确认目标 jar 里确实存在 `run()`、`getDonators()` 并调用了
  `CompletableFuture.join()`，以及 `ModUpdateURLInterceptor()` 无参构造存在。
- **编译**：对着真实的 Voidscape / NeoForge / Mixin jar 编译（`javac 21.0.7`，9 个真实 jar），
  没有手写 stub。
- **描述符核对**：目标描述符 `()Ljava/util/List;`、`()Ljava/util/List;`、`()V` 与 jar 内
  `javap -s` 输出逐字一致；注解里的 `method=[...]` 字符串已反编译确认。
- **postflight**：确认打出的 jar 内含全部类、未误打包任何 `tamaized/*` 类，
  且 mixin 类带有两个注入方法。

注入点用 `require = 1`：一旦 Voidscape 升级导致方法改名，会在启动时**明确报错**，
而不是静默失效让卡死问题悄悄回来。

## 四、安装与卸载

- **安装**：把 `artifacts\voidscapefix-1.0.0.jar` 放进
  `.minecraft\versions\起始点\mods\` 即可，客户端和服务器都要放。
- **卸载/回滚**：删掉这个 jar 就彻底恢复原状 —— 它不修改任何原 mod 文件，
  也不生成配置文件。
- **依赖**：`voidscape` 声明为 optional，若哪天移除了虚空工艺，这个 mod 会静默跳过
  （mixin 配置 `"required": false`），不会报错。

## 五、目录结构

```
voidscapefix\
  build.ps1                              # 构建 + 三轮校验（ASCII-only，PS 5.1 安全）
  build-meta.txt                         # 构建时间 / javac / classpath 记录
  src\com\voidscapefix\
    VoidscapeFix.java                    # @Mod 入口，只打一行日志
    mixin\DonatorHandlerMixin.java       # 掐联网 + 防 join
    mixin\ModUpdateURLInterceptorMixin.java
  resources\
    META-INF\neoforge.mods.toml
    voidscapefix.mixins.json
  artifacts\voidscapefix-1.0.0.jar
```

重新构建：`pwsh -NoProfile -File 相关文件\DeepSeek写的mod\voidscapefix\build.ps1`
