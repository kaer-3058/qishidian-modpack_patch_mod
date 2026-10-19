package com.tacznofire;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.item.IGun;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 让 TACZ 的枪无法开火（默认只针对 TACZ 自带的枪包 {@code tacz_default_gun}）。
 *
 * <h2>原理</h2>
 * TACZ 打算开火时会发一个<b>可取消</b>的事件，紧接着检查它的取消状态；被取消就返回
 * {@code ShootResult.FORGE_EVENT_CANCEL} 直接退出。这一点在 TACZ 自己的字节码里可以逐条看到：
 * <ul>
 *   <li>服务端：{@code com.tacz.guns.entity.shooter.LivingEntityShoot#shoot} 里
 *       {@code NeoForge.EVENT_BUS.post(new GunShootEvent(...))} 后面紧跟着
 *       {@code GunShootEvent.isCanceled()}，为真就 {@code return FORGE_EVENT_CANCEL}；</li>
 *   <li>客户端：{@code com.tacz.guns.client.gameplay.LocalPlayerShoot#shoot} 一模一样，
 *       所以客户端被取消后连开火网络包都不会发出去；</li>
 *   <li>脚本开火：{@code com.tacz.guns.item.ModernKineticGunScriptAPI} 发
 *       {@code GunFireEvent} 后同样检查取消，取消即跳过开火。</li>
 * </ul>
 * 三条路都在同一条总线 {@code NeoForge.EVENT_BUS} 上（就是普通游戏事件总线），
 * 所以本 mod <b>一个 Mixin 都不需要</b>，只是挂两个普通的事件监听器把事件取消掉。
 *
 * <h2>子弹会不会被扣</h2>
 * <ul>
 *   <li>客户端也装了本 mod（正常情况）：事件在客户端就被取消，网络包根本不发，
 *       子弹、枪声、开火动画、后坐力全都没有；</li>
 *   <li>只有服务端装了（某个玩家的客户端没装）：服务端对<b>闭膛枪</b>是在"上一发进膛"
 *       之后才发这个事件，所以那名玩家第一次扣扳机可能看到弹匣少一发、弹膛变成已上膛，
 *       之后不再减少，但<b>绝不会真的打出子弹</b>（开膛枪连这一发都不会扣）。</li>
 * </ul>
 *
 * <h2>拦哪些枪</h2>
 * 枪 id 的命名空间就是枪包的命名空间：TACZ 自带枪包是 {@code tacz}（54 把：ak47、m4a1、
 * deagle …），第三方枪包用自己的命名空间（你装的 Create_Skin 那个包用的是 {@code 1001}，
 * 里面有 lyr、m249、mk14ebr、mk18、vector9）。
 * 默认只拦 {@code tacz}，也就是"原版枪械"；要连第三方包一起拦、或者只拦某几把枪，
 * 改 {@code config/tacznofire.txt} 即可（改完保存就生效，不用重启）。
 *
 * <h2>被禁的枪长什么样</h2>
 * 鼠标移上去，提示框最下面会多一行红色斜体字：<b>{@value #DEFAULT_LORE_TEXT}</b>
 * （文本可在配置里改，留空即不显示）。这一行是显示时临时拼上去的，<b>没有写进物品数据</b>：
 * 不用重写存档、不产生同步流量，配置一改提示立刻跟着变。
 *
 * <h2>拦不住的</h2>
 * <ul>
 *   <li>刺刀近战：那是近战判定，不是"开火"，本 mod 不碰；</li>
 *   <li>KubeJS 脚本若绕过 TACZ 的事件、自己直接生成子弹实体，那拦不住；</li>
 *   <li>别的 mod 自己的枪械系统（本 mod 只认 TACZ 的枪）。</li>
 * </ul>
 */
@Mod(TaczNoFire.MOD_ID)
@EventBusSubscriber(modid = TaczNoFire.MOD_ID)
public final class TaczNoFire {

    /** 与 neoforge.mods.toml 里的 modId 必须一致（verify.ps1 会核对）。 */
    public static final String MOD_ID = "tacznofire";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 配置文件路径：相对于游戏/服务端根目录。 */
    private static final Path CONFIG_PATH = Paths.get("config", "tacznofire.txt");

    /** TACZ 自带枪包的命名空间。 */
    private static final String BUILTIN_NAMESPACE = "tacz";

    /** 被禁的枪在物品提示里多出来的那一行，默认文本（可在配置里改，留空则不显示）。 */
    private static final String DEFAULT_LORE_TEXT = "损坏，无法开枪";

    /**
     * 这一行的样式：斜体 + 红色。原版 lore 行就是"斜体"，所以看着像真正的物品描述，
     * 而不是一句外挂提示；用红色是为了让"为什么打不响"一眼可见。
     */
    private static final Style LORE_STYLE = Style.EMPTY.withItalic(true).withColor(ChatFormatting.RED);

    /** 每种枪的拦截日志只记一次，避免刷屏。 */
    private static final Set<String> LOGGED_ONCE = ConcurrentHashMap.newKeySet();

    private static volatile Rule rule;
    private static volatile FileTime ruleStamp;
    private static volatile boolean wroteDefault;

    /** FML 会反射调用这个无参构造器（所以要 public），顺手把配置准备好。 */
    public TaczNoFire() {
        // 启动时就把配置读一次：文件不存在就用默认值并把它写出来，省得非要等第一次开枪
        // 才生成。顺带在日志里报一句当前规则，方便确认 mod 真的加载了。
        //
        // 这里整体兜一层 try：mod 构造器里抛异常会让游戏起不来，而"准备配置文件"这件事
        // 失败完全不该有这个后果 —— 读不到配置时规则自己会退回默认值。
        try {
            LOGGER.info("[禁用开火] 已启用：{}", currentRule().describe());
        } catch (Throwable t) {
            LOGGER.warn("[禁用开火] 启动时准备配置失败，不影响拦截：{}", t.toString());
        }
    }

    /**
     * 主路径：扣扳机开火。TACZ 在真正开枪前发这个事件，并在之后立刻检查取消状态，
     * 被取消就返回 {@code ShootResult.FORGE_EVENT_CANCEL}。
     */
    @SubscribeEvent
    public static void onGunShoot(GunShootEvent event) {
        if (blockedGunId(event.getGunItemStack(), event.getShooter(), event.getLogicalSide(), "开火") != null) {
            event.setCanceled(true);
        }
    }

    /**
     * 副路径：真正"打出这一发"时的事件。拦它的两个理由：
     * <ul>
     *   <li>别处也用它 —— {@code ModernKineticGunScriptAPI}（枪械脚本 / KubeJS 脚本开火）
     *       在发完这个事件后同样检查 {@code isCanceled()}，取消即跳过开火。有些脚本自己
     *       定时调它，那条路根本不经过上面的 GunShootEvent，所以这里得补一刀；</li>
     *   <li>客户端用它触发开火动画，顺手取消掉，客户端也不会播开火动作。</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onGunFire(GunFireEvent event) {
        if (blockedGunId(event.getGunItemStack(), event.getShooter(), event.getLogicalSide(), "脚本/动画开火") != null) {
            event.setCanceled(true);
        }
    }

    /**
     * 给被禁的枪加一行提示（默认"损坏，无法开枪"）。
     *
     * <p><b>只是显示，不动物品数据。</b>没有把 lore 真的写进枪里，理由有三个：
     * <ul>
     *   <li>写进物品就等于改存档，每一把已经存在的枪都得重写一遍，而且要跟背包同步、跟
     *       服务器广播，纯属白费流量；</li>
     *   <li>配置一改（比如把某把枪放出黑名单），物品上的字就过时了，还得再刷一遍；</li>
     *   <li>而 tooltip 是每次把鼠标移上去临时拼的，规则是什么样就显示什么样，天然一致。</li>
     * </ul>
     *
     * <p>位置：原版的 {@code ItemTooltipEvent} 是在 {@code ItemStack#getTooltipLines} 的
     * <b>最后一步</b>才发的（就贴在 {@code areturn} 前面），所以往列表里 add 的这一行会
     * 出现在提示框<b>最底下</b> —— 正是原版 lore 该在的位置。
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        Rule current = currentRule();
        String text = current.loreText;
        if (text.isEmpty()) {
            return;                     // 配成空字符串 = 不加这一行
        }

        ItemStack stack = event.getItemStack();
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return;
        }
        ResourceLocation gunId = gun.getGunId(stack);
        if (gunId == null || !current.blocks(gunId)) {
            return;
        }

        event.getToolTip().add(Component.literal(text).withStyle(LORE_STYLE));
    }

    /**
     * 判定要不要拦下这把枪。要拦就返回它的枪 id（并顺手记一次日志），不拦返回 {@code null}。
     *
     * <p>这里刻意把"读枪 id"写成 {@link IGun#getIGunOrNull} + {@link IGun#getGunId} 的
     * 正规 API 调用，而不是去翻 NBT 里那个 {@code GunId} 字段 —— TACZ 换存档格式时
     * 本 mod 也不会失效。
     */
    private static ResourceLocation blockedGunId(ItemStack stack, LivingEntity shooter,
                                                 LogicalSide side, String source) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return null;                // 手里不是 TACZ 的枪
        }
        ResourceLocation gunId = gun.getGunId(stack);
        if (gunId == null) {
            return null;                // 数据不全的枪（比如还没初始化），不拦
        }

        Rule current = currentRule();
        if (!current.blocks(gunId)) {
            return null;
        }

        if (current.log && LOGGED_ONCE.add(gunId.toString())) {
            LOGGER.info("[禁用开火] 拦下 {}（{} 侧，{}，开火者 {}）",
                    gunId, side, source, shooter == null ? "?" : shooter.getName().getString());
        }
        return gunId;
    }

    // ------------------------------------------------------------------ 配置

    /**
     * 取当前规则。配置文件被改动（mtime 变了）就重读，所以改完保存即生效，不用重启。
     * 读失败时沿用上一份规则，最坏情况下退回默认（只拦 tacz）。
     */
    private static Rule currentRule() {
        try {
            if (Files.isRegularFile(CONFIG_PATH)) {
                FileTime stamp = Files.getLastModifiedTime(CONFIG_PATH);
                if (rule == null || !stamp.equals(ruleStamp)) {
                    Rule parsed = Rule.parse(Files.readAllLines(CONFIG_PATH, StandardCharsets.UTF_8));
                    rule = parsed;
                    ruleStamp = stamp;
                    LOGGER.info("[禁用开火] 配置已加载：{}", parsed.describe());
                }
            } else if (!wroteDefault) {
                wroteDefault = true;
                writeDefaultConfig();
            }
        } catch (Exception e) {
            LOGGER.warn("[禁用开火] 读配置失败，继续用当前规则：{}", e.toString());
        }
        if (rule == null) {
            rule = Rule.defaults();
        }
        return rule;
    }

    /** 第一次运行时生成带注释的默认配置，方便直接改。 */
    private static void writeDefaultConfig() {
        String text = """
                # ===== TACZ 禁用开火 配置 =====
                # 改完保存即生效，不需要重启服务器/游戏。
                #
                # mode：拦哪些枪
                #   default_pack = 只拦 TACZ 自带枪包（命名空间 tacz：ak47、m4a1、deagle… 共 54 把）
                #   all          = 拦所有 TACZ 的枪（连第三方枪包一起拦）
                mode = default_pack
                #
                # block_namespaces：额外要拦的"枪包命名空间"，逗号分隔。
                #   枪 id 的命名空间就是枪包的命名空间。例如某个第三方包用 1001，
                #   想连它一起拦就写： block_namespaces = tacz,1001
                block_namespaces = tacz
                #
                # block_guns：额外要拦的具体枪 id，逗号分隔。例如：
                #   block_guns = tacz:ak47,1001:lyr
                block_guns =
                #
                # log_blocked：是否在日志里记录拦截（每种枪只记一次）
                log_blocked = true
                #
                # lore_text：被禁的枪在物品提示里多显示的一行（显示在提示框最下面，红色斜体）。
                #   留空 = 不加这一行。文本里别用 # 字符（# 连同后面会被当成注释）。
                lore_text = 损坏，无法开枪
                """;
        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(CONFIG_PATH, text, StandardCharsets.UTF_8);
            LOGGER.info("[禁用开火] 已生成默认配置 {}", CONFIG_PATH.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.warn("[禁用开火] 写默认配置失败（不影响拦截，用的是内置默认值）：{}", e.toString());
        }
    }

    /** 一份解析好的规则。 */
    private static final class Rule {

        private final boolean blockAll;
        private final Set<String> namespaces;
        private final Set<String> guns;
        private final boolean log;
        /** 被禁的枪在提示里多显示的一行；空字符串 = 不显示。 */
        private final String loreText;

        private Rule(boolean blockAll, Set<String> namespaces, Set<String> guns, boolean log, String loreText) {
            this.blockAll = blockAll;
            this.namespaces = namespaces;
            this.guns = guns;
            this.log = log;
            this.loreText = loreText;
        }

        /** 内置默认：只拦 TACZ 自带枪包，并且给被禁的枪加一行提示。 */
        static Rule defaults() {
            return new Rule(false, Set.of(BUILTIN_NAMESPACE), Set.of(), true, DEFAULT_LORE_TEXT);
        }

        static Rule parse(List<String> lines) {
            boolean blockAll = false;
            boolean log = true;
            // 配置文件里没写 lore_text 这个键时，用默认文本；写了空值就是"不显示"。
            String loreText = DEFAULT_LORE_TEXT;
            Set<String> namespaces = new LinkedHashSet<>();
            Set<String> guns = new LinkedHashSet<>();

            for (String raw : lines) {
                String line = raw;
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "mode" -> blockAll = value.equalsIgnoreCase("all");
                    case "block_namespaces" -> addAll(namespaces, value);
                    case "block_guns" -> addAll(guns, value);
                    case "log_blocked" -> log = !value.equalsIgnoreCase("false");
                    case "lore_text" -> loreText = value;
                    default -> {
                        // 不认识的键忽略掉，方便以后加配置项
                    }
                }
            }
            return new Rule(blockAll, namespaces, guns, log, loreText);
        }

        private static void addAll(Set<String> target, String value) {
            for (String piece : value.split(",")) {
                String trimmed = piece.trim();
                if (!trimmed.isEmpty()) {
                    target.add(trimmed);
                }
            }
        }

        /** 枪 id 的命名空间就是枪包的命名空间。 */
        boolean blocks(ResourceLocation gunId) {
            return blocks(gunId.getNamespace(), gunId.toString());
        }

        /**
         * 真正的判定核心，刻意只用 {@code String} —— 不碰任何 Minecraft 类型，
         * 于是 verify.ps1 能在游戏外<b>真的把它跑一遍</b>（见 verify/RuleTest.java）。
         * 上面那个 ResourceLocation 重载只是三行的适配层。
         */
        boolean blocks(String namespace, String gunId) {
            if (blockAll) {
                return true;
            }
            if (guns.contains(gunId)) {
                return true;
            }
            return namespaces.contains(namespace);
        }

        String describe() {
            String lore = loreText.isEmpty() ? "不加提示行" : "提示行“" + loreText + "”";
            if (blockAll) {
                return "mode=all（所有 TACZ 的枪都不能开火），" + lore;
            }
            return "拦截命名空间 " + namespaces + "，另外点名拦截 " + guns + "，记录日志=" + log + "，" + lore;
        }
    }
}
