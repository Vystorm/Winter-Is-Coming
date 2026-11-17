package at.vystorm.winter;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelContext;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.entity.Player;
import static org.mockito.Mockito.*;

final class PanelTestSupport {
    static final String[] LANGUAGES = {"de", "en", "fr", "it", "pt_br", "ru", "zh_cn"};

    static PanelContext context(Player player, String code) throws Exception {
        Map<String, String> files = new HashMap<>();
        for (String language : LANGUAGES) {
            try (var in = PanelTestSupport.class.getResourceAsStream("/lang/" + language + ".yml")) {
                files.put(language, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        Lang lang = Lang.create("winteriscoming", files);
        PanelContext ctx = mock(PanelContext.class);
        when(ctx.player()).thenReturn(player);
        when(ctx.text(anyString(), any(Object[].class))).thenAnswer(call ->
                lang.plain(code, call.getArgument(0), placeholders(call.getArguments())));
        when(ctx.text(anyString())).thenAnswer(call -> lang.plain(code, call.getArgument(0)));
        when(ctx.component(anyString(), any(Object[].class))).thenAnswer(call ->
                lang.component(code, call.getArgument(0), placeholders(call.getArguments())));
        return ctx;
    }

    private static Object[] placeholders(Object[] args) {
        return java.util.Arrays.copyOfRange(args, 1, args.length);
    }
}
