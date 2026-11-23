package variableeconomy.exchange.client;

import variableeconomy.exchange.network.ExchangePayloads;
import variableeconomy.exchange.service.ExchangeService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static variableeconomy.exchange.client.ExchangeScreen.*;

/**
 * The exchange screen's content: trading with other players. Its list switches between items and their order books,
 * other players' listings, the player's own items that can be listed, their open orders and listings, and their
 * collection box. The panel shows the selected item's book and the order controls, or the selected listing or order.
 */
final class ExchangeView {
    private final ExchangeScreen screen;
    /**
     * What the list shows: items and their books, other players' listings, the player's own items that
     * can be listed, their open orders and listings, or their collection box.
     */
    private enum ExchangeMode { ITEMS, LISTINGS, LIST, ORDERS, BOX }

    private ExchangeMode exchangeMode = ExchangeMode.ITEMS;
    /** Cycles the list between items, listings, listing an item, the player's orders and their collection box. */
    private Button exchangeModeButton;
    /** The price each for a new order or listing. */
    private EditBox price;
    /** Post a buy or sell order, in the item view. */
    private Button postBuyButton;
    private Button postSellButton;
    /** Buy from a listing, list an item, cancel the selected order, or take everything out of the box. */
    private Button exchangeActionButton;
    /** The item selected in the item view. */
    @Nullable
    private ResourceLocation exchangeSelected;
    /** The selected order or listing under My orders; IDs never repeat across the two. */
    @Nullable
    private Long selectedOrder;
    /** The order picked in the selected item's book, which the Buy or Sell button then fills; null when none. */
    @Nullable
    private Long selectedBookOrder;
    /** The listing selected while browsing listings. */
    @Nullable
    private Long selectedListing;
    /** The main-inventory slot picked to list, and the item it held then; cleared if it no longer does. */
    private int listSlot = -1;
    private ItemStack listStack = ItemStack.EMPTY;
    /** The listings shown, filtered by the search. */
    private final List<ExchangePayloads.ListingLine> browseLines = new ArrayList<>();

    /** How browsed listings are ordered: by item (cheapest first within each), or across items by price, age or time left. */
    enum ListingSort {
        ITEM(null),
        CHEAPEST(java.util.Comparator.comparingLong(ExchangePayloads.ListingLine::price)),
        DEAREST(java.util.Comparator.comparingLong(ExchangePayloads.ListingLine::price).reversed()),
        NEWEST(java.util.Comparator.comparingLong(ExchangePayloads.ListingLine::id).reversed()),
        ENDING(java.util.Comparator.comparingLong((ExchangePayloads.ListingLine l) -> l.daysLeft() < 0 ? Long.MAX_VALUE : l.daysLeft()));

        /** Null keeps the server's order: by item, then cheapest, then oldest. Ties fall back to it too. */
        @Nullable
        final java.util.Comparator<ExchangePayloads.ListingLine> order;

        ListingSort(@Nullable java.util.Comparator<ExchangePayloads.ListingLine> order) {
            this.order = order;
        }

        ListingSort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private ListingSort listingSort = ListingSort.ITEM;
    /** Cycles the listing sort; shown in place of the price box while browsing listings. */
    private Button listingSortButton;
    /** The item list: items with orders and items held, or with a search, every item that matches. */
    private final List<ResourceLocation> exchangeItems = new ArrayList<>();
    /** Where each price of the shown book is drawn, so a click can copy it into the price box. */
    private final List<BookRow> bookRows = new ArrayList<>();

    /** The most items a search of every item lists, so a one-letter search doesn't list the whole game. */
    private static final int MAX_SEARCH_RESULTS = 300;
    /** The largest order until the server says otherwise: its default. */
    private static final int DEFAULT_MAX_ORDER = 6400;

    ExchangeView(ExchangeScreen screen) {
        this.screen = screen;
    }

    /**
     * The view button beside the search box ({@code sortX} across), the price box above the quantity (beside it when
     * narrow), and the action buttons.
     */
    void init(int x, int y, int sortX) {
        int[] first = screen.buttonBounds(true);
        int[] second = screen.buttonBounds(false);
        String priceText = price != null ? price.getValue() : "";
        exchangeModeButton = screen.add(Button.builder(Component.empty(), b -> cycleExchangeMode())
                .bounds(x + sortX, y + TOOLBAR_Y, 102, 16)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.exchange.mode.tooltip")))
                .build());
        price = new EditBox(screen.font(), x + screen.priceX, y + screen.priceY, screen.priceWidth, 14, Component.translatable("screen.variable_economy_exchange.exchange.price"));
        price.setHint(Component.translatable("screen.variable_economy_exchange.exchange.price_hint").withColor(TEXT_DIM));
        price.setFilter(s -> s.isEmpty() || (s.length() <= 10 && s.chars().allMatch(Character::isDigit)));
        price.setValue(priceText);
        screen.add(price);
        postBuyButton = screen.add(Button.builder(Component.empty(), b -> postOrder(true))
                .bounds(first[0], first[1], first[2], 18).build());
        postSellButton = screen.add(Button.builder(Component.empty(), b -> postOrder(false))
                .bounds(second[0], second[1], second[2], 18).build());
        exchangeActionButton = screen.add(Button.builder(Component.empty(), b -> exchangeAction())
                .bounds(first[0], first[1], first[2], 18).build());
        listingSortButton = screen.add(Button.builder(Component.empty(), b -> {
                    listingSort = listingSort.next();
                    screen.listDirty = true;
                })
                .bounds(x + screen.sortX, y + screen.sortY, screen.sortWidth, 16)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.exchange.sort.tooltip")))
                .build());
        // Asks for the exchange as the screen opens, and again after a resize.
        opened();
    }

    void updateWidgets() {
        if (price != null) {
            boolean itemsShown = exchangeMode == ExchangeMode.ITEMS;
            price.visible = showsPrice();
            postBuyButton.visible = itemsShown;
            postSellButton.visible = itemsShown;
            exchangeActionButton.visible = !itemsShown;
            exchangeModeButton.setMessage(exchangeModeLabel());
            listingSortButton.visible = exchangeMode == ExchangeMode.LISTINGS;
            listingSortButton.setMessage(Component.translatable("screen.variable_economy_exchange.exchange.sort." + listingSort.name().toLowerCase(Locale.ROOT)));
        }
    }

    /** Whether the price box shows: for a new order or a new listing. */
    private boolean showsPrice() {
        return exchangeMode == ExchangeMode.ITEMS || exchangeMode == ExchangeMode.LIST;
    }

    boolean hides(GuiEventListener focused) {
        return focused == price && !showsPrice();
    }

    boolean showsSearch() {
        return exchangeMode == ExchangeMode.ITEMS || exchangeMode == ExchangeMode.LISTINGS;
    }

    boolean showsQuantity() {
        return exchangeMode != ExchangeMode.ORDERS && exchangeMode != ExchangeMode.BOX;
    }

    /** Asks the server for the exchange, the selected item's book, and the listings while they're browsed. */
    private void opened() {
        PacketDistributor.sendToServer(new ExchangePayloads.ExchangeRequest(Optional.ofNullable(exchangeSelected), exchangeMode == ExchangeMode.LISTINGS));
    }

    /** The largest quantity one request may ask for: one stack for a listing, otherwise the largest order. */
    int quantityLimit() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        if (exchangeMode == ExchangeMode.LIST && listSlot >= 0) {
            return listStack.getMaxStackSize();
        }
        return exchange != null ? exchange.maxOrderSize() : DEFAULT_MAX_ORDER;
    }

    /** "All": every copy held, up to what the view allows. */
    void fillAll() {
        fillExchangeAll();
    }

    int listSize() {
        return switch (exchangeMode) {
            case ITEMS -> exchangeItems.size();
            case LISTINGS -> browseLines.size();
            case LIST -> listableSlots().size();
            case ORDERS -> orderLines().size() + myListings().size();
            case BOX -> boxLines().size() + parcelLines().size();
        };
    }

