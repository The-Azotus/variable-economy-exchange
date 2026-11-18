package variableeconomy.exchange.network;

import variableeconomy.exchange.ExchangeSettings;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.CoreApi;
import variableeconomy.exchange.data.ExchangeData;
import variableeconomy.exchange.menu.ExchangeMenu;
import variableeconomy.exchange.service.ExchangeService;
import variableeconomy.exchange.service.ExchangeResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server side of the exchange screen: keeps every open exchange screen up to date, and handles its requests. Requests
 * from a player without the exchange open are stale or forged, and ignored. Server thread only.
 */
public final class ExchangeSync {
    /** How often to re-check viewers' balance and inventory for changes made elsewhere (pickups, the bank). */
    private static final int STATE_INTERVAL_TICKS = 20;
    /** The container ID that sets a slot of the client's own inventory, whatever menu is open. */
    private static final int PLAYER_INVENTORY = -2;
    /** The most bytes of listings sent at once, in order of item ID; the rest are left out and the screen says so. */
    private static final int LISTINGS_BUDGET_BYTES = 512 * 1024;

    private static final Map<UUID, ExchangePayloads.ExchangeState> lastSentState = new HashMap<>();
    /** Each viewer's main inventory as last sent to them (see {@link #syncInventory}). */
    private static final Map<UUID, List<ItemStack>> lastSentItems = new HashMap<>();
    /** The item whose book each viewer last asked for, resent to them whenever the exchange changes. */
    private static final Map<UUID, ResourceLocation> watchedBooks = new HashMap<>();
    /** Viewers browsing the listings, who get them again whenever the exchange changes. */
    private static final Set<UUID> watchingListings = new HashSet<>();

    private ExchangeSync() {
    }

    public static List<ServerPlayer> viewers(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream()
                .filter(p -> p.containerMenu instanceof ExchangeMenu)
                .toList();
    }

    /** Whether {@code player} has the exchange open, so their request may be handled. */
    private static boolean viewing(ServerPlayer player) {
        return player.containerMenu instanceof ExchangeMenu menu && menu.stillValid(player);
    }

    /** Call right after the exchange screen opens. */
    public static void sendFullState(ServerPlayer player) {
        // A newly opened screen starts empty, so resend even if nothing changed since the last send.
        lastSentState.remove(player.getUUID());
        lastSentItems.remove(player.getUUID());
        watchedBooks.remove(player.getUUID());
        watchingListings.remove(player.getUUID());
        sendState(player);
        sendExchange(player);
    }

    /** Sends a viewer their balance and the exchange's state, but only if it changed, after syncing their inventory. */
    public static void sendState(ServerPlayer player) {
        syncInventory(player);
        ExchangePayloads.ExchangeState state = new ExchangePayloads.ExchangeState(
                CoreApi.money().balance(player.server, CoreApi.accounts().active(player)).orElse(0L), ExchangeSettings.exchangeEnabled());
        if (!state.equals(lastSentState.get(player.getUUID()))) {
            lastSentState.put(player.getUUID(), state);
            PacketDistributor.sendToPlayer(player, state);
        }
    }

