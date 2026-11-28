package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.item.RewardPouch;
import Vystorm.vystorm_core.lang.Lang;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** /winter through Core's command tree with the real language files: names, routing, help per language. */
class WinterCommandsTest {
    private static final String[] ALL = {"winter.admin", "winter.claim", "winter.calendar", "winter.snowball"};
    private final List<String> chat = new ArrayList<>();

    private static String read(String name) throws Exception {
        try (InputStream in = WinterCommandsTest.class.getResourceAsStream("/lang/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Lang lang() throws Exception {
        return Lang.create("WinterIsComing", Map.of("en", read("en.yml"), "de", read("de.yml"), "zh_cn", read("zh_cn.yml")));
    }

    private CommandRegistration registration(WinterPlugin plugin) throws Exception {
        Plugin owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("WinterIsComing");
        return CommandTesting.detached(owner, lang(), WinterPlugin.commandSpec(plugin));
    }

    private Player player(Locale locale, String... permissions) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenAnswer(i -> List.of(permissions).contains((String) i.getArgument(0)));
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(p).sendMessage(any(Component.class));
        return p;
    }

    private ConsoleCommandSender console() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(console).sendMessage(any(Component.class));
        return console;
    }

    @Test
    void givenLanguages_whenCompleting_thenSubcommandsInPlayersLanguage() throws Exception {
        CommandRegistration r = registration(null);
        String[] none = {""};
        assertEquals(List.of("status", "warmth", "preview", "setdate", "resume", "thaw", "admin", "lamp", "reload", "claim", "claims", "calendar", "snowball", "duel", "surrender", "storm", "spot", "help"),
                r.suggest(player(Locale.US, ALL), "winter", none));
        assertEquals(List.of("status", "wärme", "abholen", "geschenke", "kalender", "schneeball", "duell", "aufgeben", "sturm", "hilfe"),
                r.suggest(player(Locale.GERMANY, "winter.claim", "winter.calendar", "winter.snowball"), "winterzeit", none));
        assertEquals(List.of("状态", "保暖", "预览", "设定日期", "恢复", "解冻", "管理", "彩灯", "重载", "领取", "礼物", "日历", "雪球", "决斗", "认输", "风暴", "季节区域", "帮助"),
                r.suggest(player(Locale.SIMPLIFIED_CHINESE, ALL), "凛冬将至", none));
        assertEquals(List.of("claim", "claims"), r.suggest(player(Locale.US), "winter", new String[]{"cl"}), "claims is offered without admin");
        assertFalse(r.suggest(player(Locale.US), "winter", none).contains("duel"));
    }

    @Test
    void givenOldEnglishCommands_whenRun_thenSameHandlersWithSameArguments() throws Exception {
        WinterPlugin plugin = mock(WinterPlugin.class);
        CommandRegistration r = registration(plugin);
        Player p = player(Locale.US, ALL);
        r.execute(p, "winter", new String[0]);
        verify(plugin).commandRoot(p);
        r.execute(p, "winter", new String[]{"status"});
        r.execute(p, "winter", new String[]{"STATUS"});
        verify(plugin, times(2)).commandStatus(p);
        r.execute(p, "winter", new String[]{"preview", "2026-12-25"});
        verify(plugin).commandPreview(p, "2026-12-25");
        r.execute(p, "winter", new String[]{"setdate", "2026-12-24"});
        verify(plugin).commandSetDate(p, "2026-12-24");
        r.execute(p, "winter", new String[]{"resume"});
        verify(plugin).commandResume(p);
        r.execute(p, "winter", new String[]{"thaw"});
        verify(plugin).commandThaw(p);
        r.execute(p, "winter", new String[]{"reload"});
        verify(plugin).commandReload(p);
        r.execute(p, "winter", new String[]{"claim"});
        verify(plugin).claim(p);
        r.execute(p, "winter", new String[]{"claims"});
        verify(plugin).commandClaims(p);
        r.execute(p, "winter", new String[]{"calendar"});
        verify(plugin).commandCalendar(p);
        r.execute(p, "winter", new String[]{"snowball"});
        verify(plugin).commandSnowball(p);
        r.execute(p, "winter", new String[]{"duel", "Alex"});
        verify(plugin).commandDuel(p, "Alex");
    }

