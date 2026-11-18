package variableeconomy.exchange.service;

import variableeconomy.exchange.ExchangeSettings;
import variableeconomy.exchange.ExchangeModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.Accounts;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.PaymentResult;
import variableeconomy.core.api.PlayerItems;
import variableeconomy.core.api.Reason;
import variableeconomy.exchange.api.event.ExchangeTradeEvent;
import variableeconomy.exchange.data.ExchangeData;
import variableeconomy.exchange.data.ExchangeData.Notice;
import variableeconomy.exchange.data.ExchangeData.Listing;
import variableeconomy.exchange.data.ExchangeData.NoticeKind;
import variableeconomy.exchange.data.ExchangeData.Order;
import variableeconomy.exchange.data.ExchangeData.Parcel;
import io.netty.buffer.Unpooled;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The only code that changes the player exchange. Money moves only through core's {@code Money} ({@link CoreApi#money()}), following the
 * market's rules: every check runs before anything changes, on the server thread, and once the first change is made
 * no remaining step can fail.
 * <ul>
 *   <li>Posting a sell order takes its items from the main inventory; posting a buy order takes {@code price × qty}
 *       and the tax on it from the account the player trades with. Either then matches against the other side of the
 *       book at once. Each post also costs {@code posts.listing_fee}, if set, which is never refunded.</li>
 *   <li>A trade fills at the waiting order's price, best price first, then oldest first. A player's orders never
 *       trade with each other. A buyer who bid above the price paid gets the difference back straight away. With
 *       {@code matching.enabled} off, posting never trades: orders wait until another player fills them.</li>
 *   <li>Filling: a player picks one order on the book and fills all or part of it at its price, buying from a sell
 *       order (paying the tax on top) or selling into a buy order (paid from what it holds).</li>
 *   <li>The buyer pays {@code tax.buyer_percent} on top, destroyed; the seller is paid the whole price into the
 *       account their order belongs to. Bought items go to the buyer's collection box.</li>
 *   <li>Cancelling, expiry or turning the exchange off returns exactly what an order still holds: a buy order's money
 *       to its account, a sell order's items to the collection box.</li>
 *   <li>Only fresh copies go on the book, exactly as the market hands them out. Items with any components of their
 *       own (enchanted, damaged, renamed, a levelled relic and so on, even data the market's sale check ignores)
 *       are <em>listed</em> instead, so they reach the buyer exactly as they were: one stack's worth of one exact item at a fixed price each, which other players buy
 *       from, all or part, into their collection box, paying the same tax. Listings and orders share
 *       {@code posts.max_per_player}, and are cancelled, expired and closed the same way.</li>
 * </ul>
 * The exchange creates no money: every bit paid to a seller was taken from a buyer, and the tax and fee only remove
 * some.
 * <p>
 * Every fill of an order, and every purchase from a listing, posts an {@link ExchangeTradeEvent} once the request is
 * complete.
 */
public final class ExchangeService {
    private ExchangeService() {
    }

    public enum Action { POST, CANCEL, COLLECT, LIST, BUY_LISTING, FILL }

    /**
     * The most bytes one listed item may take to send. Every listing goes to every player browsing them, so an item
     * carrying a great deal of data (a long written book, say) is refused.
     */
    public static final int MAX_LISTING_BYTES = 8192;

    /**
     * How an exchange request went.
     *
     * @param quantity units ordered (posting), listed, bought from a listing, returned (cancelling) or taken out of the
     *                 box (collecting)
     * @param filled   units that traded straight away when posting
     * @param amount   posting: money paid for the units bought (tax included) or received for those sold; buying from a
     *                 listing: what it cost, tax included; cancelling: a buy order's money returned
     * @param orderId  the order left open on the book after posting (0 if it filled completely), or the listing
     * @param listed   for listing, buying from or cancelling a listing: its item, components and all, so messages can
     *                 name it as listed; empty otherwise
     */
    public record Outcome(Action action, ExchangeResult result, ResourceLocation item, int quantity, int filled, long amount, long orderId,
                          ItemStack listed) {
        public Outcome(Action action, ExchangeResult result, ResourceLocation item, int quantity, int filled, long amount, long orderId) {
            this(action, result, item, quantity, filled, amount, orderId, ItemStack.EMPTY);
        }

        static Outcome fail(Action action, ExchangeResult result, ResourceLocation item) {
            return new Outcome(action, result, item, 0, 0, 0, 0);
        }

        public boolean ok() {
            return result == ExchangeResult.OK;
        }
    }

    /**
     * One order on an item's book as one player sees it: its price, units left, who posted it, whether that's the
     * viewer, and the days until it expires (-1 when posts don't expire).
     */
    public record BookOrder(long id, long price, int quantity, String poster, boolean mine, long daysLeft) {
    }

    /** An item's book as one player sees it, best price first, then oldest, at most {@code depth} orders a side. */
    public record Book(ResourceLocation item, List<BookOrder> asks, List<BookOrder> bids) {
    }

    /** The best price on each side of an item's book, and how many units are on that side in all; 0 when empty. */
    public record Summary(ResourceLocation item, long bestAsk, long askUnits, long bestBid, long bidUnits) {
    }

    /** What a buy order's or a listing purchase's money is when it is taken, in core's money service. */
    public static final ResourceLocation ESCROW = ResourceLocation.fromNamespaceAndPath(ExchangeModule.MOD_ID, "exchange_escrow");
    /** What the fee for posting an order or listing is, in core's money service. */
    public static final ResourceLocation FEE = ResourceLocation.fromNamespaceAndPath(ExchangeModule.MOD_ID, "exchange_listing_fee");
    /** What a seller's takings are, in core's money service. */
    public static final ResourceLocation PAYMENT = ResourceLocation.fromNamespaceAndPath(ExchangeModule.MOD_ID, "exchange_payment");
    /** What money given back is (a cancelled or expired buy order, or units bought below the bid). */
    public static final ResourceLocation REFUND = ResourceLocation.fromNamespaceAndPath(ExchangeModule.MOD_ID, "exchange_refund");

    // ---- Rules shared with the screen ------------------------------------------------------------------------

    /** Whether an item can be traded on the exchange at all: anything but air and money. */
    public static boolean tradeable(Item item) {
        return item != Items.AIR && !CoreApi.isCoin(item);
    }

    /**
     * Whether a stack can be listed: an item that can be traded, carrying components of its own (fresh copies go on the
     * book), and not a container with anything inside, whose contents a buyer couldn't fully see.
     */
    public static boolean listable(ItemStack stack) {
        if (stack.isEmpty() || !tradeable(stack.getItem()) || fresh(stack.getItem()).test(stack)) {
            return false;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents != null && contents.nonEmptyStream().findAny().isPresent()) {
            return false;
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        return bundle == null || bundle.isEmpty();
    }

    /** How many bytes {@code stack} takes to send. */
    public static int encodedSize(ItemStack stack, RegistryAccess registries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            ItemStack.STREAM_CODEC.encode(buf, stack);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    /** The tax on a purchase worth {@code value}: {@code rate} of it ({@code tax.buyer_percent} / 100), rounded up to a whole bit. */
    public static long tax(long value, double rate) {
        if (rate <= 0 || value <= 0) {
            return 0;
        }
        return Math.min(value, BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.CEILING).longValueExact());
    }

    /**
     * The tax on one more piece of a purchase, worth {@code value}, after {@code before} of the same buy order has
     * already been bought: what the tax on the whole so far has grown by. However a purchase is split, its pieces'
     * taxes add up to {@link #tax} of its total, rounded up once.
     */
    public static long taxOnMore(long before, long value, double rate) {
        return tax(before + value, rate) - tax(before, rate);
    }

    /** The exchange's day count today, which order expiry uses. */
    public static long today(MinecraftServer server) {
        ExchangeData data = ExchangeData.get(server);
        data.observe(ExchangeData.worldDay(server));
        return data.day();
    }

    /** {@code value} and its tax, or empty if that would overflow a {@code long}. */
    public static OptionalLong withTax(long value, double rate) {
        try {
            return OptionalLong.of(Math.addExact(value, tax(value, rate)));
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * What a buy order must hold for its remaining units at its price: their value and what the tax on its whole
     * spending grows by. Never more than it held when posted.
     */
    public static long needed(Order order, double rate) {
        long rest = order.price() * order.remaining();
        return rest + taxOnMore(order.traded(), rest, rate);
    }

    /** {@code unitPrice × qty}, or empty if it would overflow a {@code long}. */
    public static OptionalLong checkedTotal(long unitPrice, int qty) {
        try {
            long total = Math.multiplyExact(unitPrice, (long) qty);
            return OptionalLong.of(total);
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    /** A player's name: online, or as the server last saw them. */
    public static Optional<String> name(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return Optional.of(online.getGameProfile().getName());
        }
        return server.getProfileCache() == null ? Optional.empty()
                : server.getProfileCache().get(id).map(com.mojang.authlib.GameProfile::getName);
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.server.isSameThread()) {
            throw new IllegalStateException("Exchange actions must run on the server thread");
        }
    }

    // ---- Posting ---------------------------------------------------------------------------------------------

    public static Outcome post(ServerPlayer player, Item item, boolean buy, long price, int qty) {
        return post(player, ExchangeData.get(player.server), item, buy, price, qty, today(player.server));
    }

    /**
     * Posts an order: a sell order's items or a buy order's money are taken, then it trades with whatever on the
     * other side of the book it crosses. What's left stays on the book.
     */
    public static Outcome post(ServerPlayer player, ExchangeData data, Item item, boolean buy, long price, int qty, long day) {
        requireServerThread(player);
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (!ExchangeSettings.exchangeEnabled()) {
            return Outcome.fail(Action.POST, ExchangeResult.EXCHANGE_CLOSED, id);
        }
        // A locked item answers as an unlisted one, so the reply doesn't reveal it.
        if (!tradeable(item) || CoreApi.itemGate().isLocked(player, item)) {
            return Outcome.fail(Action.POST, ExchangeResult.NOT_LISTED, id);
        }
        if (qty <= 0) {
            return Outcome.fail(Action.POST, ExchangeResult.INVALID_QUANTITY, id);
        }
        if (price <= 0) {
            return Outcome.fail(Action.POST, ExchangeResult.INVALID_PRICE, id);
        }
        double rate = ExchangeSettings.exchangeTax();
        long fee = ExchangeSettings.listingFee();
        OptionalLong total = checkedTotal(price, qty);
        OptionalLong escrow = total.isPresent() ? withTax(total.getAsLong(), rate) : OptionalLong.empty();
        if (qty > ExchangeSettings.maxOrderSize() || escrow.isEmpty() || buy && escrow.getAsLong() > Long.MAX_VALUE - fee) {
            return Outcome.fail(Action.POST, ExchangeResult.TOO_LARGE, id);
        }
        if (data.openOrders(player.getUUID()) >= ExchangeSettings.maxOpenOrders()) {
            return Outcome.fail(Action.POST, ExchangeResult.ORDER_LIMIT, id);
        }
        AccountId account = CoreApi.accounts().active(player);
        Predicate<ItemStack> matches = fresh(item);
        if (buy) {
            // One charge for the escrow, its tax and the fee: it's itself the funds check, and changes nothing unless it succeeds.
            PaymentResult charged = CoreApi.money().charge(player.server, account, escrow.getAsLong() + fee, Reason.by(ESCROW, player.getUUID()));
            if (!charged.ok()) {
                return Outcome.fail(Action.POST, charged.result() == BankResult.NO_FUNDS ? ExchangeResult.NO_FUNDS : ExchangeResult.BANK_UNAVAILABLE, id);
            }
        } else {
            if (PlayerItems.count(player, matches) < qty) {
                return Outcome.fail(Action.POST, ExchangeResult.NOT_ENOUGH_ITEMS, id);
            }
            ExchangeResult paid = chargeFee(player, account, fee);
            if (paid != ExchangeResult.OK) {
                return Outcome.fail(Action.POST, paid, id);
            }
            // Counted above, so this can't fail.
            PlayerItems.remove(player, matches, qty);
        }

        // The order's money or items are held now. Everything below always succeeds: every payment is one fill of
        // one order, which fits a long because that order's whole total and tax did.
        Order order = new Order(data.takeId(), player.getUUID(), Optional.of(account.value()), id, buy, price, qty, 0, 0,
                buy ? escrow.getAsLong() : 0, day);
        Fill fill = ExchangeSettings.matchingEnabled() ? match(player.server, data, order, account) : new Fill(order, 0, List.of());
        if (fill.order().remaining() > 0) {
            data.add(fill.order());
        }
        ExchangeEvents.postAll(fill.events());
        return new Outcome(Action.POST, ExchangeResult.OK, id, qty, fill.order().filled(), fill.amount(),
                fill.order().remaining() > 0 ? order.id() : 0);
    }

    /**
     * Charges the fee for posting a sell order or listing, if there is one: itself the funds check. OK if it was taken
     * or there's none.
     */
    private static ExchangeResult chargeFee(ServerPlayer player, AccountId account, long fee) {
        if (fee <= 0) {
            return ExchangeResult.OK;
        }
        PaymentResult charged = CoreApi.money().charge(player.server, account, fee, Reason.by(FEE, player.getUUID()));
        return charged.ok() ? ExchangeResult.OK : charged.result() == BankResult.NO_FUNDS ? ExchangeResult.NO_FUNDS : ExchangeResult.BANK_UNAVAILABLE;
    }

    /**
     * What matching left of an incoming order, the money it paid (buying, tax included) or received (selling), and an
     * event for each fill, posted once the order is on the book.
     */
    private record Fill(Order order, long amount, List<ExchangeTradeEvent> events) {
    }

    /**
     * Trades an incoming order against the other side of its item's book while their prices cross. The incoming
     * order's money or items are already held; {@code account} is where its own money goes.
     */
    private static Fill match(MinecraftServer server, ExchangeData data, Order incoming, AccountId account) {
        double rate = ExchangeSettings.exchangeTax();
        Order current = incoming;
        long amount = 0;
        List<ExchangeTradeEvent> events = new ArrayList<>();
        for (Order resting : data.side(incoming.item(), !incoming.buy())) {
            if (current.remaining() == 0) {
                break;
            }
            if (incoming.buy() ? resting.price() > incoming.price() : resting.price() < incoming.price()) {
                break;
            }
            if (resting.owner().equals(incoming.owner())) {
                continue;
            }
            int units = Math.min(current.remaining(), resting.remaining());
            Order buyOrder = incoming.buy() ? current : resting;
            Order sellOrder = incoming.buy() ? resting : current;
            // A seller whose account couldn't take the money isn't traded with, so no sale goes unpaid: a resting seller
            // is passed over, and an incoming one keeps the rest of its items on the book.
            long value = resting.price() * units;
            if (incoming.buy() && payee(server, resting.owner(), resting.account(), value).isEmpty()) {
                continue;
            }
            if (!incoming.buy() && !fits(server, account, value)) {
                break;
            }
            Trade trade = trade(server, data, buyOrder, sellOrder, units, resting.price(), rate);
            if (incoming.buy()) {
                current = trade.buyOrder();
                amount += trade.value() + trade.tax();
            } else {
                current = trade.sellOrder();
                amount += trade.value();
            }
            events.add(trade.event());
        }
        if (incoming.buy()) {
            // Bought below the bid: what it no longer needs to hold goes straight back.
            long keep = needed(current, rate);
            if (current.held() > keep) {
                pay(server, account, current.held() - keep, Reason.by(REFUND, incoming.owner()));
                current = current.withHeld(keep);
            }
        }
        return new Fill(current, amount, events);
    }

    /** One fill, as {@link #trade} made it: both orders after it, what it came to and its tax, and its event. */
    private record Trade(Order buyOrder, Order sellOrder, long value, long tax, ExchangeTradeEvent event) {
    }

    /**
     * Trades {@code units} between a buy order and a sell order at {@code price}, both already checked: the buy order's
     * held money pays the value and its tax, the seller is paid the value, the units go to the buyer's box, and each
     * order on the book is updated (an incoming one is the caller's to keep). The side not making the request is told.
     * The tax never takes a buy order below nothing, nor is it ever below 0 (an order saved before the tax moved to buyers,
     * or posted before an admin raised the tax, holds less tax than it now owes).
     */
    private static Trade trade(MinecraftServer server, ExchangeData data, Order buyOrder, Order sellOrder, int units, long price, double rate) {
        long value = price * units;
        long tax = Math.max(0, Math.min(taxOnMore(buyOrder.traded(), value, rate), buyOrder.held() - value));
        // The order making the request isn't on the book yet; the other one is.
        boolean buyResting = data.find(buyOrder.id()).isPresent();
        Order bought = buyOrder.withFill(units, value, value + tax);
        Order sold = sellOrder.withFill(units, value, 0);
        data.update(buyResting ? bought : sold);
        if (bought.remaining() == 0 && bought.held() > 0) {
            // Nothing left to buy: anything still held goes back.
            payBack(server, bought.owner(), bought.account(), bought.held(), Reason.by(REFUND, bought.owner()));
            bought = bought.withHeld(0);
        }
        data.count(value, tax);
        payBack(server, sold.owner(), sold.account(), value, Reason.by(PAYMENT, sold.owner()));
        data.addToBox(bought.owner(), bought.item(), units);
        if (buyResting) {
            tell(server, data, new Notice(bought.owner(), bought.item(), NoticeKind.BOUGHT, units, value + tax));
        } else {
            tell(server, data, new Notice(sold.owner(), sold.item(), NoticeKind.SOLD, units, value));
        }
        ExchangeTradeEvent event = new ExchangeTradeEvent(server, bought.owner(), sold.owner(),
                new ItemStack(BuiltInRegistries.ITEM.get(buyOrder.item())), units, price, value, tax, false);
        return new Trade(bought, sold, value, tax, event);
    }

    // ---- Filling one order -----------------------------------------------------------------------------------

    public static Outcome fill(ServerPlayer player, long orderId, int qty) {
        return fill(player, ExchangeData.get(player.server), orderId, qty, today(player.server));
    }

    /**
     * Fills {@code qty} units of another player's order at its price: buys from a sell order, paying the value and
     * the tax on top from the account the player trades with, the units going to their collection box; or sells into
     * a buy order from the main inventory, paid from what the order holds. Works with matching on or off.
     */
    public static Outcome fill(ServerPlayer player, ExchangeData data, long orderId, int qty, long day) {
        requireServerThread(player);
        if (!ExchangeSettings.exchangeEnabled()) {
            return Outcome.fail(Action.FILL, ExchangeResult.EXCHANGE_CLOSED, none());
        }
        Optional<Order> found = data.find(orderId);
        if (found.isEmpty()) {
            return Outcome.fail(Action.FILL, ExchangeResult.ORDER_GONE, none());
        }
        Order order = found.get();
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(order.item()).filter(ExchangeService::tradeable);
        // A locked item answers as an unlisted one, so the reply doesn't reveal it.
        if (item.isEmpty() || CoreApi.itemGate().isLocked(player, item.get())) {
            return Outcome.fail(Action.FILL, ExchangeResult.NOT_LISTED, order.item());
        }
        if (order.owner().equals(player.getUUID())) {
            return Outcome.fail(Action.FILL, ExchangeResult.OWN_ORDER, order.item());
        }
        if (qty <= 0) {
            return Outcome.fail(Action.FILL, ExchangeResult.INVALID_QUANTITY, order.item());
        }
        if (qty > order.remaining()) {
            return Outcome.fail(Action.FILL, ExchangeResult.NO_STOCK, order.item());
        }
        if (qty > ExchangeSettings.maxOrderSize()) {
            return Outcome.fail(Action.FILL, ExchangeResult.TOO_LARGE, order.item());
        }
        double rate = ExchangeSettings.exchangeTax();
        // The order's whole total fit a long when it was posted, so part of it does too.
        long value = order.price() * qty;
        AccountId account = CoreApi.accounts().active(player);
        Trade trade;
        long amount;
        if (order.buy()) {
            // Selling into a buy order: the items are checked, then taken; nothing after can fail.
            Predicate<ItemStack> matches = fresh(item.get());
            if (PlayerItems.count(player, matches) < qty) {
                return Outcome.fail(Action.FILL, ExchangeResult.NOT_ENOUGH_ITEMS, order.item());
            }
            if (payee(player.server, player.getUUID(), Optional.of(account.value()), value).isEmpty()) {
                return Outcome.fail(Action.FILL, ExchangeResult.TOO_LARGE, order.item());
            }
            PlayerItems.remove(player, matches, qty);
            Order selling = new Order(0, player.getUUID(), Optional.of(account.value()), order.item(), false, order.price(), qty, 0, 0, 0, day);
            trade = trade(player.server, data, order, selling, qty, order.price(), rate);
            amount = value;
        } else {
            // Buying from a sell order: the charge is itself the funds check.
            OptionalLong cost = withTax(value, rate);
            if (cost.isEmpty() || payee(player.server, order.owner(), order.account(), value).isEmpty()) {
                return Outcome.fail(Action.FILL, ExchangeResult.TOO_LARGE, order.item());
            }
            PaymentResult charged = CoreApi.money().charge(player.server, account, cost.getAsLong(), Reason.by(ESCROW, player.getUUID()));
            if (!charged.ok()) {
                return Outcome.fail(Action.FILL, charged.result() == BankResult.NO_FUNDS ? ExchangeResult.NO_FUNDS : ExchangeResult.BANK_UNAVAILABLE,
                        order.item());
            }
            Order buying = new Order(0, player.getUUID(), Optional.of(account.value()), order.item(), true, order.price(), qty, 0, 0,
                    cost.getAsLong(), day);
            trade = trade(player.server, data, buying, order, qty, order.price(), rate);
            amount = cost.getAsLong();
        }
        ExchangeEvents.post(trade.event());
        return new Outcome(Action.FILL, ExchangeResult.OK, order.item(), qty, qty, amount, order.id());
    }

    // ---- Listings --------------------------------------------------------------------------------------------

    public static Outcome list(ServerPlayer player, int slot, ItemStack expected, long price, int qty) {
        return list(player, ExchangeData.get(player.server), slot, expected, price, qty, today(player.server));
    }

    /**
     * Lists {@code qty} units of the item in main-inventory {@code slot} at {@code price} each. {@code expected} is the
     * item the player saw there; if the slot now holds something else, nothing is listed. The units are taken from
     * that slot first, then from other stacks of exactly the same item.
     */
    public static Outcome list(ServerPlayer player, ExchangeData data, int slot, ItemStack expected, long price, int qty, long day) {
        requireServerThread(player);
        NonNullList<ItemStack> items = player.getInventory().items;
        ItemStack held = slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey((held.isEmpty() ? expected : held).getItem());
        if (!ExchangeSettings.exchangeEnabled()) {
            return Outcome.fail(Action.LIST, ExchangeResult.EXCHANGE_CLOSED, id);
        }
        if (held.isEmpty() || !ItemStack.isSameItemSameComponents(held, expected)) {
            return Outcome.fail(Action.LIST, ExchangeResult.NOT_ENOUGH_ITEMS, id);
        }
        if (!listable(held) || CoreApi.itemGate().isLocked(player, held.getItem())) {
            return Outcome.fail(Action.LIST, ExchangeResult.NOT_LISTED, id);
        }
        if (qty <= 0) {
            return Outcome.fail(Action.LIST, ExchangeResult.INVALID_QUANTITY, id);
        }
        if (price <= 0) {
            return Outcome.fail(Action.LIST, ExchangeResult.INVALID_PRICE, id);
        }
        // One stack's worth at most, and a total that can be counted.
        if (qty > held.getMaxStackSize() || checkedTotal(price, qty).isEmpty()
                || encodedSize(held.copyWithCount(1), player.server.registryAccess()) > MAX_LISTING_BYTES) {
            return Outcome.fail(Action.LIST, ExchangeResult.TOO_LARGE, id);
        }
        if (data.openOrders(player.getUUID()) >= ExchangeSettings.maxOpenOrders()) {
            return Outcome.fail(Action.LIST, ExchangeResult.ORDER_LIMIT, id);
        }
        AccountId account = CoreApi.accounts().active(player);
        ItemStack template = held.copyWithCount(1);
        Predicate<ItemStack> same = stack -> ItemStack.isSameItemSameComponents(stack, template);
        if (PlayerItems.count(player, same) < qty) {
            return Outcome.fail(Action.LIST, ExchangeResult.NOT_ENOUGH_ITEMS, id);
        }
        ExchangeResult paid = chargeFee(player, account, ExchangeSettings.listingFee());
        if (paid != ExchangeResult.OK) {
            return Outcome.fail(Action.LIST, paid, id);
        }
        // Counted above, so taking the items can't fail.

        int fromSlot = Math.min(qty, held.getCount());
        held.shrink(fromSlot);
        if (held.isEmpty()) {
            items.set(slot, ItemStack.EMPTY);
        }
        if (qty > fromSlot) {
            PlayerItems.remove(player, same, qty - fromSlot);
        }
        player.getInventory().setChanged();
        Listing listing = new Listing(data.takeId(), player.getUUID(), Optional.of(account.value()), template, price, qty, 0, day);
        data.add(listing);
        return new Outcome(Action.LIST, ExchangeResult.OK, id, qty, 0, 0, listing.id(), template);
    }

    public static Outcome buyListing(ServerPlayer player, long id, int qty, long price) {
        return buyListing(player, ExchangeData.get(player.server), id, qty, price);
    }

    /**
     * Buys {@code qty} units of listing {@code id} at {@code price} each, the price the buyer saw, plus the tax. The
     * units go to the buyer's collection box; the seller is paid the whole price.
     */
    public static Outcome buyListing(ServerPlayer player, ExchangeData data, long id, int qty, long price) {
        requireServerThread(player);
        if (!ExchangeSettings.exchangeEnabled()) {
            return Outcome.fail(Action.BUY_LISTING, ExchangeResult.EXCHANGE_CLOSED, none());
        }
        Optional<Listing> found = data.findListing(id);
        if (found.isEmpty()) {
            return Outcome.fail(Action.BUY_LISTING, ExchangeResult.ORDER_GONE, none());
        }
        Listing listing = found.get();
        if (CoreApi.itemGate().isLocked(player, listing.stack().getItem())) {
            return Outcome.fail(Action.BUY_LISTING, ExchangeResult.NOT_LISTED, listing.item());
        }
        if (listing.owner().equals(player.getUUID())) {
            return Outcome.fail(Action.BUY_LISTING, ExchangeResult.OWN_LISTING, listing.item());
        }
        if (qty <= 0) {
            return Outcome.fail(Action.BUY_LISTING, ExchangeResult.INVALID_QUANTITY, listing.item());
        }
        if (qty > listing.remaining()) {
            return Outcome.fail(Action.BUY_LISTING, ExchangeResult.NO_STOCK, listing.item());
        }
        if (price != listing.price()) {
            return Outcome.fail(Action.BUY_LISTING, ExchangeResult.PRICE_CHANGED, listing.item());
        }
        // The listing's whole total fit a long when it was posted, so part of it does too.
        long value = listing.price() * qty;
        OptionalLong cost = withTax(value, ExchangeSettings.exchangeTax());
        if (cost.isEmpty() || payee(player.server, listing.owner(), listing.account(), value).isEmpty()) {
            return Outcome.fail(Action.BUY_LISTING, ExchangeResult.TOO_LARGE, listing.item());
        }
        long tax = cost.getAsLong() - value;
        AccountId account = CoreApi.accounts().active(player);
        PaymentResult charged = CoreApi.money().charge(player.server, account, cost.getAsLong(), Reason.by(ESCROW, player.getUUID()));
        if (!charged.ok()) {
            return Outcome.fail(Action.BUY_LISTING, charged.result() == BankResult.NO_FUNDS ? ExchangeResult.NO_FUNDS : ExchangeResult.BANK_UNAVAILABLE,
                    listing.item());
        }

        data.update(listing.withSold(listing.sold() + qty));
        data.count(value, tax);
        payBack(player.server, listing.owner(), listing.account(), value, Reason.by(PAYMENT, listing.owner()));
        tell(player.server, data, new Notice(listing.owner(), listing.item(), NoticeKind.SOLD, qty, value,
                customName(listing.stack())));
        data.addParcel(player.getUUID(), listing.stack(), qty);
        ExchangeEvents.post(new ExchangeTradeEvent(player.server, player.getUUID(), listing.owner(), listing.stack(), qty,
                listing.price(), value, tax, true));
        return new Outcome(Action.BUY_LISTING, ExchangeResult.OK, listing.item(), qty, qty, cost.getAsLong(), listing.id(), listing.stack());
    }

    // ---- Cancelling and expiry -------------------------------------------------------------------------------

    public static Outcome cancel(ServerPlayer player, long id) {
        return cancel(player, ExchangeData.get(player.server), id);
    }

    /** Cancels one of the player's open orders or listings, returning what it still holds. */
    public static Outcome cancel(ServerPlayer player, ExchangeData data, long id) {
        requireServerThread(player);
        Optional<Order> found = data.find(id);
        if (found.isEmpty()) {
            Optional<Listing> listing = data.findListing(id);
            if (listing.isEmpty()) {
                return Outcome.fail(Action.CANCEL, ExchangeResult.ORDER_GONE, none());
            }
            Listing l = listing.get();
            if (!l.owner().equals(player.getUUID())) {
                return Outcome.fail(Action.CANCEL, ExchangeResult.NOT_YOUR_ORDER, l.item());
            }
            data.removeListing(l.id());
            data.addParcel(l.owner(), l.stack(), l.remaining());
            return new Outcome(Action.CANCEL, ExchangeResult.OK, l.item(), l.remaining(), 0, 0, l.id(), l.stack());
        }
        Order order = found.get();
        if (!order.owner().equals(player.getUUID())) {
            return Outcome.fail(Action.CANCEL, ExchangeResult.NOT_YOUR_ORDER, order.item());
        }
        Optional<AccountId> account = Optional.empty();
        if (order.buy() && order.heldMoney() > 0) {
            account = payee(player.server, order.owner(), order.account(), order.heldMoney());
            if (account.isEmpty()) {
                return Outcome.fail(Action.CANCEL, ExchangeResult.BANK_UNAVAILABLE, order.item());
            }
        }

        data.remove(order.id());
        if (order.buy()) {
            account.ifPresent(to -> pay(player.server, to, order.heldMoney(), Reason.by(REFUND, player.getUUID())));
        } else {
            data.addToBox(order.owner(), order.item(), order.remaining());
        }
        return new Outcome(Action.CANCEL, ExchangeResult.OK, order.item(), order.remaining(), 0, order.heldMoney(), order.id());
    }

    /**
     * Closes an order whoever owns it (expiry, an admin, or the exchange being turned off), returning what it holds:
     * money to its account, or owed if the bank can't be reached; items to the owner's box. The owner is told.
     */
    public static void close(MinecraftServer server, ExchangeData data, Order order) {
        data.remove(order.id());
        if (order.buy()) {
            payBack(server, order.owner(), order.account(), order.heldMoney(), Reason.of(REFUND));
        } else {
            data.addToBox(order.owner(), order.item(), order.remaining());
        }
        tell(server, data, new Notice(order.owner(), order.item(), NoticeKind.RETURNED, order.buy() ? 0 : order.remaining(),
                order.heldMoney()));
    }

    /** Closes a listing whoever owns it, returning its units to the owner's box. The owner is told. */
    public static void close(MinecraftServer server, ExchangeData data, Listing listing) {
        data.removeListing(listing.id());
        data.addParcel(listing.owner(), listing.stack(), listing.remaining());
        tell(server, data, new Notice(listing.owner(), listing.item(), NoticeKind.LISTING_RETURNED, listing.remaining(), 0,
                customName(listing.stack())));
    }

    /** Closes every order and listing posted at least {@code expiryDays} days before {@code day}. Returns how many. */
    public static int expire(MinecraftServer server, ExchangeData data, long day, int expiryDays) {
        if (expiryDays <= 0) {
            return 0;
        }
        List<Order> due = data.orders().stream().filter(o -> day - o.postedDay() >= expiryDays).toList();
        due.forEach(order -> close(server, data, order));
        List<Listing> dueListings = data.listings().stream().filter(l -> day - l.postedDay() >= expiryDays).toList();
        dueListings.forEach(listing -> close(server, data, listing));
        return due.size() + dueListings.size();
    }

    /**
     * Every tick: closes every order and listing if the exchange was turned off, and expires old ones.
     * Returns whether the book changed, so open screens can be refreshed.
     */
    public static boolean tick(MinecraftServer server) {
        ExchangeData data = ExchangeData.get(server);
        if (data.orders().isEmpty() && data.listings().isEmpty()) {
            return false;
        }
        if (!ExchangeSettings.exchangeEnabled()) {
            closeAll(server, data);
            return true;
        }
        int expiryDays = ExchangeSettings.orderExpiryDays();
        if (expiryDays <= 0) {
            return false;
        }
        // Nothing posted since the last check can be due before the day count moves, so check once per day (and
        // again if the expiry setting changes).
        long day = today(server);
        if (!data.expiryCheckDue(day, expiryDays)) {
            return false;
        }
        return expire(server, data, day, expiryDays) > 0;
    }

    /** Closes every open order and listing, as when the exchange is turned off. Collection boxes stay. */
    public static void closeAll(MinecraftServer server, ExchangeData data) {
        List.copyOf(data.orders()).forEach(order -> close(server, data, order));
        List.copyOf(data.listings()).forEach(listing -> close(server, data, listing));
    }

    // ---- The collection box ----------------------------------------------------------------------------------

    public static Outcome collect(ServerPlayer player, Optional<ResourceLocation> only) {
        return collect(player, ExchangeData.get(player.server), only);
    }

    public static Outcome collectParcel(ServerPlayer player, long parcel) {
        return collectParcel(player, ExchangeData.get(player.server), parcel);
    }

    /**
     * Moves what fits of the player's box (all of it, or the plain copies of one item) into free main-inventory
     * space. The rest stays in the box. Works with the exchange turned off, so nothing is ever stranded.
     */
    public static Outcome collect(ServerPlayer player, ExchangeData data, Optional<ResourceLocation> only) {
        List<Parcel> parcels = only.isPresent() ? List.of() : data.parcels(player.getUUID());
        return collect(player, data, only, parcels);
    }

    /** The same for one parcel of the player's box: items with their own components. */
    public static Outcome collectParcel(ServerPlayer player, ExchangeData data, long parcel) {
        List<Parcel> one = data.parcels(player.getUUID()).stream().filter(p -> p.id() == parcel).toList();
        if (one.isEmpty()) {
            requireServerThread(player);
            return Outcome.fail(Action.COLLECT, ExchangeResult.NOT_ENOUGH_ITEMS, none());
        }
        return collect(player, data, Optional.of(none()), one);
    }

    /** Collects the plain items {@code only} names (everything when empty) and the given parcels. */
    private static Outcome collect(ServerPlayer player, ExchangeData data, Optional<ResourceLocation> only, List<Parcel> parcels) {
        requireServerThread(player);
        Map<ResourceLocation, Long> box = new LinkedHashMap<>(data.box(player.getUUID()));
        if (only.isPresent()) {
            box.keySet().retainAll(Set.of(only.get()));
        }
        ResourceLocation shown = !parcels.isEmpty() && box.isEmpty() ? id(parcels.get(0).stack())
                : only.filter(item -> !item.equals(none())).orElse(box.isEmpty() ? none() : box.keySet().iterator().next());
        if (box.isEmpty() && parcels.isEmpty()) {
            return Outcome.fail(Action.COLLECT, ExchangeResult.NOT_ENOUGH_ITEMS, shown);
        }
        long moved = 0;
        for (Parcel parcel : List.copyOf(parcels)) {
            int fits = (int) Math.min(Math.min(parcel.count(), PlayerItems.room(player, parcel.stack())), Integer.MAX_VALUE);
            if (fits > 0) {
                PlayerItems.insert(player, parcel.stack(), fits);
                data.takeParcel(player.getUUID(), parcel.id(), fits);
                moved += fits;
            }
        }
        for (Map.Entry<ResourceLocation, Long> entry : box.entrySet()) {
            // An item whose mod was removed stays in the box until it's back.
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(entry.getKey()).filter(i -> i != Items.AIR);
            if (item.isEmpty()) {
                continue;
            }
            ItemStack fresh = new ItemStack(item.get());
            int fits = (int) Math.min(Math.min(entry.getValue(), PlayerItems.room(player, fresh)), Integer.MAX_VALUE);
            if (fits > 0) {
                PlayerItems.insert(player, fresh, fits);
                data.takeFromBox(player.getUUID(), entry.getKey(), fits);
                moved += fits;
            }
        }
        if (moved == 0) {
            return Outcome.fail(Action.COLLECT, ExchangeResult.NO_SPACE, shown);
        }
        player.getInventory().setChanged();
        return new Outcome(Action.COLLECT, ExchangeResult.OK, shown, (int) Math.min(moved, Integer.MAX_VALUE), 0, 0, 0);
    }

    // ---- Paying and telling ----------------------------------------------------------------------------------

    /** The exchange's entry on the leaderboard: the money its open buy orders hold, tax included. */
    public static final ResourceLocation NET_WORTH = ResourceLocation.fromNamespaceAndPath(ExchangeModule.MOD_ID, "exchange_escrow");

    /** Registers the exchange's net-worth contributor. Once, while the mod is constructed. */
    public static void registerNetWorth() {
        CoreApi.netWorth().register(NET_WORTH, ExchangeService::held);
    }

    /**
     * The money open buy orders hold, by the account it goes back to: the account it was paid from while that exists,
     * otherwise its owner's own.
     */
    public static Map<AccountId, Long> held(MinecraftServer server) {
        return held(server, ExchangeData.get(server));
    }

    /** The same for the given exchange data. */
    public static Map<AccountId, Long> held(MinecraftServer server, ExchangeData data) {
        Accounts accounts = CoreApi.accounts();
        Map<AccountId, Long> held = new LinkedHashMap<>();
        for (Order order : data.orders()) {
            if (order.heldMoney() > 0) {
                AccountId account = order.account().map(AccountId::new).filter(id -> accounts.get(server, id).isPresent())
                        .orElseGet(() -> accounts.personal(order.owner()));
                held.merge(account, order.heldMoney(), Long::sum);
            }
        }
        return held;
    }

    /**
     * The account an order's money goes to: the account it was paid from while that still exists and can take
     * {@code amount}, otherwise the owner's own. Empty if neither can take it now.
     */
    static Optional<AccountId> payee(MinecraftServer server, UUID owner, Optional<UUID> from, long amount) {
        Accounts accounts = CoreApi.accounts();
        List<AccountId> candidates = new ArrayList<>();
        from.ifPresent(id -> candidates.add(new AccountId(id)));
        candidates.add(accounts.personal(owner));
        for (AccountId candidate : candidates) {
            if (accounts.get(server, candidate).isPresent() && fits(server, candidate, amount)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Whether the account can take {@code amount} more without its balance overflowing. */
    private static boolean fits(MinecraftServer server, AccountId account, long amount) {
        OptionalLong balance = CoreApi.money().balance(server, account);
        return balance.isPresent() && balance.getAsLong() <= Long.MAX_VALUE - amount;
    }

    /** Pays an account that was checked, or that may owe it: core owes a payment while the bank can't be reached. */
    private static void pay(MinecraftServer server, AccountId account, long amount, Reason reason) {
        if (amount <= 0) {
            return;
        }
        PaymentResult result = CoreApi.money().pay(server, account, amount, reason);
        if (!result.ok()) {
            ExchangeModule.LOGGER.error("An exchange payment of {} to {} failed: {}", amount, account, result.result());
        }
    }

    /** Pays into the account an order's money goes to, whoever's turn it is: a fill, a cancel, expiry. */
    private static void payBack(MinecraftServer server, UUID owner, Optional<UUID> from, long amount, Reason reason) {
        if (amount <= 0) {
            return;
        }
        pay(server, payee(server, owner, from, amount).orElseGet(() -> CoreApi.accounts().personal(owner)), amount, reason);
    }

    /** Tells an order's owner what happened to it: in chat if they're online, otherwise when they next log in. */
    private static void tell(MinecraftServer server, ExchangeData data, Notice notice) {
        ServerPlayer online = server.getPlayerList().getPlayer(notice.player());
        if (online != null) {
            online.sendSystemMessage(message(server, notice));
        } else {
            data.addNotice(notice);
        }
    }

    /** Sends a player who just logged in what happened to their orders while they were away, and what waits for them. */
    public static void loggedIn(ServerPlayer player) {
        ExchangeData data = ExchangeData.get(player.server);
        for (Notice notice : data.takeNotices(player.getUUID())) {
            player.sendSystemMessage(message(player.server, notice));
        }
        long waiting = data.box(player.getUUID()).values().stream().mapToLong(Long::longValue).sum()
                + data.parcels(player.getUUID()).stream().mapToLong(Parcel::count).sum();
        if (waiting > 0) {
            player.sendSystemMessage(Component.translatable("message.variable_economy_exchange.exchange.box_waiting", waiting));
        }
    }

    /** A listed item's custom name, for notices, or empty if it has none. */
    private static String customName(ItemStack stack) {
        return stack.has(DataComponents.CUSTOM_NAME) ? stack.getHoverName().getString() : "";
    }

    private static Component message(MinecraftServer server, Notice notice) {
        Component item = notice.name().isEmpty()
                ? BuiltInRegistries.ITEM.get(notice.item()).getDescription()
                : Component.literal(notice.name());
        return switch (notice.kind()) {
            case SOLD -> Component.translatable("message.variable_economy_exchange.exchange.sold", notice.quantity(), item, CoreApi.formatMoney(notice.amount()));
            case BOUGHT -> Component.translatable("message.variable_economy_exchange.exchange.bought", notice.quantity(), item, CoreApi.formatMoney(notice.amount()));
            case RETURNED -> notice.quantity() == 0
                    ? Component.translatable("message.variable_economy_exchange.exchange.returned_money", item, CoreApi.formatMoney(notice.amount()))
                    : Component.translatable("message.variable_economy_exchange.exchange.returned_items", notice.quantity(), item);
            case LISTING_RETURNED -> Component.translatable("message.variable_economy_exchange.exchange.returned_listing", notice.quantity(), item);
        };
    }

    // ---- Viewing ---------------------------------------------------------------------------------------------

    /** The best prices of every item with open orders, leaving out items locked for {@code player}. */
    public static List<Summary> summaries(ServerPlayer player, ExchangeData data) {
        Map<ResourceLocation, long[]> byItem = new LinkedHashMap<>();
        for (Order order : data.orders()) {
            long[] s = byItem.computeIfAbsent(order.item(), i -> new long[4]);
            if (order.buy()) {
                s[2] = Math.max(s[2], order.price());
                s[3] += order.remaining();
            } else {
                s[0] = s[0] == 0 ? order.price() : Math.min(s[0], order.price());
                s[1] += order.remaining();
            }
        }
        List<Summary> summaries = new ArrayList<>();
        byItem.forEach((item, s) -> {
            if (!hiddenFrom(player, item)) {
                summaries.add(new Summary(item, s[0], s[1], s[2], s[3]));
            }
        });
        return summaries;
    }

    /** An item's book, at most {@code depth} orders a side; empty if the item is locked for {@code player}. */
    public static Book book(ServerPlayer player, ExchangeData data, ResourceLocation item, int depth) {
        if (hiddenFrom(player, item)) {
            return new Book(item, List.of(), List.of());
        }
        long day = today(player.server);
        int expiry = ExchangeSettings.orderExpiryDays();
        return new Book(item, bookOrders(player, data.side(item, false), depth, day, expiry),
                bookOrders(player, data.side(item, true), depth, day, expiry));
    }

    private static List<BookOrder> bookOrders(ServerPlayer player, List<Order> side, int depth, long day, int expiry) {
        return side.stream().limit(depth).map(order -> new BookOrder(order.id(), order.price(), order.remaining(),
                name(player.server, order.owner()).orElse("?"), order.owner().equals(player.getUUID()),
                expiry <= 0 ? -1 : Math.max(0, order.postedDay() + expiry - day))).toList();
    }

    /** The order listings are browsed in: by item, then cheapest first, then oldest first. */
    public static final Comparator<Listing> BROWSING_ORDER =
            Comparator.comparing((Listing l) -> l.item().toString()).thenComparingLong(Listing::price).thenComparingLong(Listing::id);

    /** Every listing {@code player} may see, in {@link #BROWSING_ORDER}. */
    public static List<Listing> visibleListings(ServerPlayer player, ExchangeData data) {
        return data.listings().stream()
                .filter(l -> !CoreApi.itemGate().isLocked(player, l.stack().getItem()))
                .sorted(BROWSING_ORDER)
                .toList();
    }

    /** The player's listings, oldest first. */
    public static List<Listing> listingsOf(UUID player, ExchangeData data) {
        return data.listings().stream().filter(l -> l.owner().equals(player)).toList();
    }

    /** The player's open orders, oldest first. */
    public static List<Order> ordersOf(UUID player, ExchangeData data) {
        return data.orders().stream().filter(o -> o.owner().equals(player)).toList();
    }

    private static boolean hiddenFrom(ServerPlayer player, ResourceLocation item) {
        return BuiltInRegistries.ITEM.getOptional(item).map(i -> CoreApi.itemGate().isLocked(player, i)).orElse(true);
    }

    /**
     * Matches fresh copies of {@code item}, exactly as the market hands them out: the only kind a book order holds, as
     * it keeps just the item ID. {@code ignored_components} doesn't apply, so no data is ever lost on the book.
     */
    public static Predicate<ItemStack> fresh(Item item) {
        ItemStack like = new ItemStack(item);
        return stack -> ItemStack.isSameItemSameComponents(stack, like);
    }

    private static ResourceLocation id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static ResourceLocation none() {
        return BuiltInRegistries.ITEM.getDefaultKey();
    }
}
