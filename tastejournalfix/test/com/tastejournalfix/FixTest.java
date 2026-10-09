package com.tastejournalfix;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 拿 build/tastejournalfix-*.jar 里真实的 TasteJournalFix.class，
 * 配一套有真实行为的假 MC 类，验证 strip 与 tooltip 逻辑。
 */
public class FixTest {

    /** "自制" */
    private static final String K_HOMEMADE = "\u81ea\u5236";
    /** "不堆叠" */
    private static final String K_NO_STACK = "\u4e0d\u5806\u53e0";
    /** "旧标记" */
    private static final String K_LEGACY = "\u65e7\u6807\u8bb0";

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("---- 一、摘戳（非食物）----");
        stripTests();
        System.out.println();
        System.out.println("---- 二、tooltip（显示层）----");
        tooltipTests();
        System.out.println();
        System.out.println("---- 三、判据 ----");
        predicateTests();

        System.out.println();
        System.out.println("PASS " + pass + " / FAIL " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void stripTests() {
        // 1. 木板（非食物）被 tastejournal 盖了戳 -> 应整个摘掉 custom_data
        ItemStack planks = new ItemStack("minecraft:oak_planks");
        planks.with(DataComponents.CUSTOM_DATA, CustomData.of(
                new CompoundTag().put("tastejournal", "{maker: 069a79f4}")));
        TasteJournalFix.onItemCrafted(new PlayerEvent.ItemCraftedEvent(planks));
        check("木板(单点路径): custom_data 被整个摘掉", !planks.has(DataComponents.CUSTOM_DATA));

        // 2. 铁锭走 SmeltedEvent 路径
        ItemStack iron = new ItemStack("minecraft:iron_ingot");
        iron.with(DataComponents.CUSTOM_DATA, CustomData.of(
                new CompoundTag().put("tastejournal", "{maker: 069a79f4}")));
        TasteJournalFix.onItemSmelted(new PlayerEvent.ItemSmeltedEvent(iron));
        check("铁锭(烧炼路径): custom_data 被整个摘掉", !iron.has(DataComponents.CUSTOM_DATA));

        // 3. 真正的食物 -> 戳必须保留
        ItemStack bread = new ItemStack("minecraft:bread");
        bread.with(DataComponents.FOOD, new Object());
        bread.with(DataComponents.CUSTOM_DATA, CustomData.of(
                new CompoundTag().put("tastejournal", "{maker: 069a79f4}")));
        TasteJournalFix.onItemCrafted(new PlayerEvent.ItemCraftedEvent(bread));
        check("面包(有 FOOD 组件): tastejournal 戳保留", hasKey(bread, "tastejournal"));

        // 4. 饮品（DRINK 动画）-> 戳保留
        ItemStack tea = new ItemStack("somemod:green_tea");
        tea.anim(UseAnim.DRINK);
        tea.with(DataComponents.CUSTOM_DATA, CustomData.of(
                new CompoundTag().put("tastejournal", "{maker: 069a79f4}")));
        TasteJournalFix.onItemCrafted(new PlayerEvent.ItemCraftedEvent(tea));
        check("绿茶(DRINK 动画): tastejournal 戳保留", hasKey(tea, "tastejournal"));

        // 5. 木板，但 custom_data 里还有别的 mod 的数据 -> 只摘 tastejournal
        ItemStack planks2 = new ItemStack("minecraft:oak_planks");
        planks2.with(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()
                .put("tastejournal", "{maker: 069a79f4}")
                .put("othermod", "{keep: me}")));
        TasteJournalFix.onItemCrafted(new PlayerEvent.ItemCraftedEvent(planks2));
        check("木板+其它mod数据: tastejournal 被摘掉", !hasKey(planks2, "tastejournal"));
        check("木板+其它mod数据: othermod 原样保留", hasKey(planks2, "othermod"));

        // 6. 木板，custom_data 里没有 tastejournal -> 一个字节都不该动
        ItemStack planks3 = new ItemStack("minecraft:oak_planks");
        planks3.with(DataComponents.CUSTOM_DATA, CustomData.of(
                new CompoundTag().put("othermod", "{keep: me}")));
        CustomData before = planks3.get(DataComponents.CUSTOM_DATA);
        TasteJournalFix.onItemCrafted(new PlayerEvent.ItemCraftedEvent(planks3));
        check("木板无戳: custom_data 对象未被替换", planks3.get(DataComponents.CUSTOM_DATA) == before);

        // 7. 空栈不炸
        try {
            TasteJournalFix.onItemCrafted(new PlayerEvent.ItemCraftedEvent(new ItemStack(null)));
            check("空栈: 未抛异常", true);
        } catch (Throwable t) {
            check("空栈: 未抛异常 (" + t + ")", false);
        }
    }

