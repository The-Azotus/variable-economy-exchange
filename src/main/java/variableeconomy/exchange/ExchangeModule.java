package variableeconomy.exchange;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import variableeconomy.core.api.event.BalanceChangedEvent;
import variableeconomy.core.api.event.ItemGateChangedEvent;
import variableeconomy.exchange.block.ExchangeBlocks;
import variableeconomy.exchange.command.ExchangeCommands;
import variableeconomy.exchange.menu.ExchangeMenu;
import variableeconomy.exchange.network.ExchangeNetwork;
import variableeconomy.exchange.network.ExchangeSync;
import variableeconomy.exchange.service.ExchangeService;

/**
 * The player exchange: buy and sell orders, listings and the collection box. Registers its block, menu, packets,
 * commands, net-worth contributor and server events.
 */
@Mod(ExchangeModule.MOD_ID)
public final class ExchangeModule {
    /** The exchange's namespace for its block, menu, packets and saved data. */
    public static final String MOD_ID = "variable_economy_exchange";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** The mod's entry point: registers its server config, then its blocks, menus, packets, commands and server events. */
    public ExchangeModule(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, ExchangeConfig.SPEC, ExchangeConfig.FILE);
        ExchangeConfig.install();
        ExchangeService.registerNetWorth();
        ExchangeMenu.MENUS.register(modBus);
        ExchangeBlocks.register(modBus);
        modBus.addListener(ExchangeNetwork::register);

        // What a player has unlocked decides what they may see and post.
        NeoForge.EVENT_BUS.addListener((ItemGateChangedEvent event) -> ExchangeSync.locksChanged(event.getServer(), event.getPlayer()));
        // An open exchange shows a new balance straight away, whatever changed it.
        NeoForge.EVENT_BUS.addListener((BalanceChangedEvent event) -> ExchangeSync.balanceChanged(event.getServer(), event.getAccount()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> ExchangeCommands.register(event.getDispatcher(), event.getBuildContext()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            if (ExchangeService.tick(event.getServer())) {
                ExchangeSync.broadcastExchange(event.getServer());
            }
            ExchangeSync.tick(event.getServer());
        });
        // How an order ended while its owner was away, and what waits in their box.
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ExchangeService.loggedIn(player);
            }
        });
    }
}
