package com.tastejournalfix;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 修复「味蕾乐事」(tastejournal) 给非食物物品盖 custom_data.tastejournal 戳、
 * 导致合成产物的堆叠被破坏的问题，并把这个隐藏标记显示出来。
 *
 * 【bug 出处】dev.tastejournal.data.HomemadeFoodTracker.mark(ItemStack, Player)
 * 会给物品写 custom_data.tastejournal.maker = 玩家UUID，用来标记"这是玩家自制的食物"。
 * 它挂在两个事件上：
 *
 *     PlayerEvent.ItemCraftedEvent -> mark(event.getCrafting(), ...)
 *     PlayerEvent.ItemSmeltedEvent -> mark(event.getSmelting(), ...)
 *
 * 也就是"合成任意物品"和"烧炼任意物品"，**从头到尾没检查过这玩意儿是不是食物** ——
 * 所以合木板、做木棍、烧玻璃都会被盖章。
 *
 * 【为什么单点有、Shift 没有】原版两条点击路径传给事件的栈完全是两回事：
 *
 *   PICKUP（鼠标单点）：AbstractContainerMenu.doClick 里
 *       optional1.ifPresent(stack -> { this.setCarried(stack); slot.onTake(player, stack); });
 *       —— 放到鼠标上的和传给 onTake/事件的是同一个对象，于是就地改到鼠标上那叠。
 *
 *   QUICK_MOVE（Shift 点击）：CraftingMenu.quickMoveStack 里
 *       if (!moveItemStackTo(slotStack, ...)) ...;   // moveItemStackTo 会就地把 slotStack 清零
 *       slot.onQuickCraft(slotStack, result);        // 传的是已经空的它
 *       —— 事件带着空栈到达 mark()，被它自己的 if (!stack.isEmpty()) 挡掉了。
 *
 * 【为什么堆叠坏了】1.20.5 之后 isSameItemSameComponents 比的是完整组件集。
 * 一个栈有 custom_data.tastejournal、另一个没有，就永远合不到一起；
 * 而且鼠标上带戳的木板会让"再点一下继续取"也被原版拒绝（组件不匹配）。
 *
 * 【两道修法】
 *   1. 只让真正的食物/饮品保留这个戳 —— 用 EventPriority.LOWEST 挂在同样两个事件上，
 *      排在 tastejournal 自己的处理之后，把它误加的戳摘掉。
 *   2. 给仍然带戳的物品加一行 tooltip。tooltip 是显示层的，不写进组件集，
 *      所以它解释原因的同时不会让堆叠问题变得更糟。
 */
@Mod("tastejournalfix")
@EventBusSubscriber(modid = "tastejournalfix")
public class TasteJournalFix {

    private static final String TAG_KEY = "tastejournal";

    /** 真正的食物/饮品：戳是有意义的，保留，但要告诉玩家为什么它不堆叠。 */
    private static final String LINE_HOMEMADE = "\u00a76\u81ea\u5236\u00a77 \u00b7 \u4e0e\u666e\u901a\u98df\u7269\u4e0d\u5806\u53e0";

    /** 非食物却带戳 = 修复之前产出的旧物品，堆叠坏掉了，提示玩家可以扔掉。 */
    private static final String LINE_LEGACY = "\u00a7c\u5473\u857e\u4e50\u4e8b\u65e7\u6807\u8bb0\u00a77 \u00b7 \u65e0\u6cd5\u4e0e\u540c\u7c7b\u5806\u53e0";

    private static volatile boolean reported = false;

    public TasteJournalFix() {
    }

    /**
     * 复刻 tastejournal 自己认的"能吃"的定义（CommonEvents.isTasteableConsumedItem）：
     * 有食物属性、或者是"喝"动画的物品。
     */
    public static boolean isCulinary(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.has(DataComponents.FOOD)) {
            return true;
        }
        return stack.getUseAnimation() == UseAnim.DRINK;
    }

    /** 这个栈身上有没有 tastejournal 的标记。 */
    public static boolean isMarked(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(TAG_KEY);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        strip(event.getCrafting());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        strip(event.getSmelting());
    }

    /**
     * 纯显示层，不修改任何组件 —— 所以不会影响堆叠，也不会让问题变严重。
     */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!isMarked(stack)) {
            return;
        }
        event.getToolTip().add(Component.literal(isCulinary(stack) ? LINE_HOMEMADE : LINE_LEGACY));
    }

    /**
     * 只摘掉 tastejournal 那个子标签，绝不碰 custom_data 里的其它内容
     * （其它 mod 也可能往这里写东西）。
     */
    private static void strip(ItemStack stack) {
        if (isCulinary(stack)) {
            return;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.contains(TAG_KEY)) {
            return;
        }
        tag.remove(TAG_KEY);
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        reportOnce(stack);
    }

    private static void reportOnce(ItemStack stack) {
        if (!reported) {
            synchronized (TasteJournalFix.class) {
                if (!reported) {
                    reported = true;
                    System.out.println("[tastejournalfix] stripped the tastejournal tag from a non-food item"
                            + " - the fix is active. First hit: " + stack);
                }
            }
        }
    }
}