    /**
     * Sends a viewer the main-inventory slots that changed since the last send (all of them the first time). Vanilla
     * doesn't sync the inventory while a slotless menu is open, and the exchange screen reads the client's copy.
     */
    private static void syncInventory(ServerPlayer player) {
        List<ItemStack> items = player.getInventory().items;
        List<ItemStack> sent = lastSentItems.computeIfAbsent(player.getUUID(), id -> new ArrayList<>());
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (i >= sent.size() || !ItemStack.matches(stack, sent.get(i))) {
                if (i >= sent.size()) {
                    sent.add(stack.copy());
                } else {
                    sent.set(i, stack.copy());
                }
                player.connection.send(new ClientboundContainerSetSlotPacket(PLAYER_INVENTORY, 0, i, stack.copy()));
            }
        }
    }

    /** Every tick: forgets players who closed the screen, and refreshes the rest once a second. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % STATE_INTERVAL_TICKS != 0) {
            return;
        }
        List<ServerPlayer> viewers = viewers(server);
        Set<UUID> open = new HashSet<>();
        viewers.forEach(v -> open.add(v.getUUID()));
        lastSentState.keySet().retainAll(open);
        lastSentItems.keySet().retainAll(open);
        watchedBooks.keySet().retainAll(open);
        watchingListings.retainAll(open);
        viewers.forEach(ExchangeSync::sendState);
    }

    /** After an account's balance changed: viewers who use that account see it at once. */
    public static void balanceChanged(MinecraftServer server, AccountId account) {
        for (ServerPlayer viewer : viewers(server)) {
            if (CoreApi.accounts().active(viewer).equals(account)) {
                sendState(viewer);
            }
        }
    }

    /** After locks changed for a player, or anyone ({@code null}): what they may see on the exchange may have too. */
    public static void locksChanged(MinecraftServer server, Optional<ServerPlayer> player) {
        for (ServerPlayer viewer : viewers(server)) {
            if (player.isEmpty() || player.get() == viewer) {
                sendExchange(viewer);
                sendBook(viewer);
                sendListings(viewer);
            }
        }
    }

    // ---- Requests --------------------------------------------------------------------------------------------

    /**
     * The exchange's state, and an item's book if asked for, which is then resent whenever the exchange changes.
     * Works with the exchange turned off, so a player can still take what waits in their box.
     */
    static void handleExchangeRequest(ExchangePayloads.ExchangeRequest request, ServerPlayer player) {
        if (!viewing(player)) {
            return;
        }
        request.book().ifPresentOrElse(item -> watchedBooks.put(player.getUUID(), item), () -> watchedBooks.remove(player.getUUID()));
        if (request.listings()) {
            watchingListings.add(player.getUUID());
        } else {
            watchingListings.remove(player.getUUID());
        }
        sendExchange(player);
        sendBook(player);
        sendListings(player);
    }

    /** Lists an item from the open exchange screen. */
    static void handleListItem(ExchangePayloads.ListItem request, ServerPlayer player) {
        if (!viewing(player)) {
            return;
        }
        replyExchange(player, ExchangeService.list(player, request.slot(), request.expected(), request.price(), request.quantity()));
    }

    /** Post, cancel, collect, fill an order or buy a listing from the open exchange screen. */
    static void handleExchangeAction(ExchangePayloads.ExchangeAction request, ServerPlayer player) {
        if (!viewing(player)) {
            return;
        }
        ExchangeService.Outcome outcome = switch (request.action()) {
            case POST -> request.item().flatMap(BuiltInRegistries.ITEM::getOptional)
                    .map(item -> ExchangeService.post(player, item, request.buy(), request.price(), request.quantity()))
                    .orElse(new ExchangeService.Outcome(ExchangeService.Action.POST, ExchangeResult.NOT_LISTED,
                            BuiltInRegistries.ITEM.getDefaultKey(), 0, 0, 0, 0));
            case CANCEL -> ExchangeService.cancel(player, request.orderId());
            case COLLECT -> request.item().isEmpty() && request.orderId() > 0
                    ? ExchangeService.collectParcel(player, request.orderId())
                    : ExchangeService.collect(player, request.item());
            case BUY_LISTING -> ExchangeService.buyListing(player, request.orderId(), request.quantity(), request.price());
            case FILL -> ExchangeService.fill(player, request.orderId(), request.quantity());
            // Listing comes as its own packet, with the item the player saw.
            case LIST -> new ExchangeService.Outcome(ExchangeService.Action.LIST, ExchangeResult.NOT_LISTED,
                    BuiltInRegistries.ITEM.getDefaultKey(), 0, 0, 0, 0);
        };
        replyExchange(player, outcome);
    }

    /** Tells the player how an exchange request went, and refreshes whoever's screen it changed. */
    private static void replyExchange(ServerPlayer player, ExchangeService.Outcome outcome) {
        PacketDistributor.sendToPlayer(player, new ExchangePayloads.ExchangeOutcome(outcome.action(), outcome.item(), outcome.result(),
                outcome.quantity(), outcome.filled(), outcome.amount(), outcome.listed()));
        if (outcome.ok() && outcome.action() != ExchangeService.Action.COLLECT) {
            broadcastExchange(player.server);
        } else {
            sendExchange(player);
            sendBook(player);
        }
        sendState(player);
    }

    // ---- Sending ---------------------------------------------------------------------------------------------

    /** After the exchange changed: everyone with it open gets it, and the book they're looking at. */
    public static void broadcastExchange(MinecraftServer server) {
        // Measured and sorted once for every viewer, only if someone is browsing listings.
        List<SizedListing> listings = null;
        for (ServerPlayer player : viewers(server)) {
            sendExchange(player);
            sendBook(player);
            if (watchingListings.contains(player.getUUID())) {
                if (listings == null) {
                    listings = sizedListings(server);
                }
                sendListings(player, listings);
            }
        }
    }

    public static void sendExchange(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, exchangeFor(player));
    }

    private static void sendBook(ServerPlayer player) {
        ResourceLocation item = watchedBooks.get(player.getUUID());
        if (item != null) {
            ExchangeService.Book book = ExchangeService.book(player, ExchangeData.get(player.server), item, ExchangePayloads.ExchangeBook.DEPTH);
            PacketDistributor.sendToPlayer(player, new ExchangePayloads.ExchangeBook(book.item(), book.asks(), book.bids()));
        }
    }

    /** A listing and how many bytes its item takes to send. */
    private record SizedListing(ExchangeData.Listing listing, int bytes) {
    }

    /** Every listing, in browsing order, measured. */
    private static List<SizedListing> sizedListings(MinecraftServer server) {
        return ExchangeData.get(server).listings().stream()
                .sorted(ExchangeService.BROWSING_ORDER)
                .map(l -> new SizedListing(l, ExchangeService.encodedSize(l.stack(), server.registryAccess())))
                .toList();
    }

    /** Every listing the player may see, if they're browsing them, up to {@link #LISTINGS_BUDGET_BYTES}. */
    private static void sendListings(ServerPlayer player) {
        if (watchingListings.contains(player.getUUID())) {
            sendListings(player, sizedListings(player.server));
        }
    }

    /** The listings of {@code all} the player may see, up to {@link #LISTINGS_BUDGET_BYTES}. */
    private static void sendListings(ServerPlayer player, List<SizedListing> all) {
        int expiry = ExchangeSettings.orderExpiryDays();
        long today = ExchangeService.today(player.server);
        List<ExchangePayloads.ListingLine> lines = new ArrayList<>();
        int bytes = 0;
        boolean more = false;
        for (SizedListing sized : all) {
            if (CoreApi.itemGate().isLocked(player, sized.listing().stack().getItem())) {
                continue;
            }
            bytes += sized.bytes() + 64;
            if (bytes > LISTINGS_BUDGET_BYTES) {
                more = true;
                break;
            }
            lines.add(listingLine(player, sized.listing(), expiry, today));
        }
        PacketDistributor.sendToPlayer(player, new ExchangePayloads.ExchangeListings(lines, more));
    }

    private static ExchangePayloads.ListingLine listingLine(ServerPlayer viewer, ExchangeData.Listing l, int expiry, long today) {
        String seller = name(viewer.server, l.owner()).orElse("?");
        return new ExchangePayloads.ListingLine(l.id(), l.stack().copy(), l.price(), l.quantity(), l.sold(), seller,
                l.owner().equals(viewer.getUUID()), expiry > 0 ? Math.max(0, l.postedDay() + expiry - today) : -1);
    }

    /** A player's name: online, or as the server last saw them. */
    private static Optional<String> name(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return Optional.of(online.getGameProfile().getName());
        }
        return server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(id).map(GameProfile::getName);
    }

    /** The exchange as {@code player} sees it. */
    public static ExchangePayloads.Exchange exchangeFor(ServerPlayer player) {
        ExchangeData data = ExchangeData.get(player.server);
        int expiry = ExchangeSettings.orderExpiryDays();
        long today = ExchangeService.today(player.server);
        List<ExchangePayloads.OrderLine> orders = ExchangeService.ordersOf(player.getUUID(), data).stream()
                .map(o -> new ExchangePayloads.OrderLine(o.id(), o.item(), o.buy(), o.price(), o.quantity(), o.filled(),
                        expiry > 0 ? Math.max(0, o.postedDay() + expiry - today) : -1, o.heldMoney()))
                .toList();
        List<ExchangePayloads.ListingLine> listings = ExchangeService.listingsOf(player.getUUID(), data).stream()
                .map(l -> listingLine(player, l, expiry, today))
                .toList();
        List<ExchangePayloads.BoxLine> box = data.box(player.getUUID()).entrySet().stream()
                .map(e -> new ExchangePayloads.BoxLine(e.getKey(), e.getValue()))
                .toList();
        List<ExchangePayloads.ParcelLine> parcels = data.parcels(player.getUUID()).stream()
                .map(p -> new ExchangePayloads.ParcelLine(p.id(), p.stack().copy(), p.count()))
                .toList();
        return new ExchangePayloads.Exchange(ExchangeService.summaries(player, data), orders, listings, box, parcels,
                ExchangeSettings.maxOpenOrders(), ExchangeSettings.maxOrderSize(), ExchangeSettings.exchangeTax(), expiry,
                ExchangeSettings.listingFee());
    }
}
