package variableeconomy.exchange;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

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
    public static final ModConfigSpec.IntValue HISTORY_PER_PLAYER;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CATEGORIES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CREATIVE_TAB_FALLBACK;

    /** The categories the screen offers by default, in order; each but the last is an item tag of the mod's. */
    public static final List<String> DEFAULT_CATEGORIES = List.of("building_blocks", "ores_and_minerals", "farming_and_food",
            "weapons_and_armor", "tools_and_equipment", "mob_drops", "redstone_and_technology", "potions_and_brewing",
            ExchangeSettings.MISCELLANEOUS);
    /** Which category an item in no category tag gets from the creative tab it's in, by default. */
    public static final List<String> DEFAULT_CREATIVE_TAB_FALLBACK = List.of("building_blocks=minecraft:building_blocks",
            "building_blocks=minecraft:colored_blocks", "building_blocks=minecraft:natural_blocks",
            "farming_and_food=minecraft:food_and_drinks", "weapons_and_armor=minecraft:combat",
            "tools_and_equipment=minecraft:tools_and_utilities", "redstone_and_technology=minecraft:redstone_blocks");

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

        builder.push("history");
        HISTORY_PER_PLAYER = builder
                .comment("How many lines of history (trades, and orders completed, cancelled, expired or changed) the exchange keeps",
                        "for each player, shown on the screen's My Orders and Collection tabs. The oldest go first. 0 keeps none.")
                .defineInRange("max_per_player", 100, 0, 1000);
        builder.pop();

        builder.push("browse");
        CATEGORIES = builder
                .comment("The categories the exchange screen's Browse tab offers, in order. Each is an item tag: a plain name like",
                        "\"building_blocks\" is the tag variable_economy_exchange:category/building_blocks (the mod ships these, and a",
                        "datapack can change them), and a full ID like \"c:ores\" is that tag. An item may be in several.",
                        "\"" + ExchangeSettings.MISCELLANEOUS + "\" is every item in no other category.",
                        "On screen a plain name is called by the translation screen.variable_economy_exchange.category.<name>, and",
                        "a full ID by tag.item.<namespace>.<path, with / as .>, as mods name their tags; or else by its last word.")
                .defineListAllowEmpty("categories", DEFAULT_CATEGORIES, () -> "", ExchangeConfig::validCategory);
        CREATIVE_TAB_FALLBACK = builder
                .comment("For an item in none of the category tags: the category it gets from a creative tab it's on, as",
                        "\"category=creative_tab_id\" (the category as written above). Lets modded items whose mods use no common",
                        "tags still be sorted; add a mod's own tabs here, such as \"tools_and_equipment=examplemod:tools\".")
                .defineListAllowEmpty("creative_tab_fallback", DEFAULT_CREATIVE_TAB_FALLBACK, () -> "", ExchangeConfig::validFallback);
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

            @Override
            public int historyPerPlayer() {
                return value(HISTORY_PER_PLAYER);
            }

            @Override
            public List<String> categories() {
                return List.copyOf(value(CATEGORIES));
            }

            @Override
            public List<String> creativeTabFallback() {
                return List.copyOf(value(CREATIVE_TAB_FALLBACK));
            }
        });
    }

    /** A category: a plain name or a full ID, which must make a valid tag ID. */
    static boolean validCategory(Object entry) {
        return entry instanceof String s && ExchangeSettings.categoryTag(s).isPresent();
    }

    /** {@code category=creative_tab_id}. */
    static boolean validFallback(Object entry) {
        if (!(entry instanceof String s)) {
            return false;
        }
        int eq = s.indexOf('=');
        return eq > 0 && validCategory(s.substring(0, eq).trim()) && ResourceLocation.tryParse(s.substring(eq + 1).trim()) != null;
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
