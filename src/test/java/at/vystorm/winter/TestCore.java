package at.vystorm.winter;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.MessageInterface;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;
import Vystorm.vystorm_core._uiMethods.settings.SettingsInterface;
import Vystorm.vystorm_core.compat.CompatInterface;

/** A Core hub for tests without a server: the parts the menus use delegate to Core's static code as before. */
final class TestCore {
    private TestCore() {}

    static API api() {
        API api = mock(API.class);
        when(api.compat()).thenReturn(new CompatInterface() {});
        when(api.message()).thenReturn(mock(MessageInterface.class, CALLS_REAL_METHODS));
        when(api.settings()).thenReturn(mock(SettingsInterface.class, CALLS_REAL_METHODS));
        when(api.gui()).thenReturn(mock(GuiInteface.class, CALLS_REAL_METHODS));
        return api;
    }
}
