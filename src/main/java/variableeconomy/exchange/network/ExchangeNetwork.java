package variableeconomy.exchange.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import variableeconomy.exchange.client.ClientExchangeState;

/** The exchange's packets. */
public final class ExchangeNetwork {
    private static final String PROTOCOL_VERSION = "2";

    private ExchangeNetwork() {
    }

    /**
     * Handlers run on the main thread (the registrar default), which the exchange service requires. The clientbound
     * handlers are lambdas rather than method references so a dedicated server never loads the client classes.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(ExchangePayloads.ExchangeRequest.TYPE, ExchangePayloads.ExchangeRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                ExchangeSync.handleExchangeRequest(payload, player);
            }
        });
        registrar.playToServer(ExchangePayloads.ExchangeAction.TYPE, ExchangePayloads.ExchangeAction.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                ExchangeSync.handleExchangeAction(payload, player);
            }
        });
        registrar.playToServer(ExchangePayloads.ListItem.TYPE, ExchangePayloads.ListItem.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                ExchangeSync.handleListItem(payload, player);
            }
        });
        registrar.playToClient(ExchangePayloads.ExchangeState.TYPE, ExchangePayloads.ExchangeState.CODEC,
                (payload, context) -> ClientExchangeState.applyState(payload));
        registrar.playToClient(ExchangePayloads.Exchange.TYPE, ExchangePayloads.Exchange.CODEC,
                (payload, context) -> ClientExchangeState.applyExchange(payload));
        registrar.playToClient(ExchangePayloads.ExchangeBook.TYPE, ExchangePayloads.ExchangeBook.CODEC,
                (payload, context) -> ClientExchangeState.applyBook(payload));
        registrar.playToClient(ExchangePayloads.ExchangeBrowse.TYPE, ExchangePayloads.ExchangeBrowse.CODEC,
                (payload, context) -> ClientExchangeState.applyBrowse(payload));
        registrar.playToClient(ExchangePayloads.ExchangeHistory.TYPE, ExchangePayloads.ExchangeHistory.CODEC,
                (payload, context) -> ClientExchangeState.applyHistory(payload));
        registrar.playToClient(ExchangePayloads.ExchangeOutcome.TYPE, ExchangePayloads.ExchangeOutcome.CODEC,
                (payload, context) -> ClientExchangeState.applyOutcome(payload));
    }
}
