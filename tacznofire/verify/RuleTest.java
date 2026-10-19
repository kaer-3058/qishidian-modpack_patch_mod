import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Functional test of TaczNoFire's rule engine, run by verify.ps1 outside the game.
 *
 * The rule class is private inside the mod, so it is driven through reflection -- but it is
 * the real compiled code from the built jar, not a copy. It matches on the pure-String
 * overload on purpose: pulling in net.minecraft.resources.ResourceLocation would drag
 * Minecraft's own bootstrap (Codec, FMLEnvironment, modlauncher) into a plain JVM, and the
 * matching logic itself has nothing to do with Minecraft. The thin ResourceLocation adapter
 * is checked against the bytecode by verify.ps1 instead.
 */
public final class RuleTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Class<?> ruleClass = Class.forName("com.tacznofire.TaczNoFire$Rule");
        Method parse = ruleClass.getDeclaredMethod("parse", List.class);
        parse.setAccessible(true);
        Method blocks = ruleClass.getDeclaredMethod("blocks", String.class, String.class);
        blocks.setAccessible(true);
        Field logField = ruleClass.getDeclaredField("log");
        logField.setAccessible(true);
        Method describe = ruleClass.getDeclaredMethod("describe");
        describe.setAccessible(true);

        // 1) exactly the file the mod writes on first run
        Object shipped = parse.invoke(null, List.of(
                "# ===== TACZ 禁用开火 配置 =====",
                "mode = default_pack",
                "block_namespaces = tacz",
                "block_guns =",
                "log_blocked = true",
                "lore_text = 损坏，无法开枪"));
        expect(blocks, shipped, "tacz", "tacz:ak47", true, "built-in pack gun must be blocked");
        expect(blocks, shipped, "tacz", "tacz:m4a1", true, "built-in pack gun must be blocked");
        expect(blocks, shipped, "tacz", "tacz:vector45", true, "built-in pack gun must be blocked");
        expect(blocks, shipped, "1001", "1001:lyr", false, "third-party pack must stay usable");
        expect(blocks, shipped, "1001", "1001:m249", false, "third-party pack must stay usable");
        expect(blocks, shipped, "minecraft", "minecraft:carrot_on_a_stick", false, "non-TACZ items untouched");
        check("shipped config logs blocks", Boolean.TRUE.equals(logField.get(shipped)));
        check("shipped config shows the lore line", ((String) describe.invoke(shipped)).contains("损坏，无法开枪"));

        // 1b) the lore line: default when absent, verbatim when set, off when empty
        Object noKey = parse.invoke(null, List.of("mode = default_pack", "block_namespaces = tacz"));
        check("missing lore_text falls back to the default text",
                ((String) describe.invoke(noKey)).contains("损坏，无法开枪"));
        Object customLore = parse.invoke(null, List.of("lore_text =   已损坏  "));
        check("lore_text is taken verbatim (surrounding spaces trimmed)",
                ((String) describe.invoke(customLore)).contains("已损坏")
                        && !((String) describe.invoke(customLore)).contains("损坏，无法开枪"));
        Object noLore = parse.invoke(null, List.of("lore_text ="));
        check("empty lore_text turns the line off", ((String) describe.invoke(noLore)).contains("不加提示行"));

        // 2) mode = all -> every TACZ gun, third-party packs included
        Object all = parse.invoke(null, List.of("mode = all"));
        expect(blocks, all, "tacz", "tacz:ak47", true, "mode=all blocks built-in pack");
        expect(blocks, all, "1001", "1001:lyr", true, "mode=all blocks third-party pack");

        // 3) extra namespaces
        Object extra = parse.invoke(null, List.of("mode = default_pack", "block_namespaces = tacz,1001"));
        expect(blocks, extra, "1001", "1001:lyr", true, "extra namespace is blocked");
        expect(blocks, extra, "tacz", "tacz:ak47", true, "built-in pack still blocked");

        // 4) explicit gun ids only
        Object named = parse.invoke(null, List.of("mode = default_pack", "block_namespaces =", "block_guns = tacz:ak47, 1001:lyr"));
        expect(blocks, named, "tacz", "tacz:ak47", true, "named gun blocked");
        expect(blocks, named, "1001", "1001:lyr", true, "named gun blocked (with space after comma)");
        expect(blocks, named, "tacz", "tacz:m4a1", false, "other gun of the same pack allowed");

        // 5) sloppy but legal input: comments, spaces, upper case, unknown keys, empty lines
        Object sloppy = parse.invoke(null, List.of(
                "",
                "   # a comment",
                "MODE = ALL   # trailing comment",
                "block_namespaces=tacz",
                "some_future_key = 42"));
        expect(blocks, sloppy, "1001", "1001:lyr", true, "mode=ALL with comments and spaces");

        // 6) log switch
        Object quiet = parse.invoke(null, List.of("log_blocked = false"));
        check("log_blocked=false turns logging off", Boolean.FALSE.equals(logField.get(quiet)));
        Object loud = parse.invoke(null, List.of("log_blocked = true"));
        check("log_blocked=true turns logging on", Boolean.TRUE.equals(logField.get(loud)));

        if (failures > 0) {
            System.out.println("RuleTest: " + failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("RuleTest: all rule-engine cases passed");
    }

    private static void expect(Method blocks, Object rule, String namespace, String gunId, boolean wanted, String what)
            throws Exception {
        boolean actual = (Boolean) blocks.invoke(rule, namespace, gunId);
        check(what + " [" + gunId + " -> " + actual + "]", actual == wanted);
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.println("    " + (ok ? "PASS" : "FAIL") + "  " + what);
    }
}
