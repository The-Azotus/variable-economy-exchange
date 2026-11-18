package variableeconomy.exchange.service;

/** How an exchange request went. Only {@link #OK} changed anything. */
public enum ExchangeResult {
    OK,
    /** The item can't be traded here (a coin), or is locked for the player; the reply doesn't say which. */
    NOT_LISTED,
    NOT_ENOUGH_ITEMS,
    /** A listing or order has fewer units left than asked for. */
    NO_STOCK,
    NO_FUNDS,
    NO_SPACE,
    /** A listing's price isn't the one the buyer saw. */
    PRICE_CHANGED,
    /** More than one order may ask for, or a total larger than one payment. */
    TOO_LARGE,
    /** Quantity was zero or negative. */
    INVALID_QUANTITY,
    /** The bank can't be reached. The mod's own bank always can, so this isn't returned today. */
    BANK_UNAVAILABLE,
    /** Turned off on this server. */
    EXCHANGE_CLOSED,
    /** The player already has as many open orders and listings as they may. */
    ORDER_LIMIT,
    /** The order is no longer open: it filled, or was cancelled or expired. */
    ORDER_GONE,
    /** The order is someone else's. */
    NOT_YOUR_ORDER,
    /** A price of zero or less. */
    INVALID_PRICE,
    /** Buying from one's own listing. */
    OWN_LISTING,
    /** Filling one's own order. */
    OWN_ORDER
}
