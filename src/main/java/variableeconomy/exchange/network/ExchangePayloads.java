package variableeconomy.exchange.network;

import variableeconomy.exchange.ExchangeModule;
import variableeconomy.exchange.data.ExchangeData;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import variableeconomy.exchange.service.ExchangeService;
import variableeconomy.exchange.service.ExchangeResult;

import java.util.List;
import java.util.Optional;

/** Every packet the exchange sends. Prices, totals and balances are in bits. */
public final class ExchangePayloads {
    private ExchangePayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ExchangeModule.MOD_ID, path));
    }

    private static final StreamCodec<ByteBuf, ExchangeResult> RESULT_CODEC =
            ByteBufCodecs.VAR_INT.map(i -> ExchangeResult.values()[i], ExchangeResult::ordinal);

    /** The longest player name a line carries: a player name's limit. */
    public static final int MAX_NAME_LENGTH = ExchangeService.BookOrder.MAX_POSTER_LENGTH;

    /** Cuts an unusually long name (possible behind some proxies) to fit, so it can't make a packet fail to send. */
    static String fitName(String name) {
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private static final StreamCodec<ByteBuf, String> NAME_CODEC = ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH);

    /**
     * Server to client: the balance of the account the player trades with, and whether the exchange is open. Sent when
     * the screen opens and whenever either changes. The screen reads held items from the player's own inventory, which
     * the server keeps in sync while the screen is open.
     */
    public record ExchangeState(long balance, boolean enabled) implements CustomPacketPayload {
        public static final Type<ExchangeState> TYPE = payloadType("exchange_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeState> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, ExchangeState::balance,
                ByteBufCodecs.BOOL, ExchangeState::enabled,
                ExchangeState::new);

        @Override
        public Type<ExchangeState> type() {
            return TYPE;
        }
    }

    // ---- Exchange ----------------------------------------------------------------------------------------------

    private static final StreamCodec<ByteBuf, ExchangeService.Action> EXCHANGE_ACTION_CODEC =
            ByteBufCodecs.idMapper(i -> ExchangeService.Action.values()[i], ExchangeService.Action::ordinal);

    /**
     * Client to server: send me the exchange (the screen asks when it opens), this item's book if given, every offer
     * for sale if {@code browse}, and my history if {@code history}. Whatever is asked for is then resent whenever the
     * exchange changes.
     */
    public record ExchangeRequest(Optional<ResourceLocation> book, boolean browse, boolean history) implements CustomPacketPayload {
        public static final Type<ExchangeRequest> TYPE = payloadType("exchange_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), ExchangeRequest::book,
                ByteBufCodecs.BOOL, ExchangeRequest::browse,
                ByteBufCodecs.BOOL, ExchangeRequest::history,
                ExchangeRequest::new);

        @Override
        public Type<ExchangeRequest> type() {
            return TYPE;
        }
    }

    /**
     * Client to server, from the exchange screen: post an order for {@code quantity} of {@code item} at {@code price}
     * each; cancel order or listing {@code orderId}; take items out of the collection box ({@code item} only, parcel
     * {@code orderId} only, or everything when both are empty); buy {@code quantity} from listing {@code orderId} at
     * {@code price} each; fill {@code quantity} of order {@code orderId} at {@code price} each (0: at whatever its
     * price is); change the price of order or listing {@code orderId} to {@code price}; or lower it to
     * {@code quantity} units.
     */
    public record ExchangeAction(ExchangeService.Action action, Optional<ResourceLocation> item, boolean buy, long price, int quantity,
                                 long orderId) implements CustomPacketPayload {
        public static final Type<ExchangeAction> TYPE = payloadType("exchange_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeAction> CODEC = StreamCodec.composite(
                EXCHANGE_ACTION_CODEC, ExchangeAction::action,
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), ExchangeAction::item,
                ByteBufCodecs.BOOL, ExchangeAction::buy,
                ByteBufCodecs.VAR_LONG, ExchangeAction::price,
                ByteBufCodecs.VAR_INT, ExchangeAction::quantity,
                ByteBufCodecs.VAR_LONG, ExchangeAction::orderId,
                ExchangeAction::new);

        public static ExchangeAction post(ResourceLocation item, boolean buy, long price, int quantity) {
            return new ExchangeAction(ExchangeService.Action.POST, Optional.of(item), buy, price, quantity, 0);
        }

        public static ExchangeAction cancel(long orderId) {
            return new ExchangeAction(ExchangeService.Action.CANCEL, Optional.empty(), false, 0, 0, orderId);
        }

        public static ExchangeAction collect(Optional<ResourceLocation> item) {
            return new ExchangeAction(ExchangeService.Action.COLLECT, item, false, 0, 0, 0);
        }

        public static ExchangeAction collectParcel(long parcel) {
            return new ExchangeAction(ExchangeService.Action.COLLECT, Optional.empty(), false, 0, 0, parcel);
        }

        /** Fills {@code quantity} of an order at {@code price} each, the price the player saw. */
        public static ExchangeAction fill(long orderId, int quantity, long price) {
            return new ExchangeAction(ExchangeService.Action.FILL, Optional.empty(), false, price, quantity, orderId);
        }

        public static ExchangeAction buyListing(long listing, int quantity, long price) {
            return new ExchangeAction(ExchangeService.Action.BUY_LISTING, Optional.empty(), true, price, quantity, listing);
        }

        public static ExchangeAction reprice(long orderId, long price) {
            return new ExchangeAction(ExchangeService.Action.REPRICE, Optional.empty(), false, price, 0, orderId);
        }

        public static ExchangeAction reduce(long orderId, int remaining) {
            return new ExchangeAction(ExchangeService.Action.REDUCE, Optional.empty(), false, 0, remaining, orderId);
        }

        @Override
        public Type<ExchangeAction> type() {
            return TYPE;
        }
    }

    /**
     * Client to server, from the exchange screen: list {@code quantity} units of the item in main-inventory {@code slot}
     * at {@code price} each. {@code expected} is the item the player saw there.
     */
    public record ListItem(int slot, ItemStack expected, long price, int quantity) implements CustomPacketPayload {
        public static final Type<ListItem> TYPE = payloadType("exchange_list");
        public static final StreamCodec<RegistryFriendlyByteBuf, ListItem> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ListItem::slot,
                ItemStack.OPTIONAL_STREAM_CODEC, ListItem::expected,
                ByteBufCodecs.VAR_LONG, ListItem::price,
                ByteBufCodecs.VAR_INT, ListItem::quantity,
                ListItem::new);

        @Override
        public Type<ListItem> type() {
            return TYPE;
        }
    }

    /**
     * One of the receiving player's listings: {@code stack} is one unit exactly as listed. {@code daysLeft} is -1 when
     * listings don't expire; {@code ageDays} how many days ago it was posted.
     */
    public record ListingLine(long id, ItemStack stack, long price, int quantity, int sold, long daysLeft, long ageDays) {
        static final StreamCodec<RegistryFriendlyByteBuf, ListingLine> CODEC = StreamCodec.of(
                (buf, line) -> {
                    ByteBufCodecs.VAR_LONG.encode(buf, line.id());
                    ItemStack.STREAM_CODEC.encode(buf, line.stack());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.price());
                    ByteBufCodecs.VAR_INT.encode(buf, line.quantity());
                    ByteBufCodecs.VAR_INT.encode(buf, line.sold());
                    buf.writeLong(line.daysLeft());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.ageDays());
                },
                buf -> new ListingLine(ByteBufCodecs.VAR_LONG.decode(buf), ItemStack.STREAM_CODEC.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), buf.readLong(), ByteBufCodecs.VAR_LONG.decode(buf)));

        public int remaining() {
            return quantity - sold;
        }
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<ListingLine>> LISTINGS_CODEC = ListingLine.CODEC.apply(ByteBufCodecs.list(65_536));

    /**
     * One offer for sale, as the Browse tab shows it: a sell order ({@code listing} false; {@code stack} is a fresh unit,
     * and buying fills the order) or a listing ({@code stack} is one unit exactly as listed). {@code seller} is the
     * seller's name and {@code mine} whether it's the receiving player's; {@code daysLeft} is -1 when posts don't
     * expire, and {@code ageDays} how many days ago it was posted.
     */
    public record OfferLine(boolean listing, long id, ItemStack stack, long price, int remaining, String seller, boolean mine, long daysLeft,
                            long ageDays) {
        public OfferLine {
            seller = fitName(seller);
        }

        static final StreamCodec<RegistryFriendlyByteBuf, OfferLine> CODEC = StreamCodec.of(
                (buf, line) -> {
                    buf.writeBoolean(line.listing());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.id());
                    ItemStack.STREAM_CODEC.encode(buf, line.stack());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.price());
                    ByteBufCodecs.VAR_INT.encode(buf, line.remaining());
                    NAME_CODEC.encode(buf, line.seller());
                    buf.writeBoolean(line.mine());
                    buf.writeLong(line.daysLeft());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.ageDays());
                },
                buf -> new OfferLine(buf.readBoolean(), ByteBufCodecs.VAR_LONG.decode(buf), ItemStack.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), NAME_CODEC.decode(buf), buf.readBoolean(),
                        buf.readLong(), ByteBufCodecs.VAR_LONG.decode(buf)));
    }

    /**
     * Server to client: every sell order and listing the player may see, newest first, sent while the exchange screen
     * shows the Browse tab. {@code more} says some were left out to keep the packet small.
     */
    public record ExchangeBrowse(List<OfferLine> offers, boolean more) implements CustomPacketPayload {
        public static final Type<ExchangeBrowse> TYPE = payloadType("exchange_browse");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeBrowse> CODEC = StreamCodec.composite(
                OfferLine.CODEC.apply(ByteBufCodecs.list(131_072)), ExchangeBrowse::offers,
                ByteBufCodecs.BOOL, ExchangeBrowse::more,
                ExchangeBrowse::new);

        @Override
        public Type<ExchangeBrowse> type() {
            return TYPE;
        }
    }

    /** Items with their own components waiting in the collection box: {@code count} units like {@code stack}. */
    public record ParcelLine(long id, ItemStack stack, long count) {
        static final StreamCodec<RegistryFriendlyByteBuf, ParcelLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, ParcelLine::id,
                ItemStack.STREAM_CODEC, ParcelLine::stack,
                ByteBufCodecs.VAR_LONG, ParcelLine::count,
                ParcelLine::new);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<ParcelLine>> PARCELS_CODEC = ParcelLine.CODEC.apply(ByteBufCodecs.list(65_536));

    private static final StreamCodec<ByteBuf, ExchangeService.Summary> SUMMARY_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, ExchangeService.Summary::item,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::bestAsk,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::askUnits,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::bestBid,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::bidUnits,
            ExchangeService.Summary::new);
    private static final StreamCodec<ByteBuf, List<ExchangeService.Summary>> SUMMARIES_CODEC = SUMMARY_CODEC.apply(ByteBufCodecs.list(65_536));

    /**
     * One of the receiving player's open orders. {@code daysLeft} is -1 when orders don't expire; {@code ageDays} how
     * many days ago it was posted; {@code traded} what its units traded so far came to, before tax (a buy order's tax is
     * worked out on it, so the screen can show exactly what changing the order moves).
     */
    public record OrderLine(long id, ResourceLocation item, boolean buy, long price, int quantity, int filled, long daysLeft, long held,
                            long ageDays, long traded) {
        static final StreamCodec<ByteBuf, OrderLine> CODEC = StreamCodec.of(
                (buf, line) -> {
                    ByteBufCodecs.VAR_LONG.encode(buf, line.id());
                    ResourceLocation.STREAM_CODEC.encode(buf, line.item());
                    buf.writeBoolean(line.buy());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.price());
                    ByteBufCodecs.VAR_INT.encode(buf, line.quantity());
                    ByteBufCodecs.VAR_INT.encode(buf, line.filled());
                    buf.writeLong(line.daysLeft());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.held());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.ageDays());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.traded());
                },
                buf -> new OrderLine(ByteBufCodecs.VAR_LONG.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf), buf.readBoolean(),
                        ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        buf.readLong(), ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf)));

        public int remaining() {
            return quantity - filled;
        }
    }

    private static final StreamCodec<ByteBuf, List<OrderLine>> ORDERS_CODEC = OrderLine.CODEC.apply(ByteBufCodecs.list(1000));

    /** Items waiting in the collection box. */
    public record BoxLine(ResourceLocation item, long count) {
        static final StreamCodec<ByteBuf, BoxLine> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, BoxLine::item,
                ByteBufCodecs.VAR_LONG, BoxLine::count,
                BoxLine::new);
    }

    private static final StreamCodec<ByteBuf, List<BoxLine>> BOX_CODEC = BoxLine.CODEC.apply(ByteBufCodecs.list(65_536));

    /** The Browse tab's categories (see {@code browse.categories}) and the creative tabs that stand in for them. */
    public record Categories(List<String> categories, List<String> creativeTabFallback) {
        /** Leaves out any entry too long to send (no valid one is), so the config can't make the exchange fail to send. */
        public Categories {
            categories = categories.stream().filter(s -> s.length() <= 256).limit(256).toList();
            creativeTabFallback = creativeTabFallback.stream().filter(s -> s.length() <= 512).limit(1024).toList();
        }

        static final StreamCodec<ByteBuf, Categories> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(256)), Categories::categories,
                ByteBufCodecs.stringUtf8(512).apply(ByteBufCodecs.list(1024)), Categories::creativeTabFallback,
                Categories::new);
    }

    /**
     * Server to client: the exchange as this player sees it: the best prices of every item with orders, their own
     * open orders, listings and collection box (plain items and parcels), and the rules (open orders and listings
     * allowed, largest order, the buyer's tax as a fraction, expiry in days or 0, the fee per post, and the Browse
     * tab's categories).
     */
    public record Exchange(List<ExchangeService.Summary> summaries, List<OrderLine> orders, List<ListingLine> listings, List<BoxLine> box,
                           List<ParcelLine> parcels, int maxOrders, int maxOrderSize, double tax, int expiryDays, long listingFee,
                           Categories categories)
            implements CustomPacketPayload {
        public static final Type<Exchange> TYPE = payloadType("exchange");
        // Written out by hand: StreamCodec.composite takes at most six fields.
        public static final StreamCodec<RegistryFriendlyByteBuf, Exchange> CODEC = StreamCodec.of(
                (buf, exchange) -> {
                    SUMMARIES_CODEC.encode(buf, exchange.summaries());
                    ORDERS_CODEC.encode(buf, exchange.orders());
                    LISTINGS_CODEC.encode(buf, exchange.listings());
                    BOX_CODEC.encode(buf, exchange.box());
                    PARCELS_CODEC.encode(buf, exchange.parcels());
                    ByteBufCodecs.VAR_INT.encode(buf, exchange.maxOrders());
                    ByteBufCodecs.VAR_INT.encode(buf, exchange.maxOrderSize());
                    buf.writeDouble(exchange.tax());
                    ByteBufCodecs.VAR_INT.encode(buf, exchange.expiryDays());
                    ByteBufCodecs.VAR_LONG.encode(buf, exchange.listingFee());
                    Categories.CODEC.encode(buf, exchange.categories());
                },
                buf -> new Exchange(SUMMARIES_CODEC.decode(buf), ORDERS_CODEC.decode(buf), LISTINGS_CODEC.decode(buf), BOX_CODEC.decode(buf),
                        PARCELS_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), buf.readDouble(),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf), Categories.CODEC.decode(buf)));

        /** How many items wait in the box in all. */
        public long boxed() {
            return box.stream().mapToLong(BoxLine::count).sum() + parcels.stream().mapToLong(ParcelLine::count).sum();
        }

        /** Open orders and listings together, which the limit counts. */
        public int open() {
            return orders.size() + listings.size();
        }

        @Override
        public Type<Exchange> type() {
            return TYPE;
        }
    }

    private static final StreamCodec<ByteBuf, ExchangeService.BookOrder> BOOK_ORDER_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, ExchangeService.BookOrder::id,
            ByteBufCodecs.VAR_LONG, ExchangeService.BookOrder::price,
            ByteBufCodecs.VAR_INT, ExchangeService.BookOrder::quantity,
            ByteBufCodecs.stringUtf8(ExchangeService.BookOrder.MAX_POSTER_LENGTH), ExchangeService.BookOrder::poster,
            ByteBufCodecs.BOOL, ExchangeService.BookOrder::mine,
            ByteBufCodecs.VAR_LONG, ExchangeService.BookOrder::daysLeft,
            ExchangeService.BookOrder::new);

    /** Server to client: one item's book, order by order, best first, sent when the screen asks and whenever it changes. */
    public record ExchangeBook(ResourceLocation item, List<ExchangeService.BookOrder> asks, List<ExchangeService.BookOrder> bids)
            implements CustomPacketPayload {
        /** The most prices sent for each side. */
        public static final int DEPTH = 8;
        public static final Type<ExchangeBook> TYPE = payloadType("exchange_book");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeBook> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, ExchangeBook::item,
                BOOK_ORDER_CODEC.apply(ByteBufCodecs.list(DEPTH)), ExchangeBook::asks,
                BOOK_ORDER_CODEC.apply(ByteBufCodecs.list(DEPTH)), ExchangeBook::bids,
                ExchangeBook::new);

        @Override
        public Type<ExchangeBook> type() {
            return TYPE;
        }
    }

    private static final StreamCodec<ByteBuf, ExchangeData.HistoryKind> HISTORY_KIND_CODEC =
            ByteBufCodecs.idMapper(i -> ExchangeData.HistoryKind.values()[i], ExchangeData.HistoryKind::ordinal);
    private static final StreamCodec<ByteBuf, ExchangeData.Side> SIDE_CODEC =
            ByteBufCodecs.idMapper(i -> ExchangeData.Side.values()[i], ExchangeData.Side::ordinal);

    /** One line of the receiving player's history (see {@link ExchangeData.HistoryEntry}); {@code counterparty} is a name or empty. */
    public record HistoryLine(ExchangeData.HistoryKind kind, ExchangeData.Side side, long orderId, ItemStack item, long units, long price,
                              long amount, long tax, String counterparty, long time) {
        public HistoryLine {
            counterparty = fitName(counterparty);
        }

        static final StreamCodec<RegistryFriendlyByteBuf, HistoryLine> CODEC = StreamCodec.of(
                (buf, line) -> {
                    HISTORY_KIND_CODEC.encode(buf, line.kind());
                    SIDE_CODEC.encode(buf, line.side());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.orderId());
                    ItemStack.STREAM_CODEC.encode(buf, line.item());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.units());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.price());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.amount());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.tax());
                    NAME_CODEC.encode(buf, line.counterparty());
                    buf.writeLong(line.time());
                },
                buf -> new HistoryLine(HISTORY_KIND_CODEC.decode(buf), SIDE_CODEC.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                        ItemStack.STREAM_CODEC.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf), NAME_CODEC.decode(buf), buf.readLong()));
    }

    /**
     * Server to client: the receiving player's history, newest first, sent while the screen shows My Orders or
     * Collection. {@code now} is the server's clock, so ages read right whatever the client's clock says; {@code more}
     * says older lines were left out to keep the packet small.
     */
    public record ExchangeHistory(List<HistoryLine> lines, long now, boolean more) implements CustomPacketPayload {
        public static final Type<ExchangeHistory> TYPE = payloadType("exchange_history");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeHistory> CODEC = StreamCodec.composite(
                HistoryLine.CODEC.apply(ByteBufCodecs.list(4096)), ExchangeHistory::lines,
                ByteBufCodecs.VAR_LONG, ExchangeHistory::now,
                ByteBufCodecs.BOOL, ExchangeHistory::more,
                ExchangeHistory::new);

        @Override
        public Type<ExchangeHistory> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: how this player's last exchange request went; see {@link ExchangeService.Outcome}. {@code listed}
     * is the listing's item when the request was about a listing, so the message can name it; else empty.
     */
    public record ExchangeOutcome(ExchangeService.Action action, ResourceLocation item, ExchangeResult result, int quantity,
                                  int filled, long amount, long orderId, ItemStack listed) implements CustomPacketPayload {
        public static final Type<ExchangeOutcome> TYPE = payloadType("exchange_outcome");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeOutcome> CODEC = StreamCodec.of(
                (buf, outcome) -> {
                    EXCHANGE_ACTION_CODEC.encode(buf, outcome.action());
                    ResourceLocation.STREAM_CODEC.encode(buf, outcome.item());
                    RESULT_CODEC.encode(buf, outcome.result());
                    ByteBufCodecs.VAR_INT.encode(buf, outcome.quantity());
                    ByteBufCodecs.VAR_INT.encode(buf, outcome.filled());
                    ByteBufCodecs.VAR_LONG.encode(buf, outcome.amount());
                    ByteBufCodecs.VAR_LONG.encode(buf, outcome.orderId());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, outcome.listed());
                },
                buf -> new ExchangeOutcome(EXCHANGE_ACTION_CODEC.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                        RESULT_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)));

        @Override
        public Type<ExchangeOutcome> type() {
            return TYPE;
        }
    }
}
