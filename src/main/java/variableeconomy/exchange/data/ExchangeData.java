package variableeconomy.exchange.data;

import variableeconomy.exchange.ExchangeModule;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The player exchange, saved with the world: open orders and listings, each player's collection box,
 * or still to be told. All of it is one saved file, so a restart never splits a trade between them. Only
 * {@code ExchangeService} changes it. Server thread only.
 * <p>
 * What an order holds: a buy order holds {@code price × remaining} in money, taken when it was posted; a sell order
 * holds its remaining items, taken from the seller's inventory when it was posted. A listing holds its remaining
 * units of one exact item (enchanted, damaged, renamed and so on), which buyers take at its price.
 */
public final class ExchangeData extends SavedData {
    public static final String ID = "variable_economy_exchange";
    private static final SavedData.Factory<ExchangeData> FACTORY = new SavedData.Factory<>(ExchangeData::new, ExchangeData::fromTag, null);
    /** Best price first, then oldest first: the order in which a side of the book fills. */
    private static final Comparator<Order> ASKS = Comparator.comparingLong(Order::price).thenComparingLong(Order::id);
    private static final Comparator<Order> BIDS = Comparator.comparingLong(Order::price).reversed().thenComparingLong(Order::id);

    /**
     * One open order.
     *
     * @param account   the account a buy order's money came from, which money goes back to while it exists; for a sell
     *                  order, the account its proceeds go to
     * @param price     per unit, in bits
     * @param quantity  units ordered; {@code filled} of them have traded
     * @param traded    what the units traded so far came to, before tax: a buy order's spending (some units may have
     *                  cost less than its price), a sell order's takings. A buy order's tax is worked out on its whole
     *                  spending, so buying in pieces costs no more.
     * @param held      a buy order's money still held: enough for its remaining units at its price and their tax; 0
     *                  for a sell order
     * @param postedDay the exchange's day count when it was posted, for expiry
     */
    public record Order(long id, UUID owner, Optional<UUID> account, ResourceLocation item, boolean buy, long price, int quantity,
                        int filled, long traded, long held, long postedDay) {
        public int remaining() {
            return quantity - filled;
        }

        /** The money a buy order still holds; 0 for a sell order. */
        public long heldMoney() {
            return buy ? held : 0;
        }

        /** After {@code units} more traded for {@code value} in all, and {@code taken} more (value and tax) paid out of what it holds. */
        public Order withFill(int units, long value, long taken) {
            return new Order(id, owner, account, item, buy, price, quantity, filled + units, traded + value, buy ? held - taken : 0, postedDay);
        }

        /** The same order holding {@code money}. */
        public Order withHeld(long money) {
            return new Order(id, owner, account, item, buy, price, quantity, filled, traded, money, postedDay);
        }
    }

    /**
     * One listing: an item with its own components, offered at a fixed price each until it sells out or is cancelled.
     *
     * @param stack     one unit of the item exactly as listed (count 1); never changed, copied on the way out
     * @param account   the account the proceeds go to, while it exists
     * @param price     per unit, in bits
     * @param quantity  units listed; {@code sold} of them have been bought
     * @param postedDay the exchange's day count when it was posted, for expiry
     */
    public record Listing(long id, UUID owner, Optional<UUID> account, ItemStack stack, long price, int quantity, int sold, long postedDay) {
        public int remaining() {
            return quantity - sold;
        }

        public ResourceLocation item() {
            return BuiltInRegistries.ITEM.getKey(stack.getItem());
        }

        public Listing withSold(int total) {
            return new Listing(id, owner, account, stack, price, quantity, total, postedDay);
        }
    }

    /** Items with their own components waiting in a collection box: {@code count} units like {@code stack} (count 1). */
    public record Parcel(long id, ItemStack stack, long count) {
    }

    public enum NoticeKind {
        /** A sell order or listing sold units: {@code amount} is what was paid in. */
        SOLD,
        /** A buy order bought units, now in the collection box: {@code amount} is what they cost, tax included. */
        BOUGHT,
        /** An order expired or was closed: a buy order's money ({@code amount}) or a sell order's items went back. */
        RETURNED,
        /** A listing expired or was closed: its unsold units went back to the collection box. */
        LISTING_RETURNED
    }

    /**
     * Something that happened to a player's order or listing, kept until they next log in. Same-kind notices for an item
     * add up. {@code name} is a listed item's custom name, or empty to use the item's own.
     */
    public record Notice(UUID player, ResourceLocation item, NoticeKind kind, long quantity, long amount, String name) {
        public Notice(UUID player, ResourceLocation item, NoticeKind kind, long quantity, long amount) {
            this(player, item, kind, quantity, amount, "");
        }
    }

