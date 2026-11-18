package variableeconomy.exchange.api.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

import java.util.UUID;

/**
 * Two players traded on the player exchange. Posted once for every fill of an order (an order that fills against
 * three others posts three) and once for every purchase from a listing, after the money and items have moved.
 * <p>
 * The buyer paid {@code value + tax}, the tax on top ({@code tax.buyer_percent}), which is destroyed; the seller has
 * been paid the whole {@code value} (or is owed it, if the bank couldn't be reached). The bought units are in the
 * buyer's collection box. Either player may be offline.
 * <p>
 * Posted by Variable Economy; add-ons only listen for it.
 */
public final class ExchangeTradeEvent extends Event {
    private final MinecraftServer server;
    private final UUID buyer;
    private final UUID seller;
    private final ItemStack item;
    private final int quantity;
    private final long unitPrice;
    private final long value;
    private final long tax;
    private final boolean fromListing;

    /**
     * Posted by Variable Economy.
     *
     * @param server      the server
     * @param buyer       the buyer
     * @param seller      the seller
     * @param item        what was traded; copied, with a count of 1
     * @param quantity    how many units changed hands
     * @param unitPrice   the price of one unit, in bits
     * @param value       what the units sold for, in bits: what the seller was paid
     * @param tax         the tax the buyer paid on top, in bits
     * @param fromListing whether the units came from a listing rather than an order
     */
    public ExchangeTradeEvent(MinecraftServer server, UUID buyer, UUID seller, ItemStack item, int quantity, long unitPrice,
                              long value, long tax, boolean fromListing) {
        this.server = server;
        this.buyer = buyer;
        this.seller = seller;
        this.item = item.copyWithCount(1);
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.value = value;
        this.tax = tax;
        this.fromListing = fromListing;
    }

    /**
     * The server the trade happened on.
     *
     * @return the server the trade happened on
     */
    public MinecraftServer getServer() {
        return server;
    }

    /**
     * The player who bought.
     *
     * @return the player who bought
     */
    public UUID getBuyer() {
        return buyer;
    }

    /**
     * The player who sold.
     *
     * @return the player who sold
     */
    public UUID getSeller() {
        return seller;
    }

    /**
     * One unit of what was traded, as a new copy each call. A fresh copy of the item for an order fill; for a listing,
     * the listed item with all its components.
     *
     * @return one unit of what was traded
     */
    public ItemStack getItem() {
        return item.copy();
    }

    /**
     * How many units changed hands.
     *
     * @return how many units changed hands
     */
    public int getQuantity() {
        return quantity;
    }

    /**
     * The price per unit in bits: the waiting order's price for an order fill, the listing's price for a listing.
     *
     * @return the price per unit in bits: the waiting order's price for an order fill, the listing's price for a listing
     */
    public long getUnitPrice() {
        return unitPrice;
    }

    /**
     * What the units sold for in bits, {@code unitPrice × quantity}: what the seller was paid.
     *
     * @return what the units sold for in bits
     */
    public long getValue() {
        return value;
    }

    /**
     * The exchange tax in bits, which the buyer paid on top of the value and which is destroyed. A buy order's tax is
     * rounded up once over all it buys, so its fills' taxes add up to that.
     *
     * @return the tax the buyer paid, in bits
     */
    public long getTax() {
        return tax;
    }

    /**
     * What the buyer paid in bits: the value and the tax.
     *
     * @return what the buyer paid in bits
     */
    public long getBuyerPaid() {
        return value + tax;
    }

    /**
     * {@code true} for a purchase from a listing, {@code false} for two orders on the book filling each other.
     *
     * @return {@code true} for a purchase from a listing, {@code false} for two orders on the book filling each other
     */
    public boolean isFromListing() {
        return fromListing;
    }
}
