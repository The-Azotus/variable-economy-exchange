package variableeconomy.exchange;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The exchange's server config, {@code config/variable_economy_exchange-server.toml}, loaded when a server starts.
 * {@link #install} hands its values to {@link ExchangeSettings}, which the rest of the add-on reads.
 */
public final class ExchangeConfig {
    /** The config's file name. */
    public static final String FILE = "variable_economy_exchange-server.toml";
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<AccessMode> ACCESS_MODE;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue MAX_ORDER_SIZE;
    public static final ModConfigSpec.DoubleValue BUYER_TAX_PERCENT;
    public static final ModConfigSpec.IntValue MAX_POSTS;
    public static final ModConfigSpec.IntValue DURATION_DAYS;
    public static final ModConfigSpec.LongValue LISTING_FEE;
    public static final ModConfigSpec.BooleanValue MATCHING_ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ACCESS_MODE = builder
                .comment("How players reach the exchange.",
                        "COMMAND_AND_BLOCK: the /exchange commands or the Exchange block.",
                        "BLOCK_ONLY: only the Exchange block. Players without operator permission (level 2) can't open the",
                        "exchange or trade on it by command; operators and the console always can.")
                .defineEnum("access_mode", AccessMode.COMMAND_AND_BLOCK);

        builder.comment("The Exchange: players post buy and sell orders at their own prices, and orders for the same item",
                "match automatically, best price first, then oldest first. A trade fills at the price of the order that was",
                "waiting. A sell order holds its items, and a buy order its money and tax, until it fills, is cancelled or",
                "expires. Money always goes to the bank account the order was paid from; bought items, and the items of a",
                "cancelled or expired sell order, wait in the player's collection box.").push("exchange");
        ENABLED = builder
                .comment("Run the exchange (on by default). Turning it off cancels every open order and returns what it held;",
                        "collection boxes stay open so players can take their items.")
                .define("enabled", true);
        MAX_ORDER_SIZE = builder
                .comment("Largest quantity one order may ask for.")
                .defineInRange("max_order_size", 6400, 1, 1_000_000);
        builder.pop();

        builder.push("tax");
        BUYER_TAX_PERCENT = builder
                .comment("Percent the buyer pays on top of each purchase, destroyed (a money sink). Rounded up to a whole bit once",
                        "per buy order over everything it buys, or once per purchase from a listing or fill of a sell order. A",
                        "buy order holds its tax when posted. The seller gets the whole price.")
                .defineInRange("buyer_percent", 5.0, 0.0, 100.0);
        builder.pop();

        builder.push("posts");
        MAX_POSTS = builder
                .comment("How many open orders and listings, together, one player may have at once.")
                .defineInRange("max_per_player", 10, 1, 1000);
        DURATION_DAYS = builder
                .comment("In-game days until an unfilled order or listing expires: a sell order's or listing's items go back to",
                        "the collection box, a buy order's money to its account. Sleeping counts; setting the time backwards",
                        "doesn't. 0 means posts never expire.")
                .defineInRange("duration_days", 7, 0, 3650);
        LISTING_FEE = builder
                .comment("Bits charged for each order or listing posted, never refunded and destroyed, to discourage posting",
                        "and cancelling over and over. 0 for none.")
                .defineInRange("listing_fee", 0L, 0L, 1_000_000_000_000L);
        builder.pop();

        builder.push("matching");
        MATCHING_ENABLED = builder
                .comment("Buy and sell orders whose prices cross trade with each other as soon as one is posted. When false,",
                        "every order waits on the book until another player picks it and fills it, all or part, at its price.",
                        "Players can fill a chosen order either way.")
                .define("enabled", true);
        builder.pop();
        SPEC = builder.build();
    }

    private ExchangeConfig() {
    }

    /** Hands {@link ExchangeSettings} its values. Once, while the mod is constructed. */
    public static void install() {
        ExchangeSettings.install(new ExchangeSettings.Source() {
            @Override
            public boolean blockOnly() {
                return accessMode() == AccessMode.BLOCK_ONLY;
            }

            @Override
            public boolean exchangeEnabled() {
                return value(ENABLED);
            }

            @Override
            public double exchangeTax() {
                return value(BUYER_TAX_PERCENT) / 100.0;
            }

            @Override
            public int maxOpenOrders() {
                return value(MAX_POSTS);
            }

            @Override
            public int orderExpiryDays() {
                return value(DURATION_DAYS);
            }

            @Override
            public long listingFee() {
                return value(LISTING_FEE);
            }

            @Override
            public boolean matchingEnabled() {
                return value(MATCHING_ENABLED);
            }

            @Override
            public int maxOrderSize() {
                return value(MAX_ORDER_SIZE);
            }
        });
    }

    /** Whether players reach the exchange by command and block, or by block only. */
    public enum AccessMode { COMMAND_AND_BLOCK, BLOCK_ONLY }

    public static AccessMode accessMode() {
        return value(ACCESS_MODE);
    }

    /** A server config only loads when a server starts, so before then each value is its default. */
    private static <T> T value(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
