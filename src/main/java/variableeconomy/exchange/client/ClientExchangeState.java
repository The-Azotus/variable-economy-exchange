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
    /** Every offer for sale, once the screen has asked to browse them. */
    @Nullable
    private static ExchangePayloads.ExchangeBrowse browse;
    /** The player's history, once the screen has asked for it. */
    @Nullable
    private static ExchangePayloads.ExchangeHistory history;
    /** The server's clock less the client's, when the history came, so ages read right. */
    private static long clockOffset;
    /** The last request's outcome, for the tab that made it. */
    @Nullable
    private static ExchangePayloads.ExchangeOutcome lastOutcome;
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

    public static void applyBrowse(ExchangePayloads.ExchangeBrowse received) {
        browse = received;
        version++;
    }

    public static void applyHistory(ExchangePayloads.ExchangeHistory received) {
        history = received;
        clockOffset = received.now() - System.currentTimeMillis();
        version++;
    }

    public static void applyOutcome(ExchangePayloads.ExchangeOutcome outcome) {
        ExchangeModule.LOGGER.debug("Exchange outcome: {}", outcome);
        lastOutcome = outcome;
        status = message(outcome);
        statusIsError = outcome.result() != ExchangeResult.OK;
        version++;
    }

    /** What the status line says about an outcome. */
    static Component message(ExchangePayloads.ExchangeOutcome outcome) {
        // A listing is named as listed (a custom name, say); anything else by its item.
        Component itemName = outcome.listed().isEmpty()
                ? BuiltInRegistries.ITEM.get(outcome.item()).getDescription()
                : outcome.listed().getHoverName();
        Component amount = PriceFormat.money(PriceFormat.amount(outcome.amount()));
        if (outcome.result() == ExchangeResult.NOT_LISTED) {
            // Any item but coins can be traded here, so "not on the market" would mislead.
            return Component.translatable("screen.variable_economy_exchange.exchange.not_tradeable");
        }
        if (outcome.result() != ExchangeResult.OK) {
            return Component.translatable("screen.variable_economy_exchange.result." + outcome.result().name().toLowerCase(Locale.ROOT));
        }
        String p = "screen.variable_economy_exchange.exchange.";
        return switch (outcome.action()) {
            case POST -> outcome.filled() == 0
                    ? Component.translatable(p + "posted", outcome.quantity(), itemName)
                    : outcome.filled() < outcome.quantity()
                    ? Component.translatable(p + "posted_part", outcome.quantity(), itemName, outcome.filled(), amount,
                    outcome.quantity() - outcome.filled())
                    : Component.translatable(p + "filled", outcome.quantity(), itemName, amount);
            case CANCEL -> !outcome.listed().isEmpty()
                    ? Component.translatable(p + "cancelled_listing", outcome.quantity(), itemName)
                    : outcome.amount() > 0
                    ? Component.translatable(p + "cancelled_money", itemName, amount)
                    : Component.translatable(p + "cancelled_items", outcome.quantity(), itemName);
            case COLLECT -> Component.translatable(p + "collected", outcome.quantity());
            case LIST -> Component.translatable(p + "listed", outcome.quantity(), itemName);
            case BUY_LISTING -> Component.translatable(p + "bought_listing", outcome.quantity(), itemName, amount);
            case FILL -> Component.translatable(p + "filled_order", outcome.quantity(), itemName, amount);
            case REPRICE -> outcome.filled() > 0
                    ? Component.translatable(p + "repriced_traded", itemName, outcome.filled(), amount)
                    : Component.translatable(p + "repriced", itemName);
            case REDUCE -> !outcome.listed().isEmpty() || outcome.amount() == 0
                    ? Component.translatable(p + "reduced_items", itemName, outcome.quantity())
                    : Component.translatable(p + "reduced_money", itemName, outcome.quantity(), amount);
        };
    }

    /** When the exchange screen closes. */
    public static void clear() {
        state = new ExchangePayloads.ExchangeState(0, false);
        exchange = null;
        book = null;
        browse = null;
        history = null;
        lastOutcome = null;
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

    /** Every offer the player may see, or null until they've been received. */
    @Nullable
    public static ExchangePayloads.ExchangeBrowse browse() {
        return browse;
    }

    /** The player's history, newest first, or null until it's been received. */
    @Nullable
    public static ExchangePayloads.ExchangeHistory history() {
        return history;
    }

    /** The server's clock now, as near as the client can tell. */
    public static long serverNow() {
        return System.currentTimeMillis() + clockOffset;
    }

    /** The outcome of the player's last request, or null. */
    @Nullable
    public static ExchangePayloads.ExchangeOutcome lastOutcome() {
        return lastOutcome;
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

    /** Clears the status line, when the player moves on. */
    public static void clearStatus() {
        status = Component.empty();
        statusIsError = false;
    }

    /** Bumped on every change so the screen knows when to rebuild its lists. */
    public static int version() {
        return version;
    }
}