    private final List<Order> orders = new ArrayList<>();
    private final List<Listing> listings = new ArrayList<>();
    /** Items waiting for each player, by item, in the order they arrived. */
    private final Map<UUID, Map<ResourceLocation, Long>> boxes = new LinkedHashMap<>();
    /** Items with their own components waiting for each player, in the order they arrived. */
    private final Map<UUID, List<Parcel>> parcels = new LinkedHashMap<>();
    /**
     * Saved listings and parcels whose item can't be read now (its mod was removed). They're kept as saved, untouched,
     * so the items come back if the mod does.
     */
    private final ListTag unreadable = new ListTag();
    private final List<Notice> notices = new ArrayList<>();
    private long nextId = 1;
    private long taxCollected;
    private long volume;
    /** The day count and expiry setting expiry was last checked with; not saved, so a restart checks again. */
    private long expiryCheckedDay = -1;
    /** The day count orders expire by: see {@link #observe}. */
    private long day = -1;
    private long lastWorldDay = -1;
    private int expiryCheckedDays = -1;

    public static ExchangeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    // ---- Orders ----------------------------------------------------------------------------------------------

    public List<Order> orders() {
        return Collections.unmodifiableList(orders);
    }

    public Optional<Order> find(long id) {
        return orders.stream().filter(o -> o.id() == id).findFirst();
    }

    /** Takes the next order ID. IDs never repeat, so they also give time priority. */
    public long takeId() {
        setDirty();
        return nextId++;
    }

    public void add(Order order) {
        orders.add(order);
        setDirty();
    }