    @Test
    void givenLocalizedNames_whenRun_thenSameHandlers() throws Exception {
        WinterPlugin plugin = mock(WinterPlugin.class);
        CommandRegistration r = registration(plugin);
        Player german = player(Locale.GERMANY, ALL);
        r.execute(german, "winterzeit", new String[]{"kalender"});
        verify(plugin).commandCalendar(german);
        r.execute(german, "winterzeit", new String[]{"vorschau", "2026-12-25"});
        verify(plugin).commandPreview(german, "2026-12-25");
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, ALL);
        r.execute(chinese, "凛冬将至", new String[]{"决斗", "Alex"});
        verify(plugin).commandDuel(chinese, "Alex");
    }

    @Test
    void givenConsole_whenRun_thenAdminCommandsWorkAndPlayerCommandsRefused() throws Exception {
        WinterPlugin plugin = mock(WinterPlugin.class);
        CommandRegistration r = registration(plugin);
        ConsoleCommandSender console = console();
        // The acceptance harness drives the plugin with these console commands.
        r.execute(console, "winter", new String[]{"reload"});
        r.execute(console, "winter", new String[]{"thaw"});
        r.execute(console, "winter", new String[]{"setdate", "2026-12-24"});
        r.execute(console, "winter", new String[]{"preview", "2026-12-25"});
        verify(plugin).commandReload(console);
        verify(plugin).commandThaw(console);
        verify(plugin).commandSetDate(console, "2026-12-24");
        verify(plugin).commandPreview(console, "2026-12-25");
        for (String sub : List.of("calendar", "snowball")) r.execute(console, "winter", new String[]{sub});
        r.execute(console, "winter", new String[]{"duel", "Alex"});
        verify(plugin, never()).commandCalendar(any());
        verify(plugin, never()).commandSnowball(any());
        verify(plugin, never()).commandDuel(any(), any());
        assertEquals(List.of("Only players can do that.", "Only players can do that.", "Only players can do that."), chat);
    }

    @Test
    void givenSurplusOrMissingArguments_whenRun_thenNothingChanges() throws Exception {
        WinterPlugin plugin = mock(WinterPlugin.class);
        CommandRegistration r = registration(plugin);
        CommandSender sender = console();
        for (String action : new String[]{"thaw", "resume", "reload", "claim", "calendar"}) r.execute(sender, "winter", new String[]{action, "typo"});
        r.execute(sender, "winter", new String[]{"setdate"});
        r.execute(player(Locale.US, ALL), "winter", new String[]{"duel"});
        verify(plugin, never()).commandThaw(any());
        verify(plugin, never()).commandResume(any());
        verify(plugin, never()).commandReload(any());
        verify(plugin, never()).claim(any());
        verify(plugin, never()).commandSetDate(any(), any());
        verify(plugin, never()).commandDuel(any(), any());
    }

    @Test
    void givenPlayerWithClaimPermission_whenClaims_thenPendingGiftsWithoutAdmin() throws Exception {
        WinterPlugin plugin = mock(WinterPlugin.class, CALLS_REAL_METHODS);
        Lang lang = mock(Lang.class);
        RewardPouch gifts = mock(RewardPouch.class);
        set(plugin, "lang", lang);
        set(plugin, "gifts", gifts);
        Player player = player(Locale.US, "winter.claim");
        when(gifts.pending(player.getUniqueId())).thenReturn(2);
        registration(plugin).execute(player, "winter", new String[]{"claims"});
        verify(lang).send(player, "claims", "pending", 2);
        verify(lang, never()).send(player, "denied");
    }

    @Test
    void givenInvalidDate_whenPreviewOrSetdate_thenClearMessageWithoutStateChange() throws Exception {
        WinterPlugin plugin = mock(WinterPlugin.class, CALLS_REAL_METHODS);
        Lang lang = mock(Lang.class);
        set(plugin, "lang", lang);
        CommandSender sender = console();
        for (String action : new String[]{"preview", "setdate"}) registration(plugin).execute(sender, "winter", new String[]{action, "2026-02-30"});
        verify(lang, times(2)).send(sender, "invalid-date", "input", "2026-02-30");
        verify(lang, never()).send(eq(sender), eq("invalid"), any(Object[].class));
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsageAndDescriptions() throws Exception {
        registration(null).execute(player(Locale.GERMANY, "winter.snowball"), "winterzeit", new String[]{"hilfe"});
        String all = String.join("\n", chat);
        assertTrue(all.contains("/winterzeit duell SPIELER – Einen Spieler zum Schneeballduell herausfordern"), all);
        assertTrue(all.contains("/winterzeit abholen – Das heutige Feiertagsgeschenk"), all);
        assertFalse(all.contains("neuladen"), all);
    }

    @Test
    void givenRootAliases_whenRead_thenGermanAndChinese() throws Exception {
        Lang lang = lang();
        assertEquals("winterzeit", lang.raw("de", "commands.winter.aliases"));
        assertEquals("凛冬将至", lang.raw("zh_cn", "commands.winter.aliases"));
    }

    @Test
    void givenTree_whenChecked_thenNoNameConflicts() throws Exception {
        assertEquals(List.of(), registration(null).conflicts());
    }

    @Test
    void givenLanguageFiles_whenCompared_thenSameCommandKeysAndADescriptionForEveryNode() throws Exception {
        Set<String> en = commandKeys("en.yml");
        assertEquals(en, commandKeys("de.yml"));
        assertEquals(en, commandKeys("zh_cn.yml"));
        List<String> paths = new ArrayList<>();
        collect(WinterPlugin.commandSpec(null), paths);
        for (String file : List.of("en.yml", "de.yml", "zh_cn.yml")) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(read(file)));
            for (String path : paths) assertFalse(yaml.getString(path + ".description", "").isBlank(), file + ": " + path);
        }
    }

    @Test
    void surrenderRoutesAllLanguagesAndRejectsMissingPermissionConsoleAndExtraArguments() throws Exception {
        WinterPlugin plugin = mock(WinterPlugin.class);
        CommandRegistration r = registration(plugin);
        Player english = player(Locale.US, "winter.snowball");
        Player german = player(Locale.GERMANY, "winter.snowball");
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, "winter.snowball");
        r.execute(english, "winter", new String[]{"surrender"});
        r.execute(german, "winterzeit", new String[]{"aufgeben"});
        r.execute(chinese, "凛冬将至", new String[]{"认输"});
        verify(plugin).commandSurrender(english);
        verify(plugin).commandSurrender(german);
        verify(plugin).commandSurrender(chinese);
        clearInvocations(plugin);
        r.execute(player(Locale.US), "winter", new String[]{"surrender"});
        r.execute(console(), "winter", new String[]{"surrender"});
        r.execute(english, "winter", new String[]{"surrender", "Alex"});
        verifyNoInteractions(plugin);
        assertFalse(r.suggest(player(Locale.US), "winter", new String[]{""}).contains("surrender"));
    }

    private static void collect(CommandSpec node, List<String> out) {
        out.add(node.langPath());
        for (CommandSpec child : node.children().values()) collect(child, out);
    }

    private static Set<String> commandKeys(String file) throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(read(file)));
        return new TreeSet<>(yaml.getConfigurationSection("commands").getKeys(true));
    }

    private static void set(WinterPlugin plugin, String name, Object value) throws Exception {
        var field = WinterPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