    private static void tooltipTests() {
        // 1. 带戳的食物 -> 加一行解释为什么不堆叠
        ItemStack bread = new ItemStack("minecraft:bread");
        bread.with(DataComponents.FOOD, new Object());
        bread.with(DataComponents.CUSTOM_DATA, CustomData.of(
                new CompoundTag().put("tastejournal", "{maker: 069a79f4}")));
        ItemTooltipEvent e1 = new ItemTooltipEvent(bread);
        TasteJournalFix.onTooltip(e1);
        check("自制食物: tooltip 加了 1 行", e1.getToolTip().size() == 1);
        check("自制食物: 该行说明'自制'", lineContains(e1, K_HOMEMADE));
        check("自制食物: 该行说明'不堆叠'", lineContains(e1, K_NO_STACK));

        // 2. 带戳的非食物（修复前产出的旧物品）-> 加一行警示，方便找出来扔掉
        ItemStack oldPlanks = new ItemStack("minecraft:oak_planks");
        oldPlanks.with(DataComponents.CUSTOM_DATA, CustomData.of(
                new CompoundTag().put("tastejournal", "{maker: 069a79f4}")));
        ItemTooltipEvent e2 = new ItemTooltipEvent(oldPlanks);
        TasteJournalFix.onTooltip(e2);
        check("旧遗留物品: tooltip 加了 1 行", e2.getToolTip().size() == 1);
        check("旧遗留物品: 该行是旧标记警示", lineContains(e2, K_LEGACY));

        // 3. 不带戳的物品 -> 一行都不加（不能给所有物品都挂提示）
        ItemTooltipEvent e3 = new ItemTooltipEvent(new ItemStack("minecraft:stone"));
        TasteJournalFix.onTooltip(e3);
        check("普通物品: tooltip 未改动", e3.getToolTip().isEmpty());

        // 4. 串联：新合成的木板先被摘戳，再看 tooltip -> 也不该有提示
        ItemStack freshPlanks = new ItemStack("minecraft:oak_planks");
        freshPlanks.with(DataComponents.CUSTOM_DATA, CustomData.of(
                new CompoundTag().put("tastejournal", "{maker: 069a79f4}")));
        TasteJournalFix.onItemCrafted(new PlayerEvent.ItemCraftedEvent(freshPlanks));
        ItemTooltipEvent e4 = new ItemTooltipEvent(freshPlanks);
        TasteJournalFix.onTooltip(e4);
        check("修复后新合成的木板: tooltip 无提示", e4.getToolTip().isEmpty());

        // 5. tooltip 必须是纯显示层：不得改动组件对象
        CustomData beforeBread = bread.get(DataComponents.CUSTOM_DATA);
        ItemTooltipEvent e5 = new ItemTooltipEvent(bread);
        TasteJournalFix.onTooltip(e5);
        check("tooltip: 未替换 custom_data 组件对象",
                bread.get(DataComponents.CUSTOM_DATA) == beforeBread);
        check("tooltip: 未摘掉食物身上的戳", hasKey(bread, "tastejournal"));

        // 6. 同一帧内多次渲染 = 多次全新调用。
        //    原版证据：NeoForge patch 版 ItemStack.getTooltipLines 里
        //        ArrayList list = Lists.newArrayList();               // 每次调用新建
        //        EventHooks.onItemTooltip(this, player, list, ...);   // 事件拿到这个新 list
        //    所以每次渲染只会被加一行，不会累积。
        for (int i = 0; i < 3; i++) {
            ItemTooltipEvent render = new ItemTooltipEvent(bread);
            TasteJournalFix.onTooltip(render);
            check("第 " + (i + 1) + " 次渲染: 恰好 1 行", render.getToolTip().size() == 1);
        }

        // 7. tooltip 里已有别人的行时，我们只追加，不覆盖
        ItemTooltipEvent e7 = new ItemTooltipEvent(bread);
        e7.getToolTip().add(Component.literal("other mod's line"));
        TasteJournalFix.onTooltip(e7);
        check("已有其它行: 追加而不是覆盖", e7.getToolTip().size() == 2);
        check("已有其它行: 别人的行还在", e7.getToolTip().get(0).toString().equals("other mod's line"));
    }

    private static void predicateTests() {
        check("isCulinary(木板)==false", !TasteJournalFix.isCulinary(new ItemStack("minecraft:oak_planks")));
        ItemStack food = new ItemStack("minecraft:apple").with(DataComponents.FOOD, new Object());
        check("isCulinary(苹果)==true", TasteJournalFix.isCulinary(food));
        check("isCulinary(绿茶)==true", TasteJournalFix.isCulinary(new ItemStack("m:tea").anim(UseAnim.DRINK)));
        check("isCulinary(玻璃)==false", !TasteJournalFix.isCulinary(new ItemStack("minecraft:glass")));

        check("isMarked(带戳木板)==true", TasteJournalFix.isMarked(
                new ItemStack("minecraft:oak_planks").with(DataComponents.CUSTOM_DATA,
                        CustomData.of(new CompoundTag().put("tastejournal", "{}")))));
        check("isMarked(无戳石头)==false", !TasteJournalFix.isMarked(new ItemStack("minecraft:stone")));
        check("isMarked(只有别的mod数据)==false", !TasteJournalFix.isMarked(
                new ItemStack("minecraft:stone").with(DataComponents.CUSTOM_DATA,
                        CustomData.of(new CompoundTag().put("othermod", "{}")))));
    }

    private static boolean lineContains(ItemTooltipEvent event, String keyword) {
        for (int i = 0; i < event.getToolTip().size(); i++) {
            if (event.getToolTip().get(i).toString().contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasKey(ItemStack stack, String key) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(key);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [OK]   " + what);
        } else {
            fail++;
            System.out.println("  [FAIL] " + what);
        }
    }
}
