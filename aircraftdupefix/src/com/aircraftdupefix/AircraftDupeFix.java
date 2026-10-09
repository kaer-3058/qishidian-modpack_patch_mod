package com.aircraftdupefix;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 修复「沉浸式飞行」载具在玩家退出重进时原地复制、连货舱一起复制的问题。
 *
 * <p>根因不在 immersive_aircraft 里，而在原版的「载具随玩家存档」机制，共三环：
 * <ol>
 *   <li>{@code PlayerList#remove} 在玩家登出时，先 fire {@code PlayerLoggedOutEvent}，
 *       紧接着才调 {@code this.save(player)}；</li>
 *   <li>{@code ServerPlayer#addAdditionalSaveData} 会把玩家所骑载具的<b>整份 NBT</b>
 *       （UUID、坐标，以及 immersive_aircraft 塞进实体 NBT 的货舱）写进 playerdata 的
 *       {@code RootVehicle} 字段，触发条件是 {@code hasExactlyOnePlayerPassenger()}；</li>
 *   <li>玩家重进时 {@code PlayerList#placeNewPlayer} 拿这份 NBT 重建一条载具并加进世界，
 *       而「丢弃多余副本」的清理只在<b>重新上船失败</b>时才执行 —— 一旦上船成功就跳过。</li>
 * </ol>
 *
 * <p>本 mod 的介入点是登出事件：把玩家从载具上摘下来。这一步发生在 {@code save(player)}
 * <b>之前</b>，于是 {@code RootVehicle} 根本不会被写入，整条复制链从源头断掉。
 * 全程没有 mixin，没有覆写任何人的字节码，也不对 immersive_aircraft 产生编译期依赖。
 *
 * <p><b>副作用与取舍：</b>摘下来之后玩家重进时不再自动骑在载具上。绝大多数情况下没问题
 * —— 载具就停在他离开的位置，他落地就站在载具上；但载具被摧毁、被开走，或者区块加载
 * 慢了那么几 tick 时，人会直接往下掉。这里<b>不做</b>任何自动救援（例如自动放平台）：
 * 虚空、岩浆、他人陷阱、高空，危险场景穷举不完，而自作聪明地放方块反而会破坏地形、
 * 落在别人领地里。所以只做一件事 —— 在检测到玩家身边就是载具时，把行为变化明确告诉他。
 */
@EventBusSubscriber(modid = AircraftDupeFix.MOD_ID)
public final class AircraftDupeFix {

    /** 与 neoforge.mods.toml 里的 modId 必须一致（verify.ps1 会核对）。 */
    public static final String MOD_ID = "aircraftdupefix";

    /**
     * immersive_aircraft 的类全在这个包下。
     * 用字符串比对而不是 import，是为了：mod 被移除时本 mod 只是个空操作，
     * 不会因为 NoClassDefFoundError 连累游戏启动；mod 升级改名也不影响。
     */
    private static final String AIRCRAFT_PACKAGE = "immersive_aircraft.";

    /** 载具最小宽度：同包下还有子弹、TNT 这些小实体，用宽度把它们排除。 */
    private static final float MIN_VEHICLE_WIDTH = 1.0F;

    /** 重进时检测身周载具的搜索半径（格）。 */
    private static final double SEARCH_RADIUS = 6.0D;

    /** 判定「玩家就站在这条载具上」时给载具判定箱的容差（格）。 */
    private static final double ONBOARD_TOLERANCE = 1.0D;

    /** 重进时给玩家的提示。 */
    private static final String WARNING = "战斗飞艇会在退出重进时强制下机，当心坠机";

    private AircraftDupeFix() {
    }

    /**
     * 登出时把玩家从 immersive_aircraft 的载具上摘下来 —— 这是修复的核心。
     *
     * <p>条件严格照抄原版那一行（{@code isPassenger() && rootVehicle != this
     * && rootVehicle.hasExactlyOnePlayerPassenger()}），所以只在「原版本来就会写
     * RootVehicle」的情况下才动手 —— 多人共乘一艘船时原版不写，这里也不碰，
     * 避免不必要的干扰。
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();

        // 没骑任何东西：原版也不会写 RootVehicle。
        Entity root = player.getRootVehicle();
        if (root == player || !player.isPassenger()) {
            return;
        }

        // 原版的存档条件：船上正好一名玩家。共乘时原版不写，这里也不插手。
        if (!root.hasExactlyOnePlayerPassenger()) {
            return;
        }

        // 只管这个 mod 的载具，别的载具（船、矿车、马）行为保持原样。
        if (!isAircraft(root)) {
            return;
        }

        // 关键一步：解绑。之后 save(player) 里 getVehicle() 为 null，
        // RootVehicle 不会写入；同时 isPassenger() 变 false，
        // 原版「Removing player mount」那段也不会把船标成 UNLOADED_WITH_PLAYER，
        // 船会正常留在世界里。
        player.stopRiding();
    }

    /**
     * 重进时，如果玩家身边就是载具，提醒他行为已经变了。
     *
     * <p>只提示，不做任何自动救援：不把他放回船上，也不给他垫方块。判断「这里危不危险」
     * 需要穷举虚空、岩浆、高空、他人陷阱等等，永远漏得掉；而自动放平台会破坏地形、
     * 可能落在别人领地里，比问题本身更麻烦。把情况告诉玩家，让他自己决定要不要先落地再退。
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();

        // 已经骑在什么上面了（例如原版回填成功），不必提示。
        if (player.isPassenger()) {
            return;
        }

        // 身边就有载具，说明他多半正是骑着载具退出的那一位。
        if (findAircraftNearby(player) != null) {
            player.sendSystemMessage(Component.literal(WARNING).withStyle(ChatFormatting.YELLOW));
        }
    }

    /**
     * 找玩家身周那条载具：先用球形范围捞一遍，再用「载具判定箱是否包含玩家坐标」精确判定。
     * 后者比单纯比距离准，因为飞艇的座位离载具原点可能有好几格。
     */
    private static Entity findAircraftNearby(Player player) {
        Level level = player.level();
        AABB searchBox = player.getBoundingBox().inflate(SEARCH_RADIUS);
        List<Entity> candidates = level.getEntities(player, searchBox, entity -> isNearbyAircraft(entity, player));

        Entity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity candidate : candidates) {
            double distance = candidate.distanceToSqr(player);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /** 够大的、空着的、并且玩家就站在它身上的载具。 */
    private static boolean isNearbyAircraft(Entity entity, Player player) {
        if (!isAircraft(entity)) {
            return false;
        }
        if (entity.getBbWidth() < MIN_VEHICLE_WIDTH) {
            return false;                       // 子弹、TNT 这类同包小实体
        }
        if (!entity.getPassengers().isEmpty()) {
            return false;                       // 船上已经有人，多半不是他刚才那条
        }
        // 玩家必须落在这条载具的判定箱里（放宽一点），避免抓到旁边停着的另一条。
        return entity.getBoundingBox().inflate(ONBOARD_TOLERANCE).contains(player.position());
    }

    /**
     * 判断一个实体是不是 immersive_aircraft 的类。
     * 顺着继承链比对包名，这样即使子类被别的 mod 生成在别处也能认出来。
     */
    private static boolean isAircraft(Entity entity) {
        if (entity == null) {
            return false;
        }
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().startsWith(AIRCRAFT_PACKAGE)) {
                return true;
            }
        }
        return false;
    }
}
