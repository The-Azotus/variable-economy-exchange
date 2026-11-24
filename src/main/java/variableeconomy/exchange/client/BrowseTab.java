package variableeconomy.exchange.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import variableeconomy.exchange.network.ExchangePayloads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static variableeconomy.exchange.client.ExchangeScreen.*;

/**
 * Browse: everything other players offer for sale, sell orders and listings alike, newest first. The search narrows it
 * to matching items; a category, a price range and a smallest quantity narrow it further; it sorts by age, price or
 * quantity. Picking an offer shows it in full (the whole item, as an advanced tooltip shows it), with how many to buy
 * and what that costs; buying asks to confirm, then fills the sell order or buys from the listing. Bought items go to
 * the Collection tab.
 */
final class BrowseTab extends Tab {
    /** How the feed is ordered. Ties keep the server's order, newest first. */
    enum Sort {
        NEWEST(null),
        CHEAPEST(Comparator.comparingLong(ExchangePayloads.OfferLine::price)),
        DEAREST(Comparator.comparingLong(ExchangePayloads.OfferLine::price).reversed()),
        MOST(Comparator.comparingInt(ExchangePayloads.OfferLine::remaining).reversed());

        @Nullable
        final Comparator<ExchangePayloads.OfferLine> order;

        Sort(@Nullable Comparator<ExchangePayloads.OfferLine> order) {
            this.order = order;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }

        Component label() {
            return Component.translatable("screen.variable_economy_exchange.browse.sort." + name().toLowerCase(Locale.ROOT));
        }
    }

    private static final int RAIL_WIDTH = 112;
    private static final int CATEGORY_ROW = 12;
    private static final int FILTER_HEIGHT = 32;

    private Sort sort = Sort.NEWEST;
    /** The category picked, by its config entry; null for every category. */
    @Nullable
    private String category;
    private final NumberField minPrice = new NumberField(Component.translatable("screen.variable_economy_exchange.browse.min_price"), 12, "");
    private final NumberField maxPrice = new NumberField(Component.translatable("screen.variable_economy_exchange.browse.max_price"), 12, "");
    private final NumberField minQuantity = new NumberField(Component.translatable("screen.variable_economy_exchange.browse.min_quantity"), 7, "");
    private final NumberField quantity = new NumberField(Component.translatable("screen.variable_economy_exchange.quantity"), 7, "1");
    /** The offers shown, after the search, the filters and the sort. */
    private final List<ExchangePayloads.OfferLine> shown = new ArrayList<>();
    private final ScrollList list = new ScrollList(shown::size);
    /** The picked offer, by kind and ID (IDs never repeat across orders and listings); null in the list view. */
    @Nullable
    private Long picked;
    private boolean pickedListing;
    /** The picked offer as last seen, so its details still show if it sells out meanwhile. */
    @Nullable
    private ExchangePayloads.OfferLine pickedLine;
    private Button sortButton;
    @Nullable
    private Button categoryButton;
    private Button buyButton;
    // Where the content and the category rail are, from init.
    private int left;
    private int top;
    private int right;
    private int bottom;
    private int railRight;
    /** Where the details' controls column starts. */
    private int controlsX;

    BrowseTab(ExchangeScreen screen) {
        super(screen);
    }

    @Override
    Component label() {
        return Component.translatable("screen.variable_economy_exchange.tab.browse");
    }

    @Override
    boolean inDetails() {
        return picked != null;
    }

    @Override
    void back() {
        picked = null;
        pickedLine = null;
    }

    @Override
    boolean watchesBrowse() {
        return true;
    }

    @Override
    int toolbarWidth() {
        return inDetails() ? 0 : screen.wide() ? 94 : 188;
    }

