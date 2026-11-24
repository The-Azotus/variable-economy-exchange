package variableeconomy.exchange;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.player.Player;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The exchange's server settings. They come from {@code config/variable_economy_exchange-server.toml},
 * whose keys are named below; {@link ExchangeConfig} installs them when the mod is constructed. A server config only
 * loads when a server starts, so until then each value is the config's default.
 */
public final class ExchangeSettings {
    /** Where the values come from. */
    public interface Source {
        /**
         * Whether players without operator permission must use a block, not commands (access_mode BLOCK_ONLY).
         *
         * @return the value
         */
        boolean blockOnly();

        /**
         * Whether the exchange runs (exchange.enabled).
         *
         * @return the value
         */
        boolean exchangeEnabled();

        /**
         * The tax the buyer pays on top of each purchase, as a fraction (tax.buyer_percent / 100).
         *
         * @return the value
         */
        double exchangeTax();

        /**
         * How many open orders and listings one player may have (posts.max_per_player).
         *
         * @return the value
         */
        int maxOpenOrders();

        /**
         * In-game days until an order or listing expires, 0 for never (posts.duration_days).
         *
         * @return the value
         */
        int orderExpiryDays();

        /**
         * The fee charged for each order or listing posted, in bits (posts.listing_fee).
         *
         * @return the value
         */
        long listingFee();

        /**
         * Whether crossing orders trade as soon as one is posted (matching.enabled).
         *
         * @return the value
         */
        boolean matchingEnabled();

        /**
         * The largest quantity one order may ask for (exchange.max_order_size).
         *
         * @return the value
         */
        int maxOrderSize();

        /**
         * How many lines of history are kept for each player (history.max_per_player).
         *
         * @return the value
         */
        int historyPerPlayer();

        /**
         * The Browse tab's categories, in order (browse.categories).
         *
         * @return the value
         */
        List<String> categories();

        /**
         * {@code category=creative_tab_id} entries for items in no category tag (browse.creative_tab_fallback).
         *
         * @return the value
         */
        List<String> creativeTabFallback();
    }

    /** The category that holds every item in no other category. */
    public static final String MISCELLANEOUS = "miscellaneous";

    private static volatile Source source;

    private ExchangeSettings() {
    }

    /** Installs where the values come from. Once, while the mod is constructed. */
    public static void install(Source values) {
        source = Objects.requireNonNull(values, "values");
    }

    private static Source source() {
        Source installed = source;
        if (installed == null) {
            throw new IllegalStateException("ExchangeSettings isn't installed yet");
        }
        return installed;
    }

    /** Whether players without operator permission must use a block, not commands (access_mode BLOCK_ONLY). */
    public static boolean blockOnly() {
        return source().blockOnly();
    }

    /** Whether the exchange runs (exchange.enabled). */
    public static boolean exchangeEnabled() {
        return source().exchangeEnabled();
    }

    /** The tax the buyer pays on top of each purchase, as a fraction (tax.buyer_percent / 100). */
    public static double exchangeTax() {
        return source().exchangeTax();
    }

    /** How many open orders and listings one player may have (posts.max_per_player). */
    public static int maxOpenOrders() {
        return source().maxOpenOrders();
    }

    /** In-game days until an order or listing expires, 0 for never (posts.duration_days). */
    public static int orderExpiryDays() {
        return source().orderExpiryDays();
    }

    /** The fee charged for each order or listing posted, in bits (posts.listing_fee). */
    public static long listingFee() {
        return source().listingFee();
    }

    /** Whether crossing orders trade as soon as one is posted (matching.enabled). */
    public static boolean matchingEnabled() {
        return source().matchingEnabled();
    }

    /** The largest quantity one order may ask for (exchange.max_order_size). */
    public static int maxOrderSize() {
        return source().maxOrderSize();
    }

    /** How many lines of history are kept for each player (history.max_per_player). */
    public static int historyPerPlayer() {
        return source().historyPerPlayer();
    }

    /** The Browse tab's categories, in order (browse.categories). */
    public static List<String> categories() {
        return source().categories();
    }

    /** {@code category=creative_tab_id} entries for items in no category tag (browse.creative_tab_fallback). */
    public static List<String> creativeTabFallback() {
        return source().creativeTabFallback();
    }

    /**
     * The item tag a category stands for: a plain name is {@code variable_economy_exchange:category/<name>}, a full ID is
     * itself; empty for an entry that isn't a valid ID. {@link #MISCELLANEOUS} also holds every item in no other category.
     */
    public static Optional<ResourceLocation> categoryTag(String category) {
        if (category.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(category.contains(":") ? ResourceLocation.tryParse(category)
                : ResourceLocation.tryBuild(ExchangeModule.MOD_ID, "category/" + category));
    }

    /** Whether {@code source} may use the exchange's commands: always for operators and the console, otherwise unless block-only. */
    public static boolean commandsAllowed(CommandSourceStack source) {
        return commandsAllowed(blockOnly(), source.hasPermission(Commands.LEVEL_GAMEMASTERS));
    }

    /** Whether {@code player} may use the exchange's commands. */
    public static boolean commandsAllowed(Player player) {
        return commandsAllowed(blockOnly(), player.hasPermissions(Commands.LEVEL_GAMEMASTERS));
    }

    /** Operators always may; everyone else unless the server is block-only. */
    public static boolean commandsAllowed(boolean blockOnly, boolean operator) {
        return operator || !blockOnly;
    }
}
