package com.entitysectionfix;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 修复「区块状态变更时实体列表被并发修改」导致的服务端崩溃。
 *
 * <p>崩溃现象：
 * <pre>
 * java.util.ConcurrentModificationException
 *   at java.util.ArrayList$Itr.checkForComodification
 *   at PersistentEntitySectionManager.lambda$updateChunkStatus$6
 *   at PersistentEntitySectionManager.updateChunkStatus
 *   at ChunkMap.onFullChunkStatusChange
 *   at ChunkHolder.demoteFullChunk
 * </pre>
 * 典型触发时机：玩家退出服务器 / 走远导致区块降级卸载。
 *
 * <p>根因（原版不可重入）：{@code lambda$updateChunkStatus$6} 里对每个区块段做 4 次
 * {@code section.getEntities()} 流式遍历，遍历中调用
 * {@code startTracking / stopTracking / startTicking / stopTicking}。
 * 这些回调会穿过 {@code ChunkMap} 的追踪逻辑；若某个 mod 在这期间把实体挪出该区块段
 * （跟随移动平台的实体、被传送到主人身边的女仆等），
 * {@code PersistentEntitySectionManager$Callback.onMove} 会调用
 * {@code EntitySection.remove} —— 正在被遍历的 ArrayList 被结构性修改 → CME。
 *
 * <p>本 mod 只做一件事：让 {@code EntitySection.getEntities()} 返回**快照流**
 * （见 {@code EntitySectionMixin}），于是原版那 4 处遍历都变成对快照的迭代。
 * 语义上更接近原版意图（遍历开始时的那批实体），不改变任何 mod 的功能，
 * 也不影响 AABB 实体查询那条热路径。
 */
@Mod("entitysectionfix")
public class EntitySectionFix {

    public static final String MOD_ID = "entitysectionfix";
    public static final Logger LOGGER = LoggerFactory.getLogger("EntitySectionFix");

    public EntitySectionFix() {
        LOGGER.info("[entitysectionfix] EntitySection.getEntities() 已改为快照流：区块状态变更时的 ConcurrentModificationException 不会再发生。");
    }
}