    boolean rowClicked(int index) {
        screen.minecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        int orders = orderLines().size();
        int boxed = boxLines().size();
        switch (exchangeMode) {
            case ITEMS -> selectExchangeItem(exchangeItems.get(index));
            case LISTINGS -> {
                long id = browseLines.get(index).id();
                if (!Long.valueOf(id).equals(selectedListing)) {
                    selectedListing = id;
                    screen.setQuantity(1, Component.empty());
                }
            }
            case LIST -> {
                int slot = listableSlots().get(index);
                if (slot != listSlot) {
                    listSlot = slot;
                    listStack = slotStack(slot).copyWithCount(1);
                    screen.setQuantity(Math.min(heldLike(listStack), listStack.getMaxStackSize()), Component.empty());
                }
            }
            case ORDERS -> selectedOrder = index < orders ? orderLines().get(index).id() : myListings().get(index - orders).id();
            // Clicking a box line takes that item out.
            case BOX -> PacketDistributor.sendToServer(index < boxed
                    ? ExchangePayloads.ExchangeAction.collect(Optional.of(boxLines().get(index).item()))
                    : ExchangePayloads.ExchangeAction.collectParcel(parcelLines().get(index - boxed).id()));
        }
        screen.setFocused(null);
        return true;
    }

    boolean panelClicked(double mouseX, double mouseY) {
        BookRow bookRow = bookRowAt(mouseX, mouseY);
        if (bookRow != null) {
            // Clicking an order copies its price; another player's is picked, so Buy or Sell fills it. Clicking it again lets it go.
            screen.minecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            ExchangeService.BookOrder order = bookRow.order();
            if (Long.valueOf(order.id()).equals(selectedBookOrder)) {
                selectedBookOrder = null;
                return true;
            }
            price.setValue(String.valueOf(order.price()));
            if (!order.mine()) {
                selectedBookOrder = order.id();
                int most = bookRow.ask() ? order.quantity() : Math.min(order.quantity(), freshHeld(exchangeSelected));
                screen.setQuantity(Math.max(1, most), Component.empty());
            }
            return true;
        }
        return false;
    }

    void renderTooltip(GuiGraphics graphics, int index, int mouseX, int mouseY) {
        if (index >= 0 && exchangeMode == ExchangeMode.ITEMS) {
            graphics.renderTooltip(screen.font(), exchangeTooltip(exchangeItems.get(index)), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && exchangeMode == ExchangeMode.LISTINGS) {
            graphics.renderTooltip(screen.font(), listingTooltip(browseLines.get(index)), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && exchangeMode == ExchangeMode.LIST) {
            graphics.renderTooltip(screen.font(), net.minecraft.client.gui.screens.Screen.getTooltipFromItem(screen.minecraft(), slotStack(listableSlots().get(index))), Optional.empty(), mouseX, mouseY);
        } else if (index >= orderLines().size() && exchangeMode == ExchangeMode.ORDERS) {
            graphics.renderTooltip(screen.font(), listingTooltip(myListings().get(index - orderLines().size())), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && exchangeMode == ExchangeMode.BOX) {
            List<Component> lines = new ArrayList<>();
            if (index >= boxLines().size()) {
                lines.addAll(net.minecraft.client.gui.screens.Screen.getTooltipFromItem(screen.minecraft(), parcelLines().get(index - boxLines().size()).stack()));
            }
            lines.add(Component.translatable("screen.variable_economy_exchange.exchange.box.click").withColor(TEXT_DIM));
            graphics.renderTooltip(screen.font(), lines, Optional.empty(), mouseX, mouseY);
        } else if (bookRowAt(mouseX, mouseY) != null) {
            BookRow row = bookRowAt(mouseX, mouseY);
            ExchangeService.BookOrder order = row.order();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable(row.ask() ? "screen.variable_economy_exchange.exchange.book.sells" : "screen.variable_economy_exchange.exchange.book.buys",
                    order.mine() ? Component.translatable("screen.variable_economy_exchange.exchange.listing.you").getString() : order.poster(),
                    PriceFormat.count(order.quantity()), PriceFormat.amount(order.price())));
            if (order.daysLeft() >= 0) {
                lines.add(days(order.daysLeft()).withColor(TEXT_DIM));
            }
            lines.add(Component.translatable(order.mine() ? "screen.variable_economy_exchange.exchange.book.click"
                    : row.ask() ? "screen.variable_economy_exchange.exchange.book.click_buy" : "screen.variable_economy_exchange.exchange.book.click_sell")
                    .withColor(TEXT_DIM));
            graphics.renderComponentTooltip(screen.font(), lines, mouseX, mouseY);
        }
    }

    void renderList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        renderExchangeList(graphics, x, y, mouseX, mouseY);
    }

    void renderPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        bookRows.clear();
        renderExchangePanel(graphics, x, y, mouseX, mouseY);
    }

