package variableeconomy.exchange.client;

import variableeconomy.exchange.ExchangeModule;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import variableeconomy.exchange.menu.ExchangeMenu;

/** The exchange's client side: its screen. Loaded only on the client. */
@Mod(value = ExchangeModule.MOD_ID, dist = Dist.CLIENT)
public final class ExchangeClient {
    /** The mod's client entry point, constructed on the client only. */
    public ExchangeClient(IEventBus modBus, ModContainer container) {
        modBus.addListener((RegisterMenuScreensEvent event) -> event.register(ExchangeMenu.TYPE.get(), ExchangeScreen::new));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientExchangeState.clear());
    }
}