    /** Replaces the order with the same ID, or removes it once nothing remains. */
    public void update(Order order) {
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i).id() == order.id()) {
                if (order.remaining() <= 0) {
                    orders.remove(i);
                } else {
                    orders.set(i, order);
                }
                setDirty();
                return;
            }
        }
        throw new IllegalArgumentException("No exchange order " + order.id());
    }

    public void remove(long id) {
        if (orders.removeIf(o -> o.id() == id)) {
            setDirty();
        }
    }

    /** One side of an item's book in fill order: asks cheapest first, bids dearest first; oldest first at a price. */
    public List<Order> side(ResourceLocation item, boolean buy) {
        return orders.stream().filter(o -> o.item().equals(item) && o.buy() == buy).sorted(buy ? BIDS : ASKS).toList();
    }

    /**
     * Whether expiry needs checking on {@code day} with {@code expiryDays}: true the first time for each pair, as an
     * order posted after a check can't be due until the day count moves.
     */
    public boolean expiryCheckDue(long day, int expiryDays) {
        if (day == expiryCheckedDay && expiryDays == expiryCheckedDays) {
            return false;
        }
        expiryCheckedDay = day;
        expiryCheckedDays = expiryDays;
        return true;
    }

    /** The player's open orders and listings together, which {@code max_open_orders} limits. */
    public int openOrders(UUID player) {
        return (int) (orders.stream().filter(o -> o.owner().equals(player)).count()
                + listings.stream().filter(l -> l.owner().equals(player)).count());
    }

    // ---- Listings --------------------------------------------------------------------------------------------

    public List<Listing> listings() {
        return Collections.unmodifiableList(listings);
    }

    public Optional<Listing> findListing(long id) {
        return listings.stream().filter(l -> l.id() == id).findFirst();
    }

    public void add(Listing listing) {
        listings.add(listing);
        setDirty();
    }

    /** Replaces the listing with the same ID, or removes it once nothing remains. */
    public void update(Listing listing) {
        for (int i = 0; i < listings.size(); i++) {
            if (listings.get(i).id() == listing.id()) {
                if (listing.remaining() <= 0) {
                    listings.remove(i);
                } else {
                    listings.set(i, listing);
                }
                setDirty();
                return;
            }
        }
        throw new IllegalArgumentException("No exchange listing " + listing.id());
    }

    public void removeListing(long id) {
        if (listings.removeIf(l -> l.id() == id)) {
            setDirty();
        }
    }

    // ---- Collection boxes ------------------------------------------------------------------------------------

    /** What waits in the player's box, by item. */
    public Map<ResourceLocation, Long> box(UUID player) {
        return Collections.unmodifiableMap(boxes.getOrDefault(player, Map.of()));
    }

    /** A copy of every player's box, by item. */
    public Map<UUID, Map<ResourceLocation, Long>> boxes() {
        Map<UUID, Map<ResourceLocation, Long>> copy = new LinkedHashMap<>();
        boxes.forEach((player, box) -> copy.put(player, Collections.unmodifiableMap(new LinkedHashMap<>(box))));
        return Collections.unmodifiableMap(copy);
    }

    public void addToBox(UUID player, ResourceLocation item, long count) {
        if (count <= 0) {
            return;
        }
        boxes.computeIfAbsent(player, p -> new LinkedHashMap<>()).merge(item, count, Long::sum);
        setDirty();
    }

    /** Takes {@code count} of an item out of the player's box; the caller has checked there are that many. */
    public void takeFromBox(UUID player, ResourceLocation item, long count) {
        Map<ResourceLocation, Long> box = boxes.get(player);
        long have = box == null ? 0 : box.getOrDefault(item, 0L);
        if (count > have) {
            throw new IllegalArgumentException("Box holds " + have + " of " + item + ", not " + count);
        }
        if (count == have) {
            box.remove(item);
            if (box.isEmpty()) {
                boxes.remove(player);
            }
        } else {
            box.put(item, have - count);
        }
        setDirty();
    }

    /** Items with their own components waiting in the player's box, in the order they arrived. */
    public List<Parcel> parcels(UUID player) {
        return Collections.unmodifiableList(parcels.getOrDefault(player, List.of()));
    }

    public Map<UUID, List<Parcel>> allParcels() {
        return Collections.unmodifiableMap(parcels);
    }

    /** Whether anything at all waits in the player's box. */
    public boolean hasBox(UUID player) {
        return boxes.containsKey(player) || parcels.containsKey(player);
    }

    /** Adds {@code count} units like {@code stack} to the player's box, joining a parcel of exactly the same item. */
    public void addParcel(UUID player, ItemStack stack, long count) {
        if (count <= 0 || stack.isEmpty()) {
            return;
        }
        List<Parcel> theirs = parcels.computeIfAbsent(player, p -> new ArrayList<>());
        for (int i = 0; i < theirs.size(); i++) {
            Parcel parcel = theirs.get(i);
            if (ItemStack.isSameItemSameComponents(parcel.stack(), stack)) {
                theirs.set(i, new Parcel(parcel.id(), parcel.stack(), parcel.count() + count));
                setDirty();
                return;
            }
        }
        theirs.add(new Parcel(takeId(), stack.copyWithCount(1), count));
        setDirty();
    }

    /** Takes {@code count} units out of one of the player's parcels; the caller has checked there are that many. */
    public void takeParcel(UUID player, long id, long count) {
        List<Parcel> theirs = parcels.getOrDefault(player, List.of());
        for (int i = 0; i < theirs.size(); i++) {
            Parcel parcel = theirs.get(i);
            if (parcel.id() != id) {
                continue;
            }
            if (count > parcel.count()) {
                throw new IllegalArgumentException("Parcel " + id + " holds " + parcel.count() + ", not " + count);
            }
            if (count == parcel.count()) {
                theirs.remove(i);
                if (theirs.isEmpty()) {
                    parcels.remove(player);
                }
            } else {
                theirs.set(i, new Parcel(parcel.id(), parcel.stack(), parcel.count() - count));
            }
            setDirty();
            return;
        }
        throw new IllegalArgumentException("No parcel " + id + " in the box of " + player);
    }

    // ---- The day count ---------------------------------------------------------------------------------------

    /** The overworld's day number: what the debug screen calls the day. */
    public static long worldDay(MinecraftServer server) {
        return server.overworld().getDayTime() / 24000L;
    }

    /**
     * Advances the day count by every day boundary crossed forwards since the last call: the overworld's day number as
     * first seen, then moved on by sleeping or time passing, but never back by setting the time backwards.
     */
    public void observe(long worldDay) {
        if (lastWorldDay < 0 || day < 0) {
            day = Math.max(0, worldDay);
        } else if (worldDay > lastWorldDay) {
            day += worldDay - lastWorldDay;
        }
        if (worldDay != lastWorldDay) {
            lastWorldDay = worldDay;
            setDirty();
        }
    }

    /** The day count orders expire by. */
    public long day() {
        return Math.max(0, day);
    }

    // ---- Notices -----------------------------------------------------------------------------------------------

    /**
     * Keeps a notice for an offline player, adding it to one of the same kind for the same item. A buy order's
     * returned money (no units) and a sell order's returned items stay apart, as they read differently.
     */
    public void addNotice(Notice notice) {
        for (int i = 0; i < notices.size(); i++) {
            Notice n = notices.get(i);
            if (n.player().equals(notice.player()) && n.item().equals(notice.item()) && n.kind() == notice.kind()
                    && n.name().equals(notice.name()) && (n.quantity() == 0) == (notice.quantity() == 0)) {
                notices.set(i, new Notice(n.player(), n.item(), n.kind(), n.quantity() + notice.quantity(), n.amount() + notice.amount(),
                        n.name()));
                setDirty();
                return;
            }
        }
        notices.add(notice);
        setDirty();
    }

    /** Removes and returns the player's notices. */
    public List<Notice> takeNotices(UUID player) {
        List<Notice> theirs = notices.stream().filter(n -> n.player().equals(player)).toList();
        if (!theirs.isEmpty()) {
            notices.removeAll(theirs);
            setDirty();
        }
        return theirs;
    }

    // ---- Money counts ----------------------------------------------------------------------------------------

    /** Adds one trade to the counts: its value, and the tax taken from it (money destroyed). */
    public void count(long value, long tax) {
        volume += value;
        taxCollected += tax;
        setDirty();
    }

    public long taxCollected() {
        return taxCollected;
    }

    public long volume() {
        return volume;
    }

    // ---- Saving ----------------------------------------------------------------------------------------------

    public static ExchangeData fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        ExchangeData data = new ExchangeData();
        data.day = tag.contains("day") ? tag.getLong("day") : -1;
        data.lastWorldDay = tag.contains("world_day") ? tag.getLong("world_day") : -1;
        data.nextId = Math.max(1, tag.getLong("next_id"));
        data.taxCollected = tag.getLong("tax_collected");
        data.volume = tag.getLong("volume");
        for (Tag element : tag.getList("orders", Tag.TAG_COMPOUND)) {
            CompoundTag o = (CompoundTag) element;
            ResourceLocation item = ResourceLocation.tryParse(o.getString("item"));
            if (item != null && o.hasUUID("owner")) {
                boolean buy = o.getBoolean("buy");
                long price = o.getLong("price");
                int quantity = o.getInt("quantity");
                int filled = o.getInt("filled");
                // Version 1 named the account "team", kept a sell order's takings as "sold_value", and held a buy order's
                // price × remaining units with no tax, since the seller paid it then.
                long traded = o.contains("traded") ? o.getLong("traded")
                        : !buy && o.contains("sold_value") ? o.getLong("sold_value") : price * filled;
                long held = o.contains("held") ? o.getLong("held") : buy ? price * (quantity - filled) : 0;
                data.orders.add(new Order(o.getLong("id"), o.getUUID("owner"), account(o), item, buy, price, quantity, filled, traded, held,
                        o.getLong("posted_day")));
            }
        }
        for (Tag element : tag.getList("listings", Tag.TAG_COMPOUND)) {
            readListing(data, (CompoundTag) element, registries);
        }
        for (Tag element : tag.getList("parcels", Tag.TAG_COMPOUND)) {
            readParcel(data, (CompoundTag) element, registries);
        }
        // Kept from an earlier load because their item couldn't be read; tried again in case the mod is back.
        for (Tag element : tag.getList("unreadable", Tag.TAG_COMPOUND)) {
            CompoundTag u = (CompoundTag) element;
            if ("listing".equals(u.getString("kind"))) {
                readListing(data, u, registries);
            } else {
                readParcel(data, u, registries);
            }
        }
        for (Tag element : tag.getList("boxes", Tag.TAG_COMPOUND)) {
            CompoundTag b = (CompoundTag) element;
            ResourceLocation item = ResourceLocation.tryParse(b.getString("item"));
            if (item != null && b.hasUUID("player")) {
                data.boxes.computeIfAbsent(b.getUUID("player"), p -> new LinkedHashMap<>()).merge(item, b.getLong("count"), Long::sum);
            }
        }
        for (Tag element : tag.getList("notices", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) element;
            ResourceLocation item = ResourceLocation.tryParse(n.getString("item"));
            NoticeKind kind;
            try {
                kind = NoticeKind.valueOf(n.getString("kind").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (item != null && n.hasUUID("player")) {
                data.notices.add(new Notice(n.getUUID("player"), item, kind, n.getLong("quantity"), n.getLong("amount"), n.getString("name")));
            }
        }
        return data;
    }

    private static void readListing(ExchangeData data, CompoundTag l, HolderLookup.Provider registries) {
        Optional<ItemStack> stack = ItemStack.parse(registries, l.getCompound("stack"));
        if (stack.isEmpty() || !l.hasUUID("owner")) {
            keepUnreadable(data, l, "listing");
            return;
        }
        data.listings.add(new Listing(l.getLong("id"), l.getUUID("owner"), account(l),
                stack.get().copyWithCount(1), l.getLong("price"), l.getInt("quantity"), l.getInt("sold"), l.getLong("posted_day")));
    }

    /** The paying account, which version 1 called "team". */
    private static Optional<UUID> account(CompoundTag tag) {
        return tag.hasUUID("account") ? Optional.of(tag.getUUID("account")) : tag.hasUUID("team") ? Optional.of(tag.getUUID("team")) : Optional.empty();
    }

    private static void readParcel(ExchangeData data, CompoundTag p, HolderLookup.Provider registries) {
        Optional<ItemStack> stack = ItemStack.parse(registries, p.getCompound("stack"));
        if (stack.isEmpty() || !p.hasUUID("player")) {
            keepUnreadable(data, p, "parcel");
            return;
        }
        data.parcels.computeIfAbsent(p.getUUID("player"), id -> new ArrayList<>())
                .add(new Parcel(p.getLong("id"), stack.get().copyWithCount(1), p.getLong("count")));
    }

    private static void keepUnreadable(ExchangeData data, CompoundTag tag, String kind) {
        CompoundTag kept = tag.copy();
        kept.putString("kind", kind);
        data.unreadable.add(kept);
        ExchangeModule.LOGGER.warn("Exchange {} {} can't be read (was its mod removed?); it's kept as saved", kind, tag.getLong("id"));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 2);
        tag.putLong("day", day);
        tag.putLong("world_day", lastWorldDay);
        tag.putLong("next_id", nextId);
        tag.putLong("tax_collected", taxCollected);
        tag.putLong("volume", volume);
        ListTag orderList = new ListTag();
        for (Order order : orders) {
            CompoundTag o = new CompoundTag();
            o.putLong("id", order.id());
            o.putUUID("owner", order.owner());
            order.account().ifPresent(id -> o.putUUID("account", id));
            o.putString("item", order.item().toString());
            o.putBoolean("buy", order.buy());
            o.putLong("price", order.price());
            o.putInt("quantity", order.quantity());
            o.putInt("filled", order.filled());
            o.putLong("traded", order.traded());
            o.putLong("held", order.held());
            o.putLong("posted_day", order.postedDay());
            orderList.add(o);
        }
        tag.put("orders", orderList);
        ListTag listingList = new ListTag();
        for (Listing listing : listings) {
            CompoundTag l = new CompoundTag();
            l.putLong("id", listing.id());
            l.putUUID("owner", listing.owner());
            listing.account().ifPresent(id -> l.putUUID("account", id));
            l.put("stack", listing.stack().save(registries));
            l.putLong("price", listing.price());
            l.putInt("quantity", listing.quantity());
            l.putInt("sold", listing.sold());
            l.putLong("posted_day", listing.postedDay());
            listingList.add(l);
        }
        tag.put("listings", listingList);
        ListTag parcelList = new ListTag();
        parcels.forEach((player, theirs) -> theirs.forEach(parcel -> {
            CompoundTag p = new CompoundTag();
            p.putUUID("player", player);
            p.putLong("id", parcel.id());
            p.put("stack", parcel.stack().save(registries));
            p.putLong("count", parcel.count());
            parcelList.add(p);
        }));
        tag.put("parcels", parcelList);
        tag.put("unreadable", unreadable.copy());
        ListTag boxList = new ListTag();
        boxes.forEach((player, box) -> box.forEach((item, count) -> {
            CompoundTag b = new CompoundTag();
            b.putUUID("player", player);
            b.putString("item", item.toString());
            b.putLong("count", count);
            boxList.add(b);
        }));
        tag.put("boxes", boxList);
        ListTag noticeList = new ListTag();
        for (Notice notice : notices) {
            CompoundTag n = new CompoundTag();
            n.putUUID("player", notice.player());
            n.putString("item", notice.item().toString());
            n.putString("kind", notice.kind().name().toLowerCase(Locale.ROOT));
            n.putLong("quantity", notice.quantity());
            n.putLong("amount", notice.amount());
            if (!notice.name().isEmpty()) {
                n.putString("name", notice.name());
            }
            noticeList.add(n);
        }
        tag.put("notices", noticeList);
        return tag;
    }
}
