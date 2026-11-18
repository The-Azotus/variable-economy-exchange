package variableeconomy.exchange.client;

import variableeconomy.exchange.ExchangeModule;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import variableeconomy.exchange.network.ExchangePayloads;
import variableeconomy.exchange.service.ExchangeResult;

import java.util.Locale;

/**
 * What the client knows about the exchange, filled only from server packets. Display only: every request is checked
 * again on the server. Render thread only. Uses no client-only classes, so the network code can reference it on both
 * sides.
 */
public final class ClientExchangeState {
    private static ExchangePayloads.ExchangeState state = new ExchangePayloads.ExchangeState(0, false);
    /** The exchange, once the screen has asked for it. */
    @Nullable
    private static ExchangePayloads.Exchange exchange;
    /** The book of the item the screen last asked about. */
    @Nullable
    private static ExchangePayloads.ExchangeBook book;
    /** Every listing, once the screen has asked to browse them. */
    @Nullable
    private static ExchangePayloads.ExchangeListings listings;
    private static Component status = Component.empty();
    private static boolean statusIsError;
    private static int version;

    private ClientExchangeState() {
    }

    public static void applyState(ExchangePayloads.ExchangeState received) {
        state = received;
        version++;
    }

    public static void applyExchange(ExchangePayloads.Exchange received) {
        exchange = received;
        version++;
    }

    public static void applyBook(ExchangePayloads.ExchangeBook received) {
        book = received;
        version++;
    }

    public static void applyListings(ExchangePayloads.ExchangeListings received) {
        listings = received;
        version++;
    }

    public static void applyOutcome(ExchangePayloads.ExchangeOutcome outcome) {
        ExchangeModule.LOGGER.debug("Exchange outcome: {}", outcome);
        // A listing is named as listed (a custom name, say); anything else by its item.
        Component itemName = outcome.listed().isEmpty()
                ? BuiltInRegistries.ITEM.get(outcome.item()).getDescription()
                : outcome.listed().getHoverName();
        Component amount = PriceFormat.money(PriceFormat.amount(outcome.amount()));
        if (outcome.result() == ExchangeResult.OK) {
            status = switch (outcome.action()) {
                case POST -> outcome.filled() == 0
                        ? Component.translatable("screen.variable_economy_exchange.exchange.posted", outcome.quantity(), itemName)
                        : outcome.filled() < outcome.quantity()
                        ? Component.translatable("screen.variable_economy_exchange.exchange.posted_part", outcome.quantity(), itemName, outcome.filled(), amount)
                        : Component.translatable("screen.variable_economy_exchange.exchange.filled", outcome.quantity(), itemName, amount);
                case CANCEL -> !outcome.listed().isEmpty()
                        ? Component.translatable("screen.variable_economy_exchange.exchange.cancelled_listing", outcome.quantity(), itemName)
                        : outcome.amount() > 0
                        ? Component.translatable("screen.variable_economy_exchange.exchange.cancelled_money", itemName, amount)
                        : Component.translatable("screen.variable_economy_exchange.exchange.cancelled_items", outcome.quantity(), itemName);
                case COLLECT -> Component.translatable("screen.variable_economy_exchange.exchange.collected", outcome.quantity());
                case LIST -> Component.translatable("screen.variable_economy_exchange.exchange.listed", outcome.quantity(), itemName);
                case BUY_LISTING -> Component.translatable("screen.variable_economy_exchange.exchange.bought_listing", outcome.quantity(), itemName, amount);
                case FILL -> Component.translatable("screen.variable_economy_exchange.exchange.filled_order", outcome.quantity(), itemName, amount);
            };
            statusIsError = false;
        } else if (outcome.result() == ExchangeResult.NOT_LISTED) {
            // Any item but coins can be traded here, so "not on the market" would mislead.
            status = Component.translatable("screen.variable_economy_exchange.exchange.not_tradeable");
            statusIsError = true;
        } else {
            status = Component.translatable("screen.variable_economy_exchange.result." + outcome.result().name().toLowerCase(Locale.ROOT));
            statusIsError = true;
        }
        version++;
    }

    /** When the exchange screen closes. */
    public static void clear() {
        state = new ExchangePayloads.ExchangeState(0, false);
        exchange = null;
        book = null;
        listings = null;
        status = Component.empty();
        statusIsError = false;
        version++;
    }

    /** The exchange, or null until it's been received. */
    @Nullable
    public static ExchangePayloads.Exchange exchange() {
        return exchange;
    }

    /** The book for {@code item}, if that's the one last received. */
    @Nullable
    public static ExchangePayloads.ExchangeBook book(ResourceLocation item) {
        return book != null && book.item().equals(item) ? book : null;
    }

    /** Every listing the player may see, or null until they've been received. */
    @Nullable
    public static ExchangePayloads.ExchangeListings listings() {
        return listings;
    }

    /** Whether the server runs the exchange. */
    public static boolean enabled() {
        return state.enabled();
    }

    /** The balance of the account the player trades with. */
    public static long balance() {
        return state.balance();
    }

    /** Whether the balance looks enough for {@code total}. The server checks again. */
    public static boolean looksAffordable(long total) {
        return state.balance() >= total;
    }

    public static Component status() {
        return status;
    }

    public static boolean statusIsError() {
        return statusIsError;
    }

    /** Bumped on every change so the screen knows when to rebuild its list. */
    public static int version() {
        return version;
    }
}
