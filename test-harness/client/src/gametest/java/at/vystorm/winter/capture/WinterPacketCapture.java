package at.vystorm.winter.capture;
import java.nio.file.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;
final class WinterPacketCapture {
    void runTest(ClientGameTestContext ctx) {
        Path dir=Path.of(System.getProperty("winter.capture.snapshots"));
        String server=System.getProperty("winter.capture.server");
        try {
            ctx.runOnClient(mc->{mc.getWindow().setWindowed(1920,1080);mc.options.renderDistance().set(8);mc.options.fov().set(60);mc.options.enableVsync().set(false);mc.options.framerateLimit().set(120);});
            int readyWait=0;while(!Files.exists(dir.resolve("ready"))&&readyWait++<3600)ctx.waitTicks(1);
            if(readyWait>=3600)throw new AssertionError("Server startup timeout");
            ctx.runOnClient(mc->ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(server),new ServerData("packet snow",server,ServerData.Type.OTHER),false,null));
            for(String stage:new String[]{"rain","snow","reconnected","excluded","resnow","restored"}) {
                int waited=0;while(!Files.exists(dir.resolve(stage+".ready"))&&waited++<3600)ctx.waitTicks(1);
                if(waited>=3600)throw new AssertionError("Server stage timeout: "+stage);
                boolean snow=stage.equals("snow")||stage.equals("reconnected")||stage.equals("resnow");
                if(stage.equals("reconnected")){
                    ctx.runOnClient(mc->mc.disconnectFromWorld(Component.literal("packet reconnect test")));
                    ctx.waitTicks(10);ctx.setScreen(TitleScreen::new);ctx.waitTicks(2);
                    ctx.runOnClient(mc->ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(server),new ServerData("packet snow",server,ServerData.Type.OTHER),false,null));
                }
                waited=0;
                while(waited++<1200){
                    boolean match=ctx.computeOnClient(mc->mc.level!=null&&mc.player!=null&&mc.gui.screen()==null&&mc.player.getX()<-40&&mc.level.isRaining()&&mc.level.getPrecipitationAt(new BlockPos(-44,106,32))==(snow?Biome.Precipitation.SNOW:Biome.Precipitation.RAIN));
                    if(match)break;ctx.waitTicks(1);
                }
                if(waited>=1200)throw new AssertionError("Expected "+(snow?"SNOW":"RAIN")+" for "+stage);
                if(stage.equals("rain"))ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F1);
                ctx.waitTicks(100);
                ctx.runOnClient(mc->{mc.gui.hud.getChat().clearMessages(false);mc.gui.hud.clearTitles();mc.gui.toastManager().clear();});
                ctx.waitTicks(2);
                var actual=ctx.computeOnClient(mc->mc.level.getPrecipitationAt(new BlockPos(-44,106,32)).name());
                if(!actual.equals(snow?"SNOW":"RAIN"))throw new AssertionError("Unstable precipitation "+actual);
                Path shot=ctx.takeScreenshot("packet-"+stage);
                Files.writeString(dir.resolve(stage+".captured"),shot.toAbsolutePath()+"\nprecipitation="+actual+"\n");
            }
            ctx.runOnClient(mc->{if(mc.level!=null)mc.disconnectFromWorld(Component.literal("packet test complete"));});
        ctx.waitTicks(10);ctx.setScreen(TitleScreen::new);ctx.waitTicks(2);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