    @Override
    void initToolbar(int x, int y) {
        if (inDetails()) {
            return;
        }
        sortButton = screen.add(Button.builder(sort.label(), b -> {
                    sort = sort.next();
                    b.setMessage(sort.label());
                    screen.listDirty = true;
                })
                .bounds(x, y, 90, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.browse.sort.tooltip")))
                .build());
        categoryButton = null;
        if (!screen.wide()) {
            // On a narrow window the categories are a button that cycles through them.
            categoryButton = screen.add(Button.builder(categoryLabel(), b -> {
                        cycleCategory();
                        b.setMessage(categoryLabel());
                    })
                    .bounds(x + 94, y, 90, 18)
                    .tooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.browse.category.tooltip")))
                    .build());
        }
    }

    @Override
    void init(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        if (inDetails()) {
            initDetails();
            return;
        }
        railRight = screen.wide() ? left + RAIL_WIDTH : left;
        int listLeft = screen.wide() ? railRight + 6 : left;
        // The filters row: price from, to, and the smallest quantity.
        int fx = listLeft + screen.font().width(Component.translatable("screen.variable_economy_exchange.browse.price")) + 4;
        minPrice.init(screen, fx, top - 2, 50, Component.translatable("screen.variable_economy_exchange.browse.min"));
        maxPrice.init(screen, fx + 62, top - 2, 50, Component.translatable("screen.variable_economy_exchange.browse.max"));
        int qx = fx + 120 + screen.font().width(Component.translatable("screen.variable_economy_exchange.browse.quantity")) + 4;
        if (qx + 40 <= right) {
            minQuantity.init(screen, qx, top - 2, 40, Component.translatable("screen.variable_economy_exchange.browse.min"));
        }
        list.place(listLeft, top + FILTER_HEIGHT, right - listLeft, bottom - top - FILTER_HEIGHT);
    }

    private void initDetails() {
        boolean wide = screen.wide();
        controlsX = wide ? right - 170 : left;
        int controlsTop = wide ? top + 56 : bottom - 56;
        quantity.initStepped(screen, controlsX, controlsTop, this::most, this::most,
                Component.translatable("screen.variable_economy_exchange.browse.all.tooltip"));
        buyButton = screen.add(Button.builder(Component.translatable("screen.variable_economy_exchange.browse.buy"), b -> buy())
                .bounds(controlsX, controlsTop + 24, wide ? 170 : Math.min(200, right - left), 18).build());
    }

    /** The most one purchase from the picked offer may take: what's left, and for a sell order the largest order. */
    private int most() {
        ExchangePayloads.OfferLine offer = pickedLine;
        if (offer == null) {
            return 1;
        }
        return offer.listing() ? offer.remaining() : Math.min(offer.remaining(), TradeChecks.maxOrderSize());
    }

    // ---- The list ----------------------------------------------------------------------------------------------

    private Component categoryLabel() {
        Categories categories = screen.categories();
        if (category == null || categories == null) {
            return Component.translatable("screen.variable_economy_exchange.browse.all_categories");
        }
        return categories.all().stream().filter(c -> c.id().equals(category)).findFirst().map(Categories.Category::name)
                .orElse(Component.translatable("screen.variable_economy_exchange.browse.all_categories"));
    }

    private void cycleCategory() {
        Categories categories = screen.categories();
        if (categories == null || categories.all().isEmpty()) {
            category = null;
            return;
        }
        List<Categories.Category> all = categories.all();
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(category)) {
                index = i;
            }
        }
        category = index + 1 >= all.size() ? null : all.get(index + 1).id();
        list.reset();
        screen.listDirty = true;
    }

    @Override
    void rebuild(String query) {
        shown.clear();
        ExchangePayloads.ExchangeBrowse browse = ClientExchangeState.browse();
        if (browse != null) {
            Categories categories = screen.categories();
            Categories.Category only = categories == null || category == null ? null
                    : categories.all().stream().filter(c -> c.id().equals(category)).findFirst().orElse(null);
            long min = minPrice.value();
            long max = maxPrice.value();
            long fewest = minQuantity.value();
            for (ExchangePayloads.OfferLine offer : browse.offers()) {
                if (!ExchangeScreen.matches(offer.stack(), query)) {
                    continue;
                }
                if (only != null && !categories.contains(only, offer.stack().getItem())) {
                    continue;
                }
                if (min > 0 && offer.price() < min || max > 0 && offer.price() > max || fewest > 0 && offer.remaining() < fewest) {
                    continue;
                }
                shown.add(offer);
            }
            if (sort.order != null) {
                // A stable sort, so ties keep the server's order.
                shown.sort(sort.order);
            }
        }
        list.clamp();
        // Keep the picked offer's details current, or as last seen if it's gone.
        if (picked != null && browse != null) {
            pickedLine = browse.offers().stream().filter(o -> o.id() == picked && o.listing() == pickedListing).findFirst().orElse(pickedLine);
        }
    }

    @Override
    void tick() {
        if (!inDetails()) {
            // The filters apply as they're typed.
            String key = minPrice.value() + "/" + maxPrice.value() + "/" + minQuantity.value();
            if (!key.equals(lastFilters)) {
                lastFilters = key;
                list.reset();
                screen.listDirty = true;
            }
            return;
        }
        ExchangePayloads.OfferLine offer = current();
        int qty = quantity.intValue();
        Component problem = offer == null ? Component.translatable("screen.variable_economy_exchange.browse.gone")
                : TradeChecks.buyOfferProblem(offer, qty);
        long cost = offer == null ? 0 : TradeChecks.costWithTax(offer.price(), Math.max(qty, 0));
        buyButton.active = problem == null;
        buyButton.setMessage(problem == null
                ? Component.translatable("screen.variable_economy_exchange.exchange.buy_for", PriceFormat.amount(qty), PriceFormat.amount(cost))
                : Component.translatable("screen.variable_economy_exchange.browse.buy"));
        buyButton.setTooltip(Tooltip.create(problem != null ? problem
                : Component.translatable("screen.variable_economy_exchange.browse.buy.tooltip", TradeChecks.money(cost))));
    }

    private String lastFilters = "";

    /** The picked offer, if it's still for sale. */
    @Nullable
    private ExchangePayloads.OfferLine current() {
        ExchangePayloads.ExchangeBrowse browse = ClientExchangeState.browse();
        if (picked == null || browse == null) {
            return null;
        }
        return browse.offers().stream().filter(o -> o.id() == picked && o.listing() == pickedListing).findFirst().orElse(null);
    }

    private void buy() {
        ExchangePayloads.OfferLine offer = current();
        int qty = quantity.intValue();
        if (offer == null || TradeChecks.buyOfferProblem(offer, qty) != null) {
            return;
        }
        long value = TradeChecks.checkedTotal(offer.price(), qty).orElse(0);
        long cost = TradeChecks.costWithTax(offer.price(), qty);
        List<Component> lines = List.of(
                Component.translatable("screen.variable_economy_exchange.confirm.buy.what", PriceFormat.amount(qty), offer.stack().getHoverName(),
                        offer.seller()),
                Component.translatable("screen.variable_economy_exchange.confirm.buy.price", TradeChecks.money(offer.price()), TradeChecks.money(value)),
                Component.translatable("screen.variable_economy_exchange.confirm.tax", TradeChecks.taxPercent(), TradeChecks.money(cost - value)),
                Component.translatable("screen.variable_economy_exchange.confirm.total", TradeChecks.money(cost)).withColor(TEXT_MONEY),
                Component.translatable("screen.variable_economy_exchange.confirm.to_collection"));
        screen.confirm(Component.translatable("screen.variable_economy_exchange.confirm.buy.title"), lines,
                Component.translatable("screen.variable_economy_exchange.confirm.buy"), () -> PacketDistributor.sendToServer(offer.listing()
                        ? ExchangePayloads.ExchangeAction.buyListing(offer.id(), qty, offer.price())
                        : ExchangePayloads.ExchangeAction.fill(offer.id(), qty, offer.price())));
    }

    // ---- Input -------------------------------------------------------------------------------------------------

    @Override
    boolean clicked(double mouseX, double mouseY) {
        if (inDetails()) {
            return false;
        }
        if (list.clicked(mouseX, mouseY)) {
            return true;
        }
        int index = list.rowAt(mouseX, mouseY);
        if (index >= 0) {
            screen.click();
            ExchangePayloads.OfferLine offer = shown.get(index);
            picked = offer.id();
            pickedListing = offer.listing();
            pickedLine = offer;
            quantity.set(1);
            ClientExchangeState.clearStatus();
            screen.relayout();
            return true;
        }
        Categories.Category rail = railAt(mouseX, mouseY);
        if (rail != null || railAllAt(mouseX, mouseY)) {
            screen.click();
            category = rail == null ? null : rail.id();
            list.reset();
            screen.listDirty = true;
            return true;
        }
        return false;
    }

    @Override
    boolean scrolled(double mouseX, double mouseY, double amount) {
        return !inDetails() && list.scrolled(mouseX, mouseY, amount);
    }

    @Override
    boolean dragged(double mouseY) {
        return !inDetails() && list.dragged(mouseY);
    }

    @Override
    boolean released() {
        return list.released();
    }

    /** The category rail's rows, below its "All" row; only on a wide window. */
    @Nullable
    private Categories.Category railAt(double mouseX, double mouseY) {
        Categories categories = screen.categories();
        if (!screen.wide() || categories == null || mouseX < left || mouseX >= railRight) {
            return null;
        }
        int row = (int) ((mouseY - railTop()) / CATEGORY_ROW) - 1;
        return row >= 0 && row < categories.all().size() && mouseY >= railTop() ? categories.all().get(row) : null;
    }

    private boolean railAllAt(double mouseX, double mouseY) {
        return screen.wide() && mouseX >= left && mouseX < railRight && mouseY >= railTop() && mouseY < railTop() + CATEGORY_ROW;
    }

    private int railTop() {
        return top + 12;
    }

    // ---- Drawing -----------------------------------------------------------------------------------------------

    @Override
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (inDetails()) {
            renderDetails(graphics);
            return;
        }
        if (screen.wide()) {
            renderRail(graphics, mouseX, mouseY);
        }
        int listLeft = list.left;
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.browse.price"), listLeft, top + 2, TEXT_DIM, false);
        graphics.drawString(screen.font(), "-", listLeft + screen.font().width(Component.translatable("screen.variable_economy_exchange.browse.price")) + 56,
                top + 2, TEXT_DIM, false);
        int qx = listLeft + screen.font().width(Component.translatable("screen.variable_economy_exchange.browse.price")) + 4 + 120;
        if (minQuantity.box != null && minQuantity.box.getX() > qx) {
            graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.browse.quantity"), qx, top + 2, TEXT_DIM, false);
        }
        renderFeed(graphics, mouseX, mouseY);
    }

    private void renderRail(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.browse.categories"), left, top, TEXT_DIM, false);
        Categories categories = screen.categories();
        int y = railTop();
        railRow(graphics, Component.translatable("screen.variable_economy_exchange.browse.all_categories"), category == null, y,
                railAllAt(mouseX, mouseY));
        if (categories == null) {
            return;
        }
        Categories.Category hovered = railAt(mouseX, mouseY);
        for (Categories.Category c : categories.all()) {
            y += CATEGORY_ROW;
            if (y + CATEGORY_ROW > bottom) {
                break;
            }
            railRow(graphics, c.name(), c.id().equals(category), y, c == hovered);
        }
        graphics.fill(railRight + 2, top, railRight + 3, bottom, PANEL_BORDER);
    }

    private void railRow(GuiGraphics graphics, Component name, boolean picked, int y, boolean hovered) {
        if (picked || hovered) {
            graphics.fill(left, y - 1, railRight, y + CATEGORY_ROW - 1, picked ? ROW_SELECTED : ROW_HOVER);
        }
        screen.drawClipped(graphics, name.getString(), left + 3, y + 1, RAIL_WIDTH - 6, picked ? TEXT : TEXT_DIM);
    }

    /** Where the feed's columns end, from the right: age, seller, price, quantity. Narrow lists leave out age and seller. */
    private int[] columns() {
        int edge = list.right() - 2;
        boolean full = list.width >= 330;
        int age = edge;
        int seller = full ? edge - 62 : edge;
        int price = full ? seller - 84 : edge;
        int qty = price - 66;
        return new int[] {qty, price, seller, age};
    }

    private void renderFeed(GuiGraphics graphics, int mouseX, int mouseY) {
        int[] col = columns();
        boolean full = list.width >= 330;
        int headerY = list.top - 10;
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.col.item"), list.left + 22, headerY, TEXT_DIM, false);
        screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.browse.col.available"), col[0], headerY, TEXT_DIM);
        screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.browse.col.each"), col[1], headerY, TEXT_DIM);
        if (full) {
            graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.browse.col.seller"), col[1] + 8, headerY, TEXT_DIM, false);
            screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.browse.col.posted"), col[3], headerY, TEXT_DIM);
        }
        int hovered = list.rowAt(mouseX, mouseY);
        for (int i = list.first(); i < list.end(); i++) {
            ExchangePayloads.OfferLine offer = shown.get(i);
            int rowY = list.rowTop(i);
            int textY = rowY + 5;
            list.rowBackground(graphics, i, false, hovered);
            screen.renderStack(graphics, offer.stack(), list.left + 2, rowY + 1);
            String name = offer.stack().getHoverName().getString();
            screen.drawClipped(graphics, name, list.left + 22, textY, col[0] - 44 - list.left - 22, offer.listing() ? 0xFFE6B3FF : TEXT);
            screen.drawRight(graphics, Component.literal(PriceFormat.count(offer.remaining())), col[0], textY, TEXT);
            screen.drawRight(graphics, Component.literal(PriceFormat.compact(offer.price())), col[1], textY, TEXT_MONEY);
            if (full) {
                Component seller = offer.mine() ? Component.translatable("screen.variable_economy_exchange.exchange.listing.you") : Component.literal(offer.seller());
                screen.drawClipped(graphics, seller.getString(), col[1] + 8, textY, col[2] - col[1] - 12, offer.mine() ? TEXT_GOOD : TEXT_DIM);
                screen.drawRight(graphics, TradeChecks.dayAge(offer.ageDays()), col[3], textY, TEXT_DIM);
            }
        }
        list.renderScrollbar(graphics);
        ExchangePayloads.ExchangeBrowse browse = ClientExchangeState.browse();
        if (shown.isEmpty()) {
            String key = browse == null ? "screen.variable_economy_exchange.trades.loading"
                    : browse.offers().isEmpty() ? "screen.variable_economy_exchange.browse.none" : "screen.variable_economy_exchange.browse.no_matches";
            screen.drawEmpty(graphics, Component.translatable(key), list.left, list.top, list.width);
        } else if (browse != null && browse.more() && list.end() >= shown.size()) {
            graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.browse.more"), list.left + 4,
                    list.top + Math.min(list.rows, shown.size()) * list.rowHeight() + 2, TEXT_DIM, false);
        }
    }

    private void renderDetails(GuiGraphics graphics) {
        ExchangePayloads.OfferLine offer = pickedLine;
        if (offer == null) {
            return;
        }
        boolean wide = screen.wide();
        int infoRight = wide ? controlsX - 10 : right;
        ItemStack stack = offer.stack();
        screen.renderLargeStack(graphics, stack, left, top);
        screen.drawClipped(graphics, stack.getHoverName().getString(), left + 38, top + 4, infoRight - left - 38, offer.listing() ? 0xFFE6B3FF : TEXT);
        screen.drawClipped(graphics, Component.translatable(offer.listing() ? "screen.variable_economy_exchange.browse.kind.listing"
                : "screen.variable_economy_exchange.browse.kind.order").getString(), left + 38, top + 16, infoRight - left - 38, TEXT_DIM);
        // The offer: who sells it, how many, the price, its age and expiry.
        int infoLeft = wide ? controlsX : left;
        int infoTop = wide ? top : top + 38;
        int infoEnd = wide ? right : right;
        Component seller = offer.mine() ? Component.translatable("screen.variable_economy_exchange.exchange.listing.you") : Component.literal(offer.seller());
        screen.infoLine(graphics, Component.translatable("screen.variable_economy_exchange.browse.seller"), seller, TEXT, infoLeft, infoEnd, infoTop);
        screen.infoLine(graphics, Component.translatable("screen.variable_economy_exchange.browse.available"),
                Component.literal(PriceFormat.amount(offer.remaining())), TEXT, infoLeft, infoEnd, infoTop + 10);
        screen.infoLine(graphics, Component.translatable("screen.variable_economy_exchange.browse.price_each"), TradeChecks.money(offer.price()),
                TEXT_MONEY, infoLeft, infoEnd, infoTop + 20);
        screen.infoLine(graphics, Component.translatable("screen.variable_economy_exchange.browse.posted"), TradeChecks.dayAge(offer.ageDays()),
                TEXT_DIM, infoLeft, infoEnd, infoTop + 30);
        screen.infoLine(graphics, Component.translatable("screen.variable_economy_exchange.exchange.order.expires"), TradeChecks.expires(offer.daysLeft()),
                TEXT_DIM, infoLeft, infoEnd, infoTop + 40);
        // The total under the Buy button.
        int qty = quantity.intValue();
        long value = TradeChecks.checkedTotal(offer.price(), Math.max(qty, 0)).orElse(0);
        long cost = TradeChecks.costWithTax(offer.price(), Math.max(qty, 0));
        int totalY = buyButton.getY() + 22;
        if (qty > 0 && value > 0) {
            screen.drawWrapped(graphics, Component.translatable("screen.variable_economy_exchange.browse.total", TradeChecks.money(value),
                    TradeChecks.money(cost - value), TradeChecks.taxPercent(), TradeChecks.money(cost)), wide ? controlsX : left, totalY,
                    wide ? right - controlsX : right - left, bottom, TEXT_DIM);
        }
        // The whole item, to inspect before buying.
        int inspectTop = wide ? top + 38 : infoTop + 54;
        int inspectBottom = wide ? bottom : quantity.box.getY() - 6;
        screen.renderInspection(graphics, stack, left, inspectTop, infoRight - left, inspectBottom);
    }

    @Override
    void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (inDetails()) {
            if (pickedLine != null && mouseX >= left && mouseX < left + 32 && mouseY >= top && mouseY < top + 32) {
                screen.showTooltip(graphics, screen.inspect(pickedLine.stack()), mouseX, mouseY);
            }
            return;
        }
        int index = list.rowAt(mouseX, mouseY);
        if (index >= 0) {
            ExchangePayloads.OfferLine offer = shown.get(index);
            List<Component> extra = new ArrayList<>();
            extra.add(Component.translatable("screen.variable_economy_exchange.exchange.listing.offer", PriceFormat.count(offer.remaining()),
                    TradeChecks.money(offer.price())).withColor(TEXT_MONEY));
            extra.add((offer.mine() ? Component.translatable("screen.variable_economy_exchange.exchange.listing.yours")
                    : Component.translatable("screen.variable_economy_exchange.exchange.listing.by", offer.seller())).withColor(TEXT_DIM));
            extra.add(Component.translatable("screen.variable_economy_exchange.browse.click").withColor(TEXT_DIM));
            screen.showTooltip(graphics, screen.tooltip(offer.stack(), extra), mouseX, mouseY);
        }
    }
}
