package variableeconomy.exchange.network;

import variableeconomy.exchange.ExchangeModule;
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
     * Client to server: send me the exchange (the screen asks when it opens), this item's book if given, and every
     * listing if {@code listings}. Whatever is asked for is then resent whenever the exchange changes.
     */
    public record ExchangeRequest(Optional<ResourceLocation> book, boolean listings) implements CustomPacketPayload {
        public static final Type<ExchangeRequest> TYPE = payloadType("exchange_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), ExchangeRequest::book,
                ByteBufCodecs.BOOL, ExchangeRequest::listings,
                ExchangeRequest::new);

        @Override
        public Type<ExchangeRequest> type() {
            return TYPE;
        }
    }

    /**
     * Client to server, from the exchange screen: post an order for {@code quantity} of {@code item} at {@code price}
     * each, cancel order or listing {@code orderId}, take items out of the collection box ({@code item} only, parcel
     * {@code orderId} only, or everything when both are empty), or buy {@code quantity} from listing {@code orderId}
     * at {@code price} each.
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

        public static ExchangeAction fill(long orderId, int quantity) {
            return new ExchangeAction(ExchangeService.Action.FILL, Optional.empty(), false, 0, quantity, orderId);
        }

        public static ExchangeAction buyListing(long listing, int quantity, long price) {
            return new ExchangeAction(ExchangeService.Action.BUY_LISTING, Optional.empty(), true, price, quantity, listing);
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
     * One listing: {@code stack} is one unit exactly as listed. {@code seller} is the seller's name, {@code mine}
     * whether it's the receiving player's; {@code daysLeft} is -1 when listings don't expire.
     */
    public record ListingLine(long id, ItemStack stack, long price, int quantity, int sold, String seller, boolean mine, long daysLeft) {
        static final StreamCodec<RegistryFriendlyByteBuf, ListingLine> CODEC = StreamCodec.of(
                (buf, line) -> {
                    ByteBufCodecs.VAR_LONG.encode(buf, line.id());
                    ItemStack.STREAM_CODEC.encode(buf, line.stack());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.price());
                    ByteBufCodecs.VAR_INT.encode(buf, line.quantity());
                    ByteBufCodecs.VAR_INT.encode(buf, line.sold());
                    ByteBufCodecs.STRING_UTF8.encode(buf, line.seller());
                    buf.writeBoolean(line.mine());
                    buf.writeLong(line.daysLeft());
                },
                buf -> new ListingLine(ByteBufCodecs.VAR_LONG.decode(buf), ItemStack.STREAM_CODEC.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readBoolean(), buf.readLong()));

        public int remaining() {
            return quantity - sold;
        }
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<ListingLine>> LISTINGS_CODEC = ListingLine.CODEC.apply(ByteBufCodecs.list(65_536));

    /** Items with their own components waiting in the collection box: {@code count} units like {@code stack}. */
    public record ParcelLine(long id, ItemStack stack, long count) {
        static final StreamCodec<RegistryFriendlyByteBuf, ParcelLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, ParcelLine::id,
                ItemStack.STREAM_CODEC, ParcelLine::stack,
                ByteBufCodecs.VAR_LONG, ParcelLine::count,
                ParcelLine::new);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<ParcelLine>> PARCELS_CODEC = ParcelLine.CODEC.apply(ByteBufCodecs.list(65_536));

    /**
     * Server to client: every listing the player may see, cheapest first for each item, sent while the exchange screen
     * shows listings. {@code more} says some were left out to keep the packet small.
     */
    public record ExchangeListings(List<ListingLine> listings, boolean more) implements CustomPacketPayload {
        public static final Type<ExchangeListings> TYPE = payloadType("exchange_listings");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeListings> CODEC = StreamCodec.composite(
                LISTINGS_CODEC, ExchangeListings::listings,
                ByteBufCodecs.BOOL, ExchangeListings::more,
                ExchangeListings::new);

        @Override
        public Type<ExchangeListings> type() {
            return TYPE;
        }
    }

    private static final StreamCodec<ByteBuf, ExchangeService.Summary> SUMMARY_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, ExchangeService.Summary::item,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::bestAsk,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::askUnits,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::bestBid,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::bidUnits,
            ExchangeService.Summary::new);
    private static final StreamCodec<ByteBuf, List<ExchangeService.Summary>> SUMMARIES_CODEC = SUMMARY_CODEC.apply(ByteBufCodecs.list(65_536));

    /** One of the receiving player's open orders. {@code daysLeft} is -1 when orders don't expire. */
    public record OrderLine(long id, ResourceLocation item, boolean buy, long price, int quantity, int filled, long daysLeft, long held) {
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
                },
                buf -> new OrderLine(ByteBufCodecs.VAR_LONG.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf), buf.readBoolean(),
                        ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        buf.readLong(), ByteBufCodecs.VAR_LONG.decode(buf)));

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

    /**
     * Server to client: the exchange as this player sees it: the best prices of every item with orders, their own
     * open orders, listings and collection box (plain items and parcels), and the rules (open orders and listings
     * allowed, largest order, the buyer's tax as a fraction, expiry in days or 0, and the fee per post).
     */
    public record Exchange(List<ExchangeService.Summary> summaries, List<OrderLine> orders, List<ListingLine> listings, List<BoxLine> box,
                           List<ParcelLine> parcels, int maxOrders, int maxOrderSize, double tax, int expiryDays, long listingFee)
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
                },
                buf -> new Exchange(SUMMARIES_CODEC.decode(buf), ORDERS_CODEC.decode(buf), LISTINGS_CODEC.decode(buf), BOX_CODEC.decode(buf),
                        PARCELS_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), buf.readDouble(),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf)));

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

    /** Server to client: how this player's last exchange request went; see {@link ExchangeService.Outcome}. */
    /** {@code listed}: the listing's item when the request was about a listing, so the message can name it; else empty. */
    public record ExchangeOutcome(ExchangeService.Action action, ResourceLocation item, ExchangeResult result, int quantity,
                                  int filled, long amount, ItemStack listed) implements CustomPacketPayload {
        public static final Type<ExchangeOutcome> TYPE = payloadType("exchange_outcome");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeOutcome> CODEC = StreamCodec.of(
                (buf, outcome) -> {
                    EXCHANGE_ACTION_CODEC.encode(buf, outcome.action());
                    ResourceLocation.STREAM_CODEC.encode(buf, outcome.item());
                    RESULT_CODEC.encode(buf, outcome.result());
                    ByteBufCodecs.VAR_INT.encode(buf, outcome.quantity());
                    ByteBufCodecs.VAR_INT.encode(buf, outcome.filled());
                    ByteBufCodecs.VAR_LONG.encode(buf, outcome.amount());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, outcome.listed());
                },
                buf -> new ExchangeOutcome(EXCHANGE_ACTION_CODEC.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                        RESULT_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)));

        @Override
        public Type<ExchangeOutcome> type() {
            return TYPE;
        }
    }
}