    /**
     * Without a search: items with orders, then items the player holds. With one: every tradeable item that matches,
     * those with orders first. Each group is sorted by name.
     */
    void rebuildList(String query) {
        browseLines.clear();
        ExchangePayloads.ExchangeListings listings = ClientExchangeState.listings();
        if (listings != null) {
            for (ExchangePayloads.ListingLine line : listings.listings()) {
                if (matches(BuiltInRegistries.ITEM.getKey(line.stack().getItem()), query)
                        || line.stack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) {
                    browseLines.add(line);
                }
            }
            if (listingSort.order != null) {
                // A stable sort, so ties keep the server's order.
                browseLines.sort(listingSort.order);
            }
        }
        exchangeItems.clear();
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        List<ResourceLocation> withOrders = new ArrayList<>();
        if (exchange != null) {
            exchange.summaries().forEach(s -> withOrders.add(s.item()));
        }
        List<ResourceLocation> others = new ArrayList<>();
        if (query.isEmpty()) {
            heldItems().stream().filter(id -> !withOrders.contains(id) && freshHeld(id) > 0).forEach(others::add);
        } else {
            withOrders.removeIf(id -> !matches(id, query));
            for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                if (others.size() >= MAX_SEARCH_RESULTS) {
                    break;
                }
                if (!withOrders.contains(id) && ExchangeService.tradeable(BuiltInRegistries.ITEM.get(id)) && matches(id, query)) {
                    others.add(id);
                }
            }
        }
        java.util.Comparator<ResourceLocation> byName = java.util.Comparator.comparing((ResourceLocation id) -> displayName(id));
        withOrders.sort(byName);
        others.sort(byName);
        exchangeItems.addAll(withOrders);
        exchangeItems.addAll(others);
    }

    // ---- Exchange --------------------------------------------------------------------------------------------

    private Component exchangeModeLabel() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return switch (exchangeMode) {
            case ITEMS -> Component.translatable("screen.variable_economy_exchange.exchange.mode.items");
            case LISTINGS -> Component.translatable("screen.variable_economy_exchange.exchange.mode.listings");
            case LIST -> Component.translatable("screen.variable_economy_exchange.exchange.mode.list");
            case ORDERS -> Component.translatable("screen.variable_economy_exchange.exchange.mode.orders", exchange == null ? 0 : exchange.open());
            case BOX -> Component.translatable("screen.variable_economy_exchange.exchange.mode.box",
                    PriceFormat.count(exchange == null ? 0 : exchange.boxed()));
        };
    }

    private void cycleExchangeMode() {
        ExchangeMode was = exchangeMode;
        exchangeMode = ExchangeMode.values()[(exchangeMode.ordinal() + 1) % ExchangeMode.values().length];
        // The server sends every listing only while they're browsed.
        if (was == ExchangeMode.LISTINGS || exchangeMode == ExchangeMode.LISTINGS) {
            PacketDistributor.sendToServer(new ExchangePayloads.ExchangeRequest(Optional.ofNullable(exchangeSelected),
                    exchangeMode == ExchangeMode.LISTINGS));
        }
        screen.scroll = 0;
        screen.listDirty = true;
        screen.updateListButtons();
    }

    private static List<ExchangePayloads.OrderLine> orderLines() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange == null ? List.of() : exchange.orders();
    }

    private static List<ExchangePayloads.BoxLine> boxLines() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange == null ? List.of() : exchange.box();
    }

    private static List<ExchangePayloads.ParcelLine> parcelLines() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange == null ? List.of() : exchange.parcels();
    }

    /** The player's own listings, listed after their orders under My orders. */
    private static List<ExchangePayloads.ListingLine> myListings() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange == null ? List.of() : exchange.listings();
    }

    @Nullable
    private ExchangePayloads.ListingLine selectedMyListing() {
        for (ExchangePayloads.ListingLine line : myListings()) {
            if (Long.valueOf(line.id()).equals(selectedOrder)) {
                return line;
            }
        }
        return null;
    }

    @Nullable
    private ExchangePayloads.ListingLine selectedBrowseListing() {
        ExchangePayloads.ExchangeListings listings = ClientExchangeState.listings();
        if (listings != null) {
            for (ExchangePayloads.ListingLine line : listings.listings()) {
                if (Long.valueOf(line.id()).equals(selectedListing)) {
                    return line;
                }
            }
        }
        return null;
    }

    /** Main-inventory slots holding something that can be listed, as the server checks it. */
    private List<Integer> listableSlots() {
        List<Integer> slots = new ArrayList<>();
        List<ItemStack> items = screen.minecraft().player.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) {
            if (ExchangeService.listable(items.get(slot))) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private ItemStack slotStack(int slot) {
        return screen.minecraft().player.getInventory().items.get(slot);
    }

    /** How many fresh copies of {@code item} the main inventory holds: what a sell order on the book can take. */
    private int freshHeld(ResourceLocation item) {
        return heldLike(new ItemStack(BuiltInRegistries.ITEM.get(item)));
    }

    /** How many units of exactly {@code like} the main inventory holds. */
    private int heldLike(ItemStack like) {
        int count = 0;
        for (ItemStack stack : screen.minecraft().player.getInventory().items) {
            if (ItemStack.isSameItemSameComponents(stack, like)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Keeps the item picked to list in step with the inventory: follows it to another slot, or drops it. */
    private void checkListSlot() {
        if (listSlot < 0 || ItemStack.isSameItemSameComponents(slotStack(listSlot), listStack)) {
            return;
        }
        for (int slot : listableSlots()) {
            if (ItemStack.isSameItemSameComponents(slotStack(slot), listStack)) {
                listSlot = slot;
                return;
            }
        }
        listSlot = -1;
        listStack = ItemStack.EMPTY;
    }

    @Nullable
    private ExchangePayloads.OrderLine selectedOrderLine() {
        for (ExchangePayloads.OrderLine line : orderLines()) {
            if (Long.valueOf(line.id()).equals(selectedOrder)) {
                return line;
            }
        }
        return null;
    }

    /** The best prices for an item, or null if it has no orders. */
    @Nullable
    private static ExchangeService.Summary summary(ResourceLocation item) {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        if (exchange != null) {
            for (ExchangeService.Summary summary : exchange.summaries()) {
                if (summary.item().equals(item)) {
                    return summary;
                }
            }
        }
        return null;
    }

    private long priceValue() {
        try {
            return Math.max(0, Long.parseLong(price.getValue()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Selects an item, asks for its book, and starts the price at the best one on offer. */
    private void selectExchangeItem(ResourceLocation item) {
        if (item.equals(exchangeSelected)) {
            return;
        }
        exchangeSelected = item;
        selectedBookOrder = null;
        PacketDistributor.sendToServer(new ExchangePayloads.ExchangeRequest(Optional.of(item), false));
        ExchangeService.Summary s = summary(item);
        long start = s != null && s.bestAsk() > 0 ? s.bestAsk() : s != null && s.bestBid() > 0 ? s.bestBid() : 0;
        price.setValue(start > 0 ? String.valueOf(start) : "");
    }

    /** Every item the main inventory holds tradeable copies of, from the client's copy, which the server keeps in sync. */
    private List<ResourceLocation> heldItems() {
        List<ResourceLocation> held = new ArrayList<>();
        for (ItemStack stack : screen.minecraft().player.getInventory().items) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!stack.isEmpty() && ExchangeService.tradeable(stack.getItem()) && !held.contains(id)) {
                held.add(id);
            }
        }
        return held;
    }

    /** {@code unitPrice × qty}, or empty if it would overflow a {@code long}. */
    private static java.util.OptionalLong checkedTotal(long unitPrice, int qty) {
        try {
            long total = Math.multiplyExact(unitPrice, (long) qty);
            return java.util.OptionalLong.of(total);
        } catch (ArithmeticException e) {
            return java.util.OptionalLong.empty();
        }
    }

    /** "All" in the item view: every copy held, up to the largest order. */
    private void fillExchangeAll() {
        if (exchangeMode == ExchangeMode.LISTINGS) {
            ExchangePayloads.ListingLine line = selectedBrowseListing();
            if (line != null) {
                screen.setQuantity(line.remaining(), Component.empty());
            }
            return;
        }
        if (exchangeMode == ExchangeMode.LIST) {
            if (listSlot >= 0) {
                screen.setQuantity(Math.min(heldLike(listStack), listStack.getMaxStackSize()), Component.empty());
            }
            return;
        }
        if (exchangeSelected == null) {
            return;
        }
        int held = freshHeld(exchangeSelected);
        if (held == 0) {
            screen.quantityNote = Component.translatable("screen.variable_economy_exchange.note.none_held");
            screen.quantityNoteFor = screen.quantityValue();
            return;
        }
        // With another player's buy order picked, All fills it: at most the units it still wants.
        ExchangeService.BookOrder picked = selectedBookOrder();
        int limit = picked != null && !isAsk(picked) ? Math.min(quantityLimit(), picked.quantity()) : quantityLimit();
        screen.setQuantity(Math.min(held, limit), Component.empty());
    }

    /** Why the order can't be posted, as far as the client can tell, or null if it looks fine. The server re-checks. */
    @Nullable
    private Component postProblem(boolean buy, int qty, long each) {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        String problem;
        if (!ClientExchangeState.enabled()) {
            problem = "exchange_closed";
        } else if (qty <= 0) {
            problem = "invalid_quantity";
        } else if (each <= 0) {
            problem = "invalid_price";
        } else if (exchange != null && qty > exchange.maxOrderSize() || checkedTotal(each, qty).isEmpty()) {
            problem = "too_large";
        } else if (exchange != null && exchange.open() >= exchange.maxOrders()) {
            problem = "order_limit";
        } else if (buy && !ClientExchangeState.looksAffordable(ExchangeService.withTax(checkedTotal(each, qty).getAsLong(), taxRate())
                .orElse(Long.MAX_VALUE) + fee())) {
            problem = "no_funds";
        } else if (!buy && exchangeSelected != null && freshHeld(exchangeSelected) < qty) {
            problem = "not_enough_items";
        } else if (!buy && fee() > 0 && !ClientExchangeState.looksAffordable(fee())) {
            // Posting a sell order takes the fee from the account before the items.
            problem = "no_funds";
        } else {
            return null;
        }
        return Component.translatable("screen.variable_economy_exchange.result." + problem);
    }

    /** Why the listing can't be bought, as far as the client can tell, or null if it looks fine. */
    @Nullable
    private Component buyListingProblem(ExchangePayloads.ListingLine line, int qty) {
        String problem;
        if (!ClientExchangeState.enabled()) {
            problem = "exchange_closed";
        } else if (line.mine()) {
            problem = "own_listing";
        } else if (qty <= 0) {
            problem = "invalid_quantity";
        } else if (qty > line.remaining()) {
            problem = "no_stock";
        } else if (!ClientExchangeState.looksAffordable(ExchangeService.withTax(checkedTotal(line.price(), qty).orElse(Long.MAX_VALUE), taxRate())
                .orElse(Long.MAX_VALUE))) {
            problem = "no_funds";
        } else {
            return null;
        }
        return Component.translatable("screen.variable_economy_exchange.result." + problem);
    }

    /** Why the picked item can't be listed, as far as the client can tell, or null if it looks fine. */
    @Nullable
    private Component listProblem(int qty, long each) {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        String problem;
        if (!ClientExchangeState.enabled()) {
            problem = "exchange_closed";
        } else if (qty <= 0) {
            problem = "invalid_quantity";
        } else if (each <= 0) {
            problem = "invalid_price";
        } else if (qty > listStack.getMaxStackSize() || checkedTotal(each, qty).isEmpty()) {
            problem = "too_large";
        } else if (exchange != null && exchange.open() >= exchange.maxOrders()) {
            problem = "order_limit";
        } else if (heldLike(listStack) < qty) {
            problem = "not_enough_items";
        } else if (fee() > 0 && !ClientExchangeState.looksAffordable(fee())) {
            problem = "no_funds";
        } else {
            return null;
        }
        return Component.translatable("screen.variable_economy_exchange.result." + problem);
    }

    /** "1 day" or "N days". */
    private static net.minecraft.network.chat.MutableComponent days(long days) {
        return Component.translatable(days == 1 ? "screen.variable_economy_exchange.exchange.order.day"
                : "screen.variable_economy_exchange.exchange.order.days", days);
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /** The buyer's tax as a fraction, as the server last said. */
    private static double taxRate() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange == null ? 0 : exchange.tax();
    }

    /** The fee per post, as the server last said, kept below what adding it to a total could overflow. */
    private static long fee() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange == null ? 0 : Math.min(exchange.listingFee(), Long.MAX_VALUE / 2);
    }

    /** A tooltip with the posting fee's line added, when there's a fee. */
    private static Component withFee(Component tooltip, long fee) {
        return fee <= 0 ? tooltip : tooltip.copy().append("\n").append(Component.translatable("screen.variable_economy_exchange.exchange.fee",
                PriceFormat.money(PriceFormat.amount(fee))));
    }

    /** Client-side hints only; the server re-checks everything and replies with the real outcome. */
    void refreshButtons() {
        exchangeModeButton.setMessage(exchangeModeLabel());
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        switch (exchangeMode) {
            case ITEMS -> {
                int qty = screen.quantityValue();
                if (qty != screen.quantityNoteFor) {
                    screen.quantityNote = Component.empty();
                }
                long each = priceValue();
                long total = qty > 0 && each > 0 ? checkedTotal(each, qty).orElse(0) : 0;
                long tax = ExchangeService.tax(total, exchange == null ? 0 : exchange.tax());
                long fee = exchange == null ? 0 : exchange.listingFee();
                // A buy order's button shows what posting it takes from the account: the money it holds, tax included, and the fee.
                long charged = saturatedAdd(saturatedAdd(total, tax), fee);
                postBuyButton.setMessage(total > 0 && exchangeSelected != null
                        ? Component.translatable("screen.variable_economy_exchange.exchange.buy_for", PriceFormat.amount(qty), PriceFormat.amount(charged))
                        : Component.translatable("screen.variable_economy_exchange.exchange.buy"));
                postSellButton.setMessage(total > 0 && exchangeSelected != null
                        ? Component.translatable("screen.variable_economy_exchange.exchange.sell_for", PriceFormat.amount(qty), PriceFormat.amount(total))
                        : Component.translatable("screen.variable_economy_exchange.exchange.sell"));
                Component buyProblem = exchangeSelected == null ? Component.translatable("screen.variable_economy_exchange.exchange.select_hint")
                        : postProblem(true, qty, each);
                Component sellProblem = exchangeSelected == null ? buyProblem : postProblem(false, qty, each);
                postBuyButton.active = buyProblem == null;
                postSellButton.active = sellProblem == null;
                postBuyButton.setTooltip(Tooltip.create(buyProblem != null ? buyProblem
                        : withFee(Component.translatable("screen.variable_economy_exchange.exchange.buy.tooltip", PriceFormat.money(PriceFormat.amount(total + tax)),
                                PriceFormat.money(PriceFormat.amount(tax))), fee)));
                postSellButton.setTooltip(Tooltip.create(sellProblem != null ? sellProblem
                        : withFee(Component.translatable("screen.variable_economy_exchange.exchange.sell.tooltip", PriceFormat.money(PriceFormat.amount(total))), fee)));
                refreshFillButton(qty, each);
            }
            case LISTINGS -> {
                int qty = screen.quantityValue();
                if (qty != screen.quantityNoteFor) {
                    screen.quantityNote = Component.empty();
                }
                ExchangePayloads.ListingLine line = selectedBrowseListing();
                long value = line == null || qty <= 0 ? 0 : checkedTotal(line.price(), qty).orElse(0);
                long total = ExchangeService.withTax(value, exchange == null ? 0 : exchange.tax()).orElse(0);
                exchangeActionButton.setMessage(total > 0
                        ? Component.translatable("screen.variable_economy_exchange.exchange.buy_for", PriceFormat.amount(qty), PriceFormat.amount(total))
                        : Component.translatable("screen.variable_economy_exchange.exchange.buy_listing"));
                Component problem = line == null ? Component.translatable("screen.variable_economy_exchange.exchange.listings.select_hint")
                        : buyListingProblem(line, qty);
                exchangeActionButton.active = problem == null;
                exchangeActionButton.setTooltip(Tooltip.create(problem != null ? problem
                        : Component.translatable("screen.variable_economy_exchange.exchange.buy_listing.tooltip", PriceFormat.money(PriceFormat.amount(total)))));
            }
            case LIST -> {
                checkListSlot();
                int qty = screen.quantityValue();
                if (qty != screen.quantityNoteFor) {
                    screen.quantityNote = Component.empty();
                }
                long each = priceValue();
                long total = qty > 0 && each > 0 ? checkedTotal(each, qty).orElse(0) : 0;
                exchangeActionButton.setMessage(total > 0 && listSlot >= 0
                        ? Component.translatable("screen.variable_economy_exchange.exchange.list_for", PriceFormat.amount(qty), PriceFormat.amount(total))
                        : Component.translatable("screen.variable_economy_exchange.exchange.list"));
                Component problem = listSlot < 0 ? Component.translatable("screen.variable_economy_exchange.exchange.list.select_hint")
                        : listProblem(qty, each);
                exchangeActionButton.active = problem == null;
                exchangeActionButton.setTooltip(Tooltip.create(problem != null ? problem
                        : withFee(Component.translatable("screen.variable_economy_exchange.exchange.list.tooltip", PriceFormat.money(PriceFormat.amount(total))),
                        exchange == null ? 0 : exchange.listingFee())));
            }
            case ORDERS -> {
                ExchangePayloads.OrderLine line = selectedOrderLine();
                ExchangePayloads.ListingLine listing = selectedMyListing();
                exchangeActionButton.setMessage(Component.translatable("screen.variable_economy_exchange.exchange.cancel"));
                exchangeActionButton.active = line != null || listing != null;
                exchangeActionButton.setTooltip(listing != null
                        ? Tooltip.create(Component.translatable("screen.variable_economy_exchange.exchange.cancel.items", PriceFormat.count(listing.remaining())))
                        : line == null ? null : Tooltip.create(line.buy()
                        ? Component.translatable("screen.variable_economy_exchange.exchange.cancel.money",
                                PriceFormat.money(PriceFormat.amount(line.held())))
                        : Component.translatable("screen.variable_economy_exchange.exchange.cancel.items", PriceFormat.count(line.remaining()))));
            }
            case BOX -> {
                long boxed = exchange == null ? 0 : exchange.boxed();
                exchangeActionButton.setMessage(Component.translatable("screen.variable_economy_exchange.exchange.take_all", PriceFormat.count(boxed)));
                exchangeActionButton.active = boxed > 0;
                exchangeActionButton.setTooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.exchange.take_all.tooltip")));
            }
        }
    }

    /**
     * With another player's order picked in the book, the button on the other side of it fills that order instead of
     * posting: Buy for a sell order, Sell for a buy order. Changing the price lets the order go.
     */
    private void refreshFillButton(int qty, long each) {
        ExchangeService.BookOrder order = selectedBookOrder();
        if (order == null || each != order.price()) {
            selectedBookOrder = null;
            return;
        }
        boolean ask = isAsk(order);
        long value = checkedTotal(order.price(), Math.max(qty, 0)).orElse(0);
        long cost = ExchangeService.withTax(value, taxRate()).orElse(Long.MAX_VALUE);
        Component problem = fillProblem(order, ask, qty, cost);
        net.minecraft.client.gui.components.Button button = ask ? postBuyButton : postSellButton;
        button.setMessage(Component.translatable(ask ? "screen.variable_economy_exchange.exchange.fill_buy" : "screen.variable_economy_exchange.exchange.fill_sell",
                PriceFormat.amount(Math.max(qty, 0)), order.poster()));
        button.active = problem == null;
        button.setTooltip(Tooltip.create(problem != null ? problem
                : ask ? Component.translatable("screen.variable_economy_exchange.exchange.fill_buy.tooltip", order.poster(), PriceFormat.money(PriceFormat.amount(cost)),
                        PriceFormat.money(PriceFormat.amount(cost - value)))
                : Component.translatable("screen.variable_economy_exchange.exchange.fill_sell.tooltip", order.poster(), PriceFormat.money(PriceFormat.amount(value)))));
    }

    @Nullable
    private Component fillProblem(ExchangeService.BookOrder order, boolean ask, int qty, long cost) {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        String problem;
        if (!ClientExchangeState.enabled()) {
            problem = "exchange_closed";
        } else if (qty <= 0) {
            problem = "invalid_quantity";
        } else if (qty > order.quantity()) {
            problem = "no_stock";
        } else if (exchange != null && qty > exchange.maxOrderSize()) {
            problem = "too_large";
        } else if (ask && !ClientExchangeState.looksAffordable(cost)) {
            problem = "no_funds";
        } else if (!ask && exchangeSelected != null && freshHeld(exchangeSelected) < qty) {
            problem = "not_enough_items";
        } else {
            return null;
        }
        return Component.translatable("screen.variable_economy_exchange.result." + problem);
    }

    /** The order picked in the book, if it's still there and isn't the player's own. */
    @Nullable
    private ExchangeService.BookOrder selectedBookOrder() {
        ExchangePayloads.ExchangeBook book = exchangeSelected == null || selectedBookOrder == null ? null : ClientExchangeState.book(exchangeSelected);
        if (book == null) {
            return null;
        }
        for (List<ExchangeService.BookOrder> side : List.of(book.asks(), book.bids())) {
            for (ExchangeService.BookOrder order : side) {
                if (order.id() == selectedBookOrder && !order.mine()) {
                    return order;
                }
            }
        }
        return null;
    }

    /** Whether a book order is a sell order: it's on the asks side of the selected item's book. */
    private boolean isAsk(ExchangeService.BookOrder order) {
        ExchangePayloads.ExchangeBook book = exchangeSelected == null ? null : ClientExchangeState.book(exchangeSelected);
        return book != null && book.asks().stream().anyMatch(o -> o.id() == order.id());
    }

    private void postOrder(boolean buy) {
        int qty = screen.quantityValue();
        long each = priceValue();
        ExchangeService.BookOrder picked = selectedBookOrder();
        if (picked != null && each == picked.price() && isAsk(picked) == buy) {
            long cost = ExchangeService.withTax(checkedTotal(picked.price(), Math.max(qty, 0)).orElse(0), taxRate()).orElse(Long.MAX_VALUE);
            if (fillProblem(picked, buy, qty, cost) == null) {
                PacketDistributor.sendToServer(ExchangePayloads.ExchangeAction.fill(picked.id(), qty));
            }
            return;
        }
        if (exchangeSelected != null && postProblem(buy, qty, each) == null) {
            PacketDistributor.sendToServer(ExchangePayloads.ExchangeAction.post(exchangeSelected, buy, each, qty));
        }
    }

    /** Buys from the selected listing, lists the picked item, cancels the selected order, or empties the box. */
    private void exchangeAction() {
        int qty = screen.quantityValue();
        switch (exchangeMode) {
            case LISTINGS -> {
                ExchangePayloads.ListingLine line = selectedBrowseListing();
                if (line != null && buyListingProblem(line, qty) == null) {
                    PacketDistributor.sendToServer(ExchangePayloads.ExchangeAction.buyListing(line.id(), qty, line.price()));
                }
            }
            case LIST -> {
                long each = priceValue();
                if (listSlot >= 0 && listProblem(qty, each) == null) {
                    PacketDistributor.sendToServer(new ExchangePayloads.ListItem(listSlot, listStack.copy(), each, qty));
                }
            }
            case ORDERS -> {
                if (selectedOrderLine() != null || selectedMyListing() != null) {
                    PacketDistributor.sendToServer(ExchangePayloads.ExchangeAction.cancel(selectedOrder));
                }
            }
            case BOX -> PacketDistributor.sendToServer(ExchangePayloads.ExchangeAction.collect(Optional.empty()));
            default -> {
            }
        }
    }

    /** One order drawn in the book, in screen coordinates; {@code ask} for a sell order. */
    private record BookRow(int left, int top, int right, int bottom, ExchangeService.BookOrder order, boolean ask) {
    }

    @Nullable
    private BookRow bookRowAt(double mouseX, double mouseY) {
        if (exchangeMode != ExchangeMode.ITEMS) {
            return null;
        }
        for (BookRow row : bookRows) {
            if (mouseX >= row.left() && mouseX < row.right() && mouseY >= row.top() && mouseY < row.bottom()) {
                return row;
            }
        }
        return null;
    }

    /** The list: items with their best prices, listings, items to list, the player's open orders, or their box. */
    private void renderExchangeList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        int listRight = LIST_X + screen.listWidth;
        int headerY = y + HEADER_Y;
        int hovered = screen.rowAt(mouseX, mouseY);
        int size = listSize();
        switch (exchangeMode) {
            case ITEMS -> {
                graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
                screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.exchange.col.ask"), x + screen.colBuy, headerY, TEXT_DIM);
                screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.exchange.col.bid"), x + screen.colSell, headerY, TEXT_DIM);
                screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.col.have"), x + screen.colHave, headerY, TEXT_DIM);
            }
            case LISTINGS -> {
                graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
                screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.exchange.col.price"), x + screen.colSell, headerY, TEXT_DIM);
                screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.exchange.col.left"), x + screen.colHave, headerY, TEXT_DIM);
            }
            case LIST -> {
                graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
                screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.col.have"), x + screen.colHave, headerY, TEXT_DIM);
            }
            case ORDERS -> graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.exchange.orders.title",
                    exchange == null ? 0 : exchange.open(), exchange == null ? 0 : exchange.maxOrders()), x + LIST_X + 4, headerY, TEXT_DIM, false);
            case BOX -> {
                graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.exchange.box.title"), x + LIST_X + 4, headerY, TEXT_DIM, false);
                screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.exchange.col.waiting"), x + listRight - 2, headerY, TEXT_DIM);
            }
        }
        List<Integer> slots = exchangeMode == ExchangeMode.LIST ? listableSlots() : List.of();
        int orders = orderLines().size();
        int boxed = boxLines().size();
        for (int i = 0; i < screen.rows && screen.scroll + i < size; i++) {
            int index = screen.scroll + i;
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            boolean picked = switch (exchangeMode) {
                case ITEMS -> exchangeItems.get(index).equals(exchangeSelected);
                case LISTINGS -> Long.valueOf(browseLines.get(index).id()).equals(selectedListing);
                case LIST -> slots.get(index) == listSlot;
                case ORDERS -> Long.valueOf(index < orders ? orderLines().get(index).id() : myListings().get(index - orders).id()).equals(selectedOrder);
                case BOX -> false;
            };
            int background = picked ? ROW_SELECTED : index == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            int textY = rowY + 5;
            switch (exchangeMode) {
                case ITEMS -> {
                    ResourceLocation item = exchangeItems.get(index);
                    graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(item)), x + LIST_X + 2, rowY + 1);
                    graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(displayName(item), screen.colBuy - 44 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
                    ExchangeService.Summary s = summary(item);
                    boolean asks = s != null && s.bestAsk() > 0;
                    boolean bids = s != null && s.bestBid() > 0;
                    screen.drawRight(graphics, Component.literal(asks ? PriceFormat.compact(s.bestAsk()) : "-"), x + screen.colBuy, textY, asks ? TEXT_MONEY : TEXT_DIM);
                    screen.drawRight(graphics, Component.literal(bids ? PriceFormat.compact(s.bestBid()) : "-"), x + screen.colSell, textY, bids ? TEXT_GOOD : TEXT_DIM);
                    int held = freshHeld(item);
                    screen.drawRight(graphics, Component.literal(PriceFormat.count(held)), x + screen.colHave, textY, held > 0 ? TEXT : TEXT_DIM);
                }
                case LISTINGS -> {
                    ExchangePayloads.ListingLine line = browseLines.get(index);
                    screen.renderStack(graphics, line.stack(), x + LIST_X + 2, rowY + 1);
                    String name = line.stack().getHoverName().getString() + (line.mine() ? " *" : "");
                    graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(name, screen.colSell - 44 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
                    screen.drawRight(graphics, Component.literal(PriceFormat.compact(line.price())), x + screen.colSell, textY, TEXT_MONEY);
                    screen.drawRight(graphics, Component.literal(PriceFormat.count(line.remaining())), x + screen.colHave, textY, TEXT);
                }
                case LIST -> {
                    ItemStack stack = slotStack(slots.get(index));
                    screen.renderStack(graphics, stack, x + LIST_X + 2, rowY + 1);
                    graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(stack.getHoverName().getString(), screen.colHave - 44 - LIST_X - 22),
                            x + LIST_X + 22, textY, TEXT, false);
                    screen.drawRight(graphics, Component.literal(PriceFormat.count(heldLike(stack))), x + screen.colHave, textY, TEXT);
                }
                case ORDERS -> {
                    if (index >= orders) {
                        renderListingRow(graphics, myListings().get(index - orders), x, rowY, textY, listRight);
                        continue;
                    }
                    ExchangePayloads.OrderLine line = orderLines().get(index);
                    int priceRight = listRight - 60;
                    graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(line.item())), x + LIST_X + 2, rowY + 1);
                    Component what = Component.translatable(line.buy() ? "screen.variable_economy_exchange.exchange.order.buy"
                            : "screen.variable_economy_exchange.exchange.order.sell", PriceFormat.amount(line.quantity()), displayName(line.item()));
                    graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(what.getString(), priceRight - 50 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
                    screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.exchange.order.each", PriceFormat.amount(line.price())),
                            x + priceRight, textY, line.buy() ? TEXT_MONEY : TEXT_GOOD);
                    screen.drawRight(graphics, Component.literal(PriceFormat.count(line.filled()) + "/" + PriceFormat.count(line.quantity())),
                            x + listRight - 2, textY, line.filled() > 0 ? TEXT : TEXT_DIM);
                }
                case BOX -> {
                    if (index >= boxed) {
                        ExchangePayloads.ParcelLine parcel = parcelLines().get(index - boxed);
                        screen.renderStack(graphics, parcel.stack(), x + LIST_X + 2, rowY + 1);
                        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(parcel.stack().getHoverName().getString(), screen.listWidth - 90),
                                x + LIST_X + 22, textY, TEXT, false);
                        screen.drawRight(graphics, Component.literal(PriceFormat.count(parcel.count())), x + listRight - 2, textY, TEXT);
                        continue;
                    }
                    ExchangePayloads.BoxLine line = boxLines().get(index);
                    graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(line.item())), x + LIST_X + 2, rowY + 1);
                    graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(displayName(line.item()), screen.listWidth - 90), x + LIST_X + 22, textY, TEXT, false);
                    screen.drawRight(graphics, Component.literal(PriceFormat.count(line.count())), x + listRight - 2, textY, TEXT);
                }
            }
        }
        if (size == 0) {
            String key = exchange == null ? "screen.variable_economy_exchange.trades.loading" : switch (exchangeMode) {
                case ITEMS -> screen.search.getValue().isBlank() ? "screen.variable_economy_exchange.exchange.none_items" : "screen.variable_economy_exchange.no_matches";
                case LISTINGS -> ClientExchangeState.listings() == null ? "screen.variable_economy_exchange.trades.loading"
                        : screen.search.getValue().isBlank() ? "screen.variable_economy_exchange.exchange.none_listings" : "screen.variable_economy_exchange.no_matches";
                case LIST -> "screen.variable_economy_exchange.exchange.none_listable";
                case ORDERS -> "screen.variable_economy_exchange.exchange.none_orders";
                case BOX -> "screen.variable_economy_exchange.exchange.none_box";
            };
            // Long enough to need wrapping when it explains what can be listed.
            List<net.minecraft.util.FormattedCharSequence> lines = screen.font().split(Component.translatable(key), screen.listWidth - 8);
            for (int i = 0; i < lines.size(); i++) {
                graphics.drawCenteredString(screen.font(), lines.get(i), x + LIST_X + screen.listWidth / 2, y + LIST_Y + 20 + i * 10, TEXT_DIM);
            }
        } else if (exchangeMode == ExchangeMode.LISTINGS && ClientExchangeState.listings() != null
                && ClientExchangeState.listings().more() && screen.scroll + screen.rows >= size) {
            graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.exchange.listings.more"), x + LIST_X + 4,
                    y + LIST_Y + Math.min(screen.rows, size) * ROW_HEIGHT + 2, TEXT_DIM, false);
        }
    }

    /** One of the player's listings under My orders, drawn like an order. */
    private void renderListingRow(GuiGraphics graphics, ExchangePayloads.ListingLine line, int x, int rowY, int textY, int listRight) {
        int priceRight = listRight - 60;
        screen.renderStack(graphics, line.stack(), x + LIST_X + 2, rowY + 1);
        Component what = Component.translatable("screen.variable_economy_exchange.exchange.order.listing", PriceFormat.amount(line.quantity()),
                line.stack().getHoverName().getString());
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(what.getString(), priceRight - 50 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
        screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.exchange.order.each", PriceFormat.amount(line.price())),
                x + priceRight, textY, TEXT_GOOD);
        screen.drawRight(graphics, Component.literal(PriceFormat.count(line.sold()) + "/" + PriceFormat.count(line.quantity())),
                x + listRight - 2, textY, line.sold() > 0 ? TEXT : TEXT_DIM);
    }

    /** A listed item's own tooltip, then who sells it and for how much. */
    private List<Component> listingTooltip(ExchangePayloads.ListingLine line) {
        List<Component> lines = new ArrayList<>(net.minecraft.client.gui.screens.Screen.getTooltipFromItem(screen.minecraft(), line.stack()));
        lines.add(Component.translatable("screen.variable_economy_exchange.exchange.listing.offer", PriceFormat.count(line.remaining()),
                PriceFormat.money(PriceFormat.amount(line.price()))).withColor(TEXT_MONEY));
        lines.add((line.mine() ? Component.translatable("screen.variable_economy_exchange.exchange.listing.yours")
                : Component.translatable("screen.variable_economy_exchange.exchange.listing.by", line.seller())).withColor(TEXT_DIM));
        if (line.daysLeft() >= 0) {
            lines.add(days(line.daysLeft()).withColor(TEXT_DIM));
        }
        return lines;
    }

    /** The item's tooltip below its name, from {@code top} down to {@code bottom}, so a buyer sees it in full. */
    private void renderItemDetails(GuiGraphics graphics, ItemStack stack, int left, int top, int bottom) {
        List<Component> lines = net.minecraft.client.gui.screens.Screen.getTooltipFromItem(screen.minecraft(), stack);
        int y = top;
        // The first line is the name, shown above already.
        for (int i = 1; i < lines.size(); i++) {
            for (net.minecraft.util.FormattedCharSequence part : screen.font().split(lines.get(i), screen.panelWidth)) {
                if (y + 9 > bottom) {
                    return;
                }
                graphics.drawString(screen.font(), part, left, y, TEXT_DIM, false);
                y += 10;
            }
        }
    }

    /** Browsing listings: the selected listing in full, and what buying the quantity costs. */
    private void renderListingPanel(GuiGraphics graphics, int x, int y, String percent) {
        int left = x + screen.panelX;
        ExchangePayloads.ListingLine line = selectedBrowseListing();
        if (line == null) {
            renderHelp(graphics, Component.translatable(screen.wide ? "screen.variable_economy_exchange.exchange.listings.help"
                    : "screen.variable_economy_exchange.exchange.listings.select_hint", percent), left, y + screen.panelY + 4);
            return;
        }
        screen.renderStack(graphics, line.stack(), left, y + screen.panelY);
        String name = line.stack().getHoverName().getString();
        if (screen.wide) {
            int right = left + screen.panelWidth;
            int top = y + screen.chartY;
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(name, screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            screen.infoLine(graphics, "exchange.listing.seller", line.mine() ? Component.translatable("screen.variable_economy_exchange.exchange.listing.you").getString()
                    : line.seller(), TEXT, left, right, top);
            screen.infoLine(graphics, "exchange.order.price", PriceFormat.amount(line.price()), TEXT_MONEY, left, right, top + INFO_LINE_HEIGHT);
            screen.infoLine(graphics, "exchange.listing.left", PriceFormat.count(line.remaining()), TEXT, left, right, top + 2 * INFO_LINE_HEIGHT);
            Component expires = line.daysLeft() < 0 ? Component.translatable("screen.variable_economy_exchange.exchange.order.never")
                    : days(line.daysLeft());
            screen.infoLine(graphics, "exchange.order.expires", expires.getString(), TEXT_DIM, left, right, top + 3 * INFO_LINE_HEIGHT);
            renderItemDetails(graphics, line.stack(), left, top + 4 * INFO_LINE_HEIGHT + 4, y + screen.priceY - 4);
        } else {
            Component summary = Component.translatable("screen.variable_economy_exchange.exchange.listing.summary", name, PriceFormat.amount(line.price()),
                    PriceFormat.count(line.remaining()), line.seller());
            // The Sort button shares this row on a narrow window.
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(summary.getString(), screen.panelWidth - 20 - screen.sortWidth - 4),
                    left + 20, y + screen.panelY + 4, TEXT, false);
        }
        int qty = screen.quantityValue();
        ExchangePayloads.Exchange state = ClientExchangeState.exchange();
        long total = qty > 0 ? ExchangeService.withTax(checkedTotal(line.price(), qty).orElse(0), state == null ? 0 : state.tax()).orElse(0) : 0;
        Component note = !screen.quantityNote.getString().isEmpty() ? screen.quantityNote
                : total > 0 ? Component.translatable("screen.variable_economy_exchange.exchange.listing.total", PriceFormat.amount(total)) : Component.empty();
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(note.getString(), screen.noteWidth), x + screen.noteX, y + screen.noteY, TEXT_DIM, false);
    }

    /** Listing an item: the picked item in full, the price box, and what the listing comes to. */
    private void renderListPanel(GuiGraphics graphics, int x, int y, String percent) {
        int left = x + screen.panelX;
        if (listSlot < 0) {
            renderHelp(graphics, Component.translatable(screen.wide ? "screen.variable_economy_exchange.exchange.list.help"
                    : "screen.variable_economy_exchange.exchange.list.select_hint", percent), left, y + screen.panelY + 4);
            return;
        }
        screen.renderStack(graphics, listStack, left, y + screen.panelY);
        String name = listStack.getHoverName().getString();
        if (!screen.wide) {
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(name, screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            graphics.drawString(screen.font(), "@", x + screen.noteX, y + screen.controlsY + 5, TEXT_DIM, false);
            return;
        }
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(name, screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
        renderItemDetails(graphics, listStack, left, y + screen.chartY, y + screen.priceY - 14);
        screen.infoLine(graphics, "col.have", PriceFormat.amount(heldLike(listStack)), TEXT, left, left + screen.panelWidth, y + screen.priceY - 12);
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.exchange.price"), left, y + screen.priceY + 3, TEXT_DIM, false);
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        int qty = screen.quantityValue();
        long each = priceValue();
        long total = qty > 0 && each > 0 ? checkedTotal(each, qty).orElse(0) : 0;
        Component note;
        int color = TEXT_DIM;
        if (!screen.quantityNote.getString().isEmpty()) {
            note = screen.quantityNote;
        } else if (qty > listStack.getMaxStackSize()) {
            note = Component.translatable("screen.variable_economy_exchange.note.over_limit", PriceFormat.amount(listStack.getMaxStackSize()));
            color = TEXT_BAD;
        } else if (total > 0) {
            note = Component.translatable("screen.variable_economy_exchange.exchange.note.total", PriceFormat.amount(total),
                    PriceFormat.amount(ExchangeService.tax(total, exchange == null ? 0 : exchange.tax())));
        } else {
            note = Component.translatable("screen.variable_economy_exchange.exchange.note.tax", percent);
        }
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(note.getString(), screen.noteWidth), x + screen.noteX, y + screen.noteY, color, false);
    }

    /** The panel: the selected item's book and order controls, the selected order or listing, or the box's help. */
    private void renderExchangePanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        int left = x + screen.panelX;
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        String percent = exchange == null ? "-" : String.format(Locale.ROOT, "%.1f%%", exchange.tax() * 100);
        if (exchangeMode == ExchangeMode.BOX) {
            renderHelp(graphics, Component.translatable("screen.variable_economy_exchange.exchange.box.help"), left, y + screen.panelY + 4);
            return;
        }
        if (exchangeMode == ExchangeMode.ORDERS) {
            renderOrderPanel(graphics, x, y);
            return;
        }
        if (exchangeMode == ExchangeMode.LISTINGS) {
            renderListingPanel(graphics, x, y, percent);
            return;
        }
        if (exchangeMode == ExchangeMode.LIST) {
            renderListPanel(graphics, x, y, percent);
            return;
        }
        if (exchangeSelected == null) {
            renderHelp(graphics, Component.translatable(screen.wide ? "screen.variable_economy_exchange.exchange.help" : "screen.variable_economy_exchange.exchange.select_hint",
                    percent), left, y + screen.panelY + 4);
            return;
        }
        graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(exchangeSelected)), left, y + screen.panelY);
        if (!screen.wide) {
            ExchangeService.Summary s = summary(exchangeSelected);
            Component line = Component.translatable("screen.variable_economy_exchange.exchange.summary", displayName(exchangeSelected),
                    s != null && s.bestAsk() > 0 ? PriceFormat.amount(s.bestAsk()) : "-", s != null && s.bestBid() > 0 ? PriceFormat.amount(s.bestBid()) : "-");
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(line.getString(), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            graphics.drawString(screen.font(), "@", x + screen.noteX, y + screen.controlsY + 5, TEXT_DIM, false);
            return;
        }
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(displayName(exchangeSelected), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
        renderBook(graphics, left, y + screen.chartY, y + screen.priceY - 13, mouseX, mouseY);
        int held = freshHeld(exchangeSelected);
        screen.infoLine(graphics, "col.have", PriceFormat.amount(held), held > 0 ? TEXT : TEXT_DIM, left, left + screen.panelWidth, y + screen.priceY - 12);
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.exchange.price"), left, y + screen.priceY + 3, TEXT_DIM, false);

        // Under the quantity: what the order comes to, and the tax a sale pays.
        int qty = screen.quantityValue();
        long each = priceValue();
        long total = qty > 0 && each > 0 ? checkedTotal(each, qty).orElse(0) : 0;
        Component note;
        int color = TEXT_DIM;
        if (!screen.quantityNote.getString().isEmpty()) {
            note = screen.quantityNote;
        } else if (exchange != null && qty > exchange.maxOrderSize()) {
            note = Component.translatable("screen.variable_economy_exchange.note.over_limit", PriceFormat.amount(exchange.maxOrderSize()));
            color = TEXT_BAD;
        } else if (total > 0) {
            note = Component.translatable("screen.variable_economy_exchange.exchange.note.total", PriceFormat.amount(total),
                    PriceFormat.amount(ExchangeService.tax(total, exchange == null ? 0 : exchange.tax())));
        } else {
            note = Component.translatable("screen.variable_economy_exchange.exchange.note.tax", percent);
        }
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(note.getString(), screen.noteWidth), x + screen.noteX, y + screen.noteY, color, false);
    }

    /**
     * The selected item's book between {@code top} and {@code bottom}: sellers' prices above the line with the
     * cheapest nearest it, buyers' below with the highest nearest it, one line per order. Clicking one copies its
     * price, and picks another player's order to fill.
     */
    private void renderBook(GuiGraphics graphics, int left, int top, int bottom, int mouseX, int mouseY) {
        int right = left + screen.panelWidth;
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.exchange.book.price"), left, top, TEXT_DIM, false);
        screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.exchange.book.units"), right, top, TEXT_DIM);
        ExchangePayloads.ExchangeBook book = exchangeSelected == null ? null : ClientExchangeState.book(exchangeSelected);
        if (book == null) {
            graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.trades.loading"), left, top + 12, TEXT_DIM, false);
            return;
        }
        int perSide = Mth.clamp((bottom - top - 20) / 20, 1, ExchangePayloads.ExchangeBook.DEPTH);
        int asksTop = top + 10;
        int lineY = asksTop + perSide * 10;
        int bidsTop = lineY + 10;
        int shownAsks = Math.min(perSide, book.asks().size());
        for (int j = 0; j < shownAsks; j++) {
            // The cheapest ask sits right above the line.
            ExchangeService.BookOrder order = book.asks().get(shownAsks - 1 - j);
            bookLine(graphics, order, true, left, right, asksTop + (perSide - shownAsks + j) * 10, TEXT_MONEY, mouseX, mouseY);
        }
        if (book.asks().isEmpty()) {
            graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.exchange.book.no_asks"), left, lineY - 10, TEXT_OFF, false);
        }
        graphics.fill(left, lineY + 4, right, lineY + 5, PANEL_BORDER);
        if (!book.asks().isEmpty() && !book.bids().isEmpty()) {
            Component spread = Component.translatable("screen.variable_economy_exchange.exchange.book.spread",
                    PriceFormat.amount(book.asks().get(0).price() - book.bids().get(0).price()));
            int width = screen.font().width(spread) + 4;
            int middle = (left + right) / 2;
            graphics.fill(middle - width / 2, lineY, middle + width / 2, lineY + 9, PANEL);
            graphics.drawCenteredString(screen.font(), spread, middle, lineY + 1, TEXT_DIM);
        }
        for (int j = 0; j < Math.min(perSide, book.bids().size()); j++) {
            bookLine(graphics, book.bids().get(j), false, left, right, bidsTop + j * 10, TEXT_GOOD, mouseX, mouseY);
        }
        if (book.bids().isEmpty()) {
            graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.exchange.book.no_bids"), left, bidsTop, TEXT_OFF, false);
        }
    }

    /** One order in the book: its price, who posted it (the player's own marked with a star) and its units. */
    private void bookLine(GuiGraphics graphics, ExchangeService.BookOrder order, boolean ask, int left, int right, int top, int color,
                          int mouseX, int mouseY) {
        BookRow row = new BookRow(left, top - 1, right, top + 9, order, ask);
        bookRows.add(row);
        if (Long.valueOf(order.id()).equals(selectedBookOrder)) {
            graphics.fill(row.left(), row.top(), row.right(), row.bottom(), ROW_SELECTED);
        } else if (mouseX >= row.left() && mouseX < row.right() && mouseY >= row.top() && mouseY < row.bottom()) {
            graphics.fill(row.left(), row.top(), row.right(), row.bottom(), ROW_HOVER);
        }
        String priceText = PriceFormat.amount(order.price()) + (order.mine() ? " *" : "");
        graphics.drawString(screen.font(), priceText, left, top, color, false);
        String units = PriceFormat.count(order.quantity());
        int nameLeft = left + screen.font().width(priceText) + 6;
        int nameWidth = right - screen.font().width(units) - 6 - nameLeft;
        if (nameWidth > 12 && !order.mine()) {
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(order.poster(), nameWidth), nameLeft, top, TEXT_DIM, false);
        }
        screen.drawRight(graphics, Component.literal(units), right, top, TEXT);
    }

    /** The selected order: its side, price, progress, what it still holds and when it expires. */
    private void renderOrderPanel(GuiGraphics graphics, int x, int y) {
        int left = x + screen.panelX;
        ExchangePayloads.OrderLine line = selectedOrderLine();
        ExchangePayloads.ListingLine listing = selectedMyListing();
        if (line == null && listing != null) {
            renderMyListingPanel(graphics, x, y, listing);
            return;
        }
        if (line == null) {
            renderHelp(graphics, Component.translatable(screen.wide ? "screen.variable_economy_exchange.exchange.orders.help"
                    : "screen.variable_economy_exchange.exchange.orders.select_hint"), left, y + screen.panelY + 4);
            return;
        }
        graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(line.item())), left, y + screen.panelY);
        Component expires = line.daysLeft() < 0 ? Component.translatable("screen.variable_economy_exchange.exchange.order.never")
                : days(line.daysLeft());
        if (!screen.wide) {
            Component summary = Component.translatable("screen.variable_economy_exchange.exchange.order.summary", displayName(line.item()),
                    PriceFormat.amount(line.price()), PriceFormat.count(line.filled()), PriceFormat.count(line.quantity()));
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(summary.getString(), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            return;
        }
        int right = left + screen.panelWidth;
        int top = y + screen.chartY;
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(displayName(line.item()), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
        screen.infoLine(graphics, "exchange.order.side", Component.translatable(line.buy() ? "screen.variable_economy_exchange.exchange.order.side_buy"
                : "screen.variable_economy_exchange.exchange.order.side_sell").getString(), line.buy() ? TEXT_MONEY : TEXT_GOOD, left, right, top);
        screen.infoLine(graphics, "exchange.order.price", PriceFormat.amount(line.price()), TEXT, left, right, top + INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "exchange.order.filled", PriceFormat.count(line.filled()) + "/" + PriceFormat.count(line.quantity()), TEXT,
                left, right, top + 2 * INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "exchange.order.holds", line.buy() ? PriceFormat.money(PriceFormat.amount(line.held())).getString()
                : Component.translatable("screen.variable_economy_exchange.exchange.order.items", PriceFormat.count(line.remaining())).getString(),
                TEXT, left, right, top + 3 * INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "exchange.order.expires", expires.getString(), TEXT_DIM, left, right, top + 4 * INFO_LINE_HEIGHT);
    }

    /** One of the player's own listings under My orders: its price, sales, what it still holds and when it expires. */
    private void renderMyListingPanel(GuiGraphics graphics, int x, int y, ExchangePayloads.ListingLine line) {
        int left = x + screen.panelX;
        screen.renderStack(graphics, line.stack(), left, y + screen.panelY);
        String name = line.stack().getHoverName().getString();
        if (!screen.wide) {
            Component summary = Component.translatable("screen.variable_economy_exchange.exchange.order.summary", name,
                    PriceFormat.amount(line.price()), PriceFormat.count(line.sold()), PriceFormat.count(line.quantity()));
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(summary.getString(), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            return;
        }
        int right = left + screen.panelWidth;
        int top = y + screen.chartY;
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(name, screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
        screen.infoLine(graphics, "exchange.order.side", Component.translatable("screen.variable_economy_exchange.exchange.order.side_listing").getString(),
                TEXT_GOOD, left, right, top);
        screen.infoLine(graphics, "exchange.order.price", PriceFormat.amount(line.price()), TEXT, left, right, top + INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "exchange.order.sold", PriceFormat.count(line.sold()) + "/" + PriceFormat.count(line.quantity()), TEXT,
                left, right, top + 2 * INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "exchange.order.holds", Component.translatable("screen.variable_economy_exchange.exchange.order.items",
                PriceFormat.count(line.remaining())).getString(), TEXT, left, right, top + 3 * INFO_LINE_HEIGHT);
        Component expires = line.daysLeft() < 0 ? Component.translatable("screen.variable_economy_exchange.exchange.order.never")
                : days(line.daysLeft());
        screen.infoLine(graphics, "exchange.order.expires", expires.getString(), TEXT_DIM, left, right, top + 4 * INFO_LINE_HEIGHT);
        renderItemDetails(graphics, line.stack(), left, top + 5 * INFO_LINE_HEIGHT + 4, y + screen.tradeY - 4);
    }

    /** Wrapped help text from {@code top} down to the controls. */
    private void renderHelp(GuiGraphics graphics, Component text, int left, int top) {
        // On a narrow window the Listings view's Sort button shares the help's one line.
        int width = !screen.wide && exchangeMode == ExchangeMode.LISTINGS ? screen.panelWidth - screen.sortWidth - 4 : screen.panelWidth;
        List<net.minecraft.util.FormattedCharSequence> lines = screen.font().split(text, width);
        // Above the price box or the Sort button when one shows, otherwise above the button; one line when narrow.
        int bottom = screen.topPos() + (!screen.wide ? screen.panelY + 14 : exchangeMode == ExchangeMode.ITEMS || exchangeMode == ExchangeMode.LIST ? screen.priceY - 2
                : exchangeMode == ExchangeMode.LISTINGS ? screen.sortY - 2 : screen.tradeY - 2);
        for (int i = 0; i < lines.size() && top + i * 10 + 9 <= bottom; i++) {
            graphics.drawString(screen.font(), lines.get(i), left, top + i * 10, TEXT_DIM, false);
        }
    }

    /** The item's own tooltip plus its best prices on the exchange. */
    private List<Component> exchangeTooltip(ResourceLocation item) {
        List<Component> lines = new ArrayList<>(net.minecraft.client.gui.screens.Screen.getTooltipFromItem(screen.minecraft(), new ItemStack(BuiltInRegistries.ITEM.get(item))));
        ExchangeService.Summary s = summary(item);
        if (s != null && s.bestAsk() > 0) {
            lines.add(Component.translatable("screen.variable_economy_exchange.exchange.tooltip.ask", PriceFormat.money(PriceFormat.amount(s.bestAsk())),
                    PriceFormat.count(s.askUnits())).withColor(TEXT_MONEY));
        }
        if (s != null && s.bestBid() > 0) {
            lines.add(Component.translatable("screen.variable_economy_exchange.exchange.tooltip.bid", PriceFormat.money(PriceFormat.amount(s.bestBid())),
                    PriceFormat.count(s.bidUnits())).withColor(TEXT_GOOD));
        }
        if (s == null) {
            lines.add(Component.translatable("screen.variable_economy_exchange.exchange.tooltip.none").withColor(TEXT_DIM));
        }
        return lines;
    }
}
