package variableeconomy.exchange.network;

import variableeconomy.exchange.ExchangeSettings;
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
    /** The most bytes of offers sent at once, newest first; the rest are left out and the screen says so. */
    private static final int BROWSE_BUDGET_BYTES = 512 * 1024;
    /** The most bytes of history sent at once, newest first. */
    private static final int HISTORY_BUDGET_BYTES = 256 * 1024;

    private static final Map<UUID, ExchangePayloads.ExchangeState> lastSentState = new HashMap<>();
    /** Each viewer's main inventory as last sent to them (see {@link #syncInventory}). */
    private static final Map<UUID, List<ItemStack>> lastSentItems = new HashMap<>();
    /** The item whose book each viewer last asked for, resent to them whenever the exchange changes. */
    private static final Map<UUID, ResourceLocation> watchedBooks = new HashMap<>();
    /** Viewers on the Browse tab, who get every offer again whenever the exchange changes. */
    private static final Set<UUID> watchingBrowse = new HashSet<>();
    /** Viewers looking at their history, who get it again whenever the exchange changes. */
    private static final Set<UUID> watchingHistory = new HashSet<>();

    private ExchangeSync() {
    }

    public static List<ServerPlayer> viewers(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream()
                .filter(p -> p.containerMenu instanceof ExchangeMenu)
                .toList();
    }

    /**
     * Whether {@code player} has the exchange open, so their request may be handled. A dead player's isn't: vanilla
     * keeps the screen open through death, until they respawn.
     */
    private static boolean viewing(ServerPlayer player) {
        return player.containerMenu instanceof ExchangeMenu menu && menu.stillValid(player)
                && player.isAlive() && !player.isRemoved();
    }

    /** Call right after the exchange screen opens. */
    public static void sendFullState(ServerPlayer player) {
        // A newly opened screen starts empty, so resend even if nothing changed since the last send.
        lastSentState.remove(player.getUUID());
        lastSentItems.remove(player.getUUID());
        watchedBooks.remove(player.getUUID());
        watchingBrowse.remove(player.getUUID());
        watchingHistory.remove(player.getUUID());
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
        watchingBrowse.retainAll(open);
        watchingHistory.retainAll(open);
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
                sendBrowse(viewer);
                sendHistory(viewer);
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
        watch(watchingBrowse, player, request.browse());
        watch(watchingHistory, player, request.history());
        sendExchange(player);
        sendBook(player);
        sendBrowse(player);
        sendHistory(player);
    }

    private static void watch(Set<UUID> watching, ServerPlayer player, boolean on) {
        if (on) {
            watching.add(player.getUUID());
        } else {
            watching.remove(player.getUUID());
        }
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
            // The screen sends the price the player saw; 0 fills at whatever the order's price is.
            case FILL -> request.price() > 0
                    ? ExchangeService.fill(player, request.orderId(), request.quantity(), request.price())
                    : ExchangeService.fill(player, request.orderId(), request.quantity());
            case REPRICE -> ExchangeService.reprice(player, request.orderId(), request.price());
            case REDUCE -> ExchangeService.reduce(player, request.orderId(), request.quantity());
            // Listing comes as its own packet, with the item the player saw.
            case LIST -> new ExchangeService.Outcome(ExchangeService.Action.LIST, ExchangeResult.NOT_LISTED,
                    BuiltInRegistries.ITEM.getDefaultKey(), 0, 0, 0, 0);
        };
        replyExchange(player, outcome);
    }

    /** Tells the player how an exchange request went, and refreshes whoever's screen it changed. */
    private static void replyExchange(ServerPlayer player, ExchangeService.Outcome outcome) {
        PacketDistributor.sendToPlayer(player, new ExchangePayloads.ExchangeOutcome(outcome.action(), outcome.item(), outcome.result(),
                outcome.quantity(), outcome.filled(), outcome.amount(), outcome.orderId(), outcome.listed()));
        if (outcome.ok() && outcome.action() != ExchangeService.Action.COLLECT) {
            broadcastExchange(player.server);
        } else {
            sendExchange(player);
            sendBook(player);
        }
        sendState(player);
    }

    // ---- Sending ---------------------------------------------------------------------------------------------

    /** After the exchange changed: everyone with it open gets it, the book they're looking at, and what else they watch. */
    public static void broadcastExchange(MinecraftServer server) {
        // Measured and sorted once for every viewer, only if someone is browsing.
        List<SizedOffer> offers = null;
        for (ServerPlayer player : viewers(server)) {
            sendExchange(player);
            sendBook(player);
            if (watchingBrowse.contains(player.getUUID())) {
                if (offers == null) {
                    offers = sizedOffers(server);
                }
                sendBrowse(player, offers);
            }
            sendHistory(player);
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

    /**
     * One offer for sale, a sell order or a listing, with what it's posted by, when, at what price, and how many bytes
     * its item takes to send.
     */
    private record SizedOffer(long id, boolean listing, ItemStack stack, long price, int remaining, UUID owner, long postedDay, int bytes) {
    }

    /** Every sell order and listing, newest first, measured. */
    private static List<SizedOffer> sizedOffers(MinecraftServer server) {
        ExchangeData data = ExchangeData.get(server);
        List<SizedOffer> offers = new ArrayList<>();
        for (ExchangeData.Order o : data.orders()) {
            if (!o.buy()) {
                BuiltInRegistries.ITEM.getOptional(o.item()).ifPresent(item -> offers.add(new SizedOffer(o.id(), false, new ItemStack(item),
                        o.price(), o.remaining(), o.owner(), o.postedDay(), 48)));
            }
        }
        for (ExchangeData.Listing l : data.listings()) {
            offers.add(new SizedOffer(l.id(), true, l.stack(), l.price(), l.remaining(), l.owner(), l.postedDay(),
                    ExchangeService.encodedSize(l.stack(), server.registryAccess())));
        }
        // IDs never repeat and grow, so the highest is the newest.
        offers.sort(java.util.Comparator.comparingLong(SizedOffer::id).reversed());
        return offers;
    }

    /** Every offer the player may see, if they're browsing, up to {@link #BROWSE_BUDGET_BYTES}. */
    private static void sendBrowse(ServerPlayer player) {
        if (watchingBrowse.contains(player.getUUID())) {
            sendBrowse(player, sizedOffers(player.server));
        }
    }

    /** The offers of {@code all} the player may see, up to {@link #BROWSE_BUDGET_BYTES}. */
    private static void sendBrowse(ServerPlayer player, List<SizedOffer> all) {
        int expiry = ExchangeSettings.orderExpiryDays();
        long today = ExchangeService.today(player.server);
        List<ExchangePayloads.OfferLine> lines = new ArrayList<>();
        int bytes = 0;
        boolean more = false;
        for (SizedOffer offer : all) {
            if (CoreApi.itemGate().isLocked(player, offer.stack().getItem())) {
                continue;
            }
            bytes += offer.bytes() + 64;
            if (bytes > BROWSE_BUDGET_BYTES) {
                more = true;
                break;
            }
            lines.add(new ExchangePayloads.OfferLine(offer.listing(), offer.id(), offer.stack().copy(), offer.price(), offer.remaining(),
                    ExchangeService.name(player.server, offer.owner()).orElse("?"), offer.owner().equals(player.getUUID()),
                    daysLeft(offer.postedDay(), expiry, today), Math.max(0, today - offer.postedDay())));
        }
        PacketDistributor.sendToPlayer(player, new ExchangePayloads.ExchangeBrowse(lines, more));
    }

    /** The player's history, newest first, if they're looking at it, up to {@link #HISTORY_BUDGET_BYTES}. */
    private static void sendHistory(ServerPlayer player) {
        if (!watchingHistory.contains(player.getUUID())) {
            return;
        }
        List<ExchangeData.HistoryEntry> entries = ExchangeData.get(player.server).history(player.getUUID());
        List<ExchangePayloads.HistoryLine> lines = new ArrayList<>();
        int bytes = 0;
        boolean more = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            ExchangeData.HistoryEntry e = entries.get(i);
            bytes += (e.item().isComponentsPatchEmpty() ? 16 : ExchangeService.encodedSize(e.item(), player.server.registryAccess())) + 64;
            if (bytes > HISTORY_BUDGET_BYTES) {
                more = true;
                break;
            }
            String counterparty = e.counterparty().flatMap(id -> ExchangeService.name(player.server, id)).orElse("");
            lines.add(new ExchangePayloads.HistoryLine(e.kind(), e.side(), e.orderId(), e.item().copy(), e.units(), e.price(), e.amount(),
                    e.tax(), counterparty, e.time()));
        }
        PacketDistributor.sendToPlayer(player, new ExchangePayloads.ExchangeHistory(lines, System.currentTimeMillis(), more));
    }

    /** Days until a post expires, or -1 when posts don't. */
    private static long daysLeft(long postedDay, int expiry, long today) {
        return expiry > 0 ? Math.max(0, postedDay + expiry - today) : -1;
    }

    private static ExchangePayloads.ListingLine listingLine(ExchangeData.Listing l, int expiry, long today) {
        return new ExchangePayloads.ListingLine(l.id(), l.stack().copy(), l.price(), l.quantity(), l.sold(), daysLeft(l.postedDay(), expiry, today),
                Math.max(0, today - l.postedDay()));
    }

    /** The exchange as {@code player} sees it. */
    public static ExchangePayloads.Exchange exchangeFor(ServerPlayer player) {
        ExchangeData data = ExchangeData.get(player.server);
        int expiry = ExchangeSettings.orderExpiryDays();
        long today = ExchangeService.today(player.server);
        List<ExchangePayloads.OrderLine> orders = ExchangeService.ordersOf(player.getUUID(), data).stream()
                .map(o -> new ExchangePayloads.OrderLine(o.id(), o.item(), o.buy(), o.price(), o.quantity(), o.filled(),
                        daysLeft(o.postedDay(), expiry, today), o.heldMoney(), Math.max(0, today - o.postedDay()), o.traded()))
                .toList();
        List<ExchangePayloads.ListingLine> listings = ExchangeService.listingsOf(player.getUUID(), data).stream()
                .map(l -> listingLine(l, expiry, today))
                .toList();
        List<ExchangePayloads.BoxLine> box = data.box(player.getUUID()).entrySet().stream()
                .map(e -> new ExchangePayloads.BoxLine(e.getKey(), e.getValue()))
                .toList();
        List<ExchangePayloads.ParcelLine> parcels = data.parcels(player.getUUID()).stream()
                .map(p -> new ExchangePayloads.ParcelLine(p.id(), p.stack().copy(), p.count()))
                .toList();
        return new ExchangePayloads.Exchange(ExchangeService.summaries(player, data), orders, listings, box, parcels,
                ExchangeSettings.maxOpenOrders(), ExchangeSettings.maxOrderSize(), ExchangeSettings.exchangeTax(), expiry,
                ExchangeSettings.listingFee(),
                new ExchangePayloads.Categories(ExchangeSettings.categories(), ExchangeSettings.creativeTabFallback()));
    }
}
