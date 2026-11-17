package at.vystorm.winter;

import Vystorm.vystorm_core.lang.Lang;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WinterFunCommandTest {
    @Test void englishAndGermanHaveTheSameKeys() throws Exception {
        assertEquals(keys("/lang/en.yml"),keys("/lang/de.yml"));
    }

    @Test void calendarDoorsMapBackToTheirDay() {
        int[] layout=at.vystorm.winter.season.AdventCalendar.layout();
        for(int i=0;i<layout.length;i++)assertEquals(i+1,CalendarMenu.dayAt(layout[i]));
        for(int slot=45;slot<54;slot++)assertEquals(0,CalendarMenu.dayAt(slot));
    }

    private static java.util.Set<String> keys(String resource) throws Exception {
        try(var in=WinterFunCommandTest.class.getResourceAsStream(resource)){
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in,StandardCharsets.UTF_8)).getKeys(true);
        }
    }

    private static void set(WinterPlugin plugin,String name,Object value) throws Exception {
        var field=WinterPlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(plugin,value);
    }
}
