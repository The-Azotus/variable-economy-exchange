package variableeconomy.exchange.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import variableeconomy.exchange.network.ExchangePayloads;
import variableeconomy.exchange.service.ExchangeService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static variableeconomy.exchange.client.ExchangeScreen.*;

/**
 * Exchange: posting buy and sell orders. The Buy sub-tab lists items (those with orders, or with a search every item
 * that matches); the Sell sub-tab lists what the player holds. Picking one shows its book (other players' sell orders
 * above the line, buy orders below) beside the order form: how many, the price each (the most a buy order pays, the
 * least a sell order takes), what the order comes to, and how many units on the book it would trade with now. Orders
 * that cross trade at once, best price first, then oldest, at the waiting order's price; the rest waits on the book.
 * Clicking another player's order on the side the form trades with picks it to fill at its price instead. An item with
 * data of its own (enchanted, damaged, renamed) is listed at a fixed price instead, and shows in Browse.
 */
final class TradeTab extends Tab {
    private static final int BUY = 0;
    /** The most items a search of every item lists, so a one-letter search doesn't list the whole game. */
    private static final int MAX_SEARCH_RESULTS = 300;
    private static final int FORM_WIDTH = 176;

    /** The list: items to buy, or what the player holds to sell (a fresh copy, or an item with data to list). */
    private final List<ItemStack> items = new ArrayList<>();
    private final ScrollList list = new ScrollList(items::size);
    /** The item picked; null in the list view. */
    @Nullable
    private ItemStack picked;
    private final NumberField quantity = new NumberField(Component.translatable("screen.variable_economy_exchange.quantity"), 7, "1");
    private final NumberField price = new NumberField(Component.translatable("screen.variable_economy_exchange.exchange.price"), 12, "");
    /** Another player's order picked on the book, which the button then fills; null when none. */
    @Nullable
    private Long pickedOrder;
    private Button submit;
    /** Where each order of the shown book is drawn, so a click can pick it. */
    private final List<BookRow> bookRows = new ArrayList<>();
    private int left;
    private int top;
    private int right;
    private int bottom;
    /** The form's column and the book's box. */
    private int formX;
    private int formTop;
    private int bookLeft;
    private int bookTop;
    private int bookRight;
    private int bookBottom;

    private record BookRow(int left, int top, int right, int bottom, ExchangeService.BookOrder order, boolean ask) {
        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    TradeTab(ExchangeScreen screen) {
        super(screen);
    }

    @Override
    Component label() {
        return Component.translatable("screen.variable_economy_exchange.tab.exchange");
    }

    @Override
    List<Component> subTabs() {
        return List.of(Component.translatable("screen.variable_economy_exchange.trade.buy"),
                Component.translatable("screen.variable_economy_exchange.trade.sell"));
    }

    @Override
    void selectSubTab(int index) {
        super.selectSubTab(index);
        list.reset();
    }

    private boolean buying() {
        return subTab == BUY;
    }

    /** Whether the picked item is listed at a fixed price rather than ordered: it carries data of its own. */
    private boolean listing() {
        return picked != null && !buying() && ExchangeService.listable(picked);
    }

    private ResourceLocation item() {
        return BuiltInRegistries.ITEM.getKey(picked.getItem());
    }

    @Override
    boolean inDetails() {
        return picked != null;
    }

    @Override
    void back() {
        picked = null;
        pickedOrder = null;
    }

    @Override
    Optional<ResourceLocation> book() {
        return picked != null && !listing() ? Optional.of(item()) : Optional.empty();
    }

    @Override
    void init(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        if (!inDetails()) {
            list.place(left, top + 12, right - left, bottom - top - 12);
            return;
        }
        boolean wide = screen.wide();
        formX = wide ? right - FORM_WIDTH : left;
        formTop = top + 38;
        int fieldX = formX + 64;
        quantity.initStepped(screen, fieldX, formTop, this::quantityLimit, this::all,
                Component.translatable(buying() ? "screen.variable_economy_exchange.trade.all_buy.tooltip" : "screen.variable_economy_exchange.trade.all_sell.tooltip"));
        price.init(screen, fieldX, formTop + 22, 70, Component.translatable("screen.variable_economy_exchange.exchange.price_hint"));
        int submitY = formTop + 44;
        submit = screen.add(Button.builder(Component.empty(), b -> submit())
                .bounds(formX, submitY, wide ? FORM_WIDTH : Math.min(240, right - left), 18).build());
        // The book: beside the form when wide, below its notes when narrow.
        bookLeft = left;
        bookRight = wide ? formX - 12 : right;
        bookTop = wide ? top + 38 : submitY + 62;
        bookBottom = bottom;
    }

    /** The most one request may ask for: one stack for a listing, otherwise the largest order. */
    private int quantityLimit() {
        return listing() ? picked.getMaxStackSize() : TradeChecks.maxOrderSize();
    }

    /** All: buying from a picked order, what it has left; selling, every copy held (up to a picked buy order's want). */
    private int all() {
        ExchangeService.BookOrder order = pickedBookOrder();
        if (listing()) {
            return Math.min(TradeChecks.heldLike(picked), picked.getMaxStackSize());
        }
        if (buying()) {
            return order != null ? order.quantity() : quantity.intValue();
        }
        int held = TradeChecks.freshHeld(item());
        return Math.min(order != null ? Math.min(held, order.quantity()) : held, quantityLimit());
    }

    // ---- The list ----------------------------------------------------------------------------------------------

    @Override
    void rebuild(String query) {
        items.clear();
        if (buying()) {
            ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
            List<ResourceLocation> withOrders = new ArrayList<>();
            if (exchange != null) {
                exchange.summaries().forEach(s -> withOrders.add(s.item()));
            }
            withOrders.removeIf(id -> !ExchangeScreen.matches(id, query));
            List<ResourceLocation> others = new ArrayList<>();
            if (!query.isEmpty()) {
                for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                    if (others.size() >= MAX_SEARCH_RESULTS) {
                        break;
                    }
                    if (!withOrders.contains(id) && ExchangeService.tradeable(BuiltInRegistries.ITEM.get(id)) && ExchangeScreen.matches(id, query)) {
                        others.add(id);
                    }
                }
            }
            Comparator<ResourceLocation> byName = Comparator.comparing(ExchangeScreen::displayName);
            withOrders.sort(byName);
            others.sort(byName);
            withOrders.forEach(id -> items.add(ExchangeScreen.stack(id)));
            others.forEach(id -> items.add(ExchangeScreen.stack(id)));
        } else {
            for (ItemStack stack : TradeChecks.sellable()) {
                if (ExchangeScreen.matches(stack, query)) {
                    items.add(stack);
                }
            }
            items.sort(Comparator.comparing((ItemStack s) -> s.getHoverName().getString()));
        }
        list.clamp();
    }

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

    private void pick(ItemStack stack) {
        picked = stack.copyWithCount(1);
        pickedOrder = null;
        ClientExchangeState.clearStatus();
        quantity.set(1);
        if (listing()) {
            price.clear();
            quantity.set(Math.max(1, Math.min(TradeChecks.heldLike(picked), picked.getMaxStackSize())));
        } else {
            // Start at the price a trade would happen at now: the best sell order to buy, the best buy order to sell.
            ExchangeService.Summary s = summary(item());
            long start = s == null ? 0 : buying() ? (s.bestAsk() > 0 ? s.bestAsk() : s.bestBid()) : (s.bestBid() > 0 ? s.bestBid() : s.bestAsk());
            if (start > 0) {
                price.set(start);
            } else {
                price.clear();
            }
            if (!buying()) {
                quantity.set(Math.max(1, Math.min(TradeChecks.freshHeld(item()), quantityLimit())));
            }
        }
        screen.relayout();
    }

    // ---- The form ----------------------------------------------------------------------------------------------

    /** The other player's order picked on the book, if it's still there, on the side this form trades with. */
    @Nullable
    private ExchangeService.BookOrder pickedBookOrder() {
        if (picked == null || pickedOrder == null || listing()) {
            return null;
        }
        ExchangePayloads.ExchangeBook book = ClientExchangeState.book(item());
        if (book == null) {
            return null;
        }
        for (ExchangeService.BookOrder order : buying() ? book.asks() : book.bids()) {
            if (order.id() == pickedOrder && !order.mine()) {
                return order;
            }
        }
        return null;
    }

    @Override
    void tick() {
        if (!inDetails()) {
            return;
        }
        int qty = quantity.intValue();
        long each = price.value();
        ExchangeService.BookOrder order = pickedBookOrder();
        if (order != null && each != order.price()) {
            // Changing the price lets the picked order go.
            pickedOrder = null;
            order = null;
        }
        Component problem;
        Component message;
        Component tooltip;
        if (listing()) {
            long total = TradeChecks.checkedTotal(each, Math.max(qty, 0)).orElse(0);
            problem = TradeChecks.listProblem(picked, qty, each);
            message = problem == null ? Component.translatable("screen.variable_economy_exchange.exchange.list_for", PriceFormat.amount(qty), PriceFormat.amount(total))
                    : Component.translatable("screen.variable_economy_exchange.exchange.list");
            tooltip = Component.translatable("screen.variable_economy_exchange.exchange.list.tooltip", TradeChecks.money(total));
        } else if (order != null) {
            long value = TradeChecks.checkedTotal(order.price(), Math.max(qty, 0)).orElse(0);
            long cost = TradeChecks.costWithTax(order.price(), Math.max(qty, 0));
            problem = TradeChecks.fillProblem(item(), order, buying(), qty);
            message = Component.translatable(buying() ? "screen.variable_economy_exchange.exchange.fill_buy" : "screen.variable_economy_exchange.exchange.fill_sell",
                    PriceFormat.amount(Math.max(qty, 0)), order.poster());
            tooltip = buying()
                    ? Component.translatable("screen.variable_economy_exchange.exchange.fill_buy.tooltip", order.poster(), TradeChecks.money(cost),
                    TradeChecks.money(cost - value))
                    : Component.translatable("screen.variable_economy_exchange.exchange.fill_sell.tooltip", order.poster(), TradeChecks.money(value));
        } else {
            long total = TradeChecks.checkedTotal(each, Math.max(qty, 0)).orElse(0);
            problem = TradeChecks.postProblem(item(), buying(), qty, each);
            if (buying()) {
                // A buy order's button shows what posting it takes from the account: what it holds, tax included, and the fee.
                long charged = TradeChecks.saturatedAdd(TradeChecks.costWithTax(each, Math.max(qty, 0)), TradeChecks.fee());
                message = problem == null ? Component.translatable("screen.variable_economy_exchange.trade.place_buy", PriceFormat.amount(qty), PriceFormat.amount(charged))
                        : Component.translatable("screen.variable_economy_exchange.trade.place_buy_plain");
                tooltip = Component.translatable("screen.variable_economy_exchange.trade.place_buy.tooltip", TradeChecks.money(charged));
            } else {
                message = problem == null ? Component.translatable("screen.variable_economy_exchange.trade.place_sell", PriceFormat.amount(qty), PriceFormat.amount(total))
                        : Component.translatable("screen.variable_economy_exchange.trade.place_sell_plain");
                tooltip = Component.translatable("screen.variable_economy_exchange.trade.place_sell.tooltip", TradeChecks.money(total));
            }
        }
        submit.active = problem == null;
        submit.setMessage(message);
        submit.setTooltip(Tooltip.create(problem != null ? problem : tooltip));
    }

    private void submit() {
        int qty = quantity.intValue();
        long each = price.value();
        ExchangeService.BookOrder order = pickedBookOrder();
        Component name = picked.getHoverName();
        if (listing()) {
            ItemStack stack = picked.copy();
            if (TradeChecks.slotOf(stack) < 0 || TradeChecks.listProblem(stack, qty, each) != null) {
                return;
            }
            long total = TradeChecks.checkedTotal(each, qty).orElse(0);
            List<Component> lines = new ArrayList<>(List.of(
                    Component.translatable("screen.variable_economy_exchange.confirm.list.what", PriceFormat.amount(qty), name, TradeChecks.money(each)),
                    Component.translatable("screen.variable_economy_exchange.confirm.list.total", TradeChecks.money(total)),
                    Component.translatable("screen.variable_economy_exchange.confirm.list.held")));
            addFee(lines);
            screen.confirm(Component.translatable("screen.variable_economy_exchange.confirm.list.title"), lines,
                    Component.translatable("screen.variable_economy_exchange.confirm.list"),
                    () -> {
                        // The slot it's in when confirmed; the server takes from it first, then from other stacks of the same item.
                        int slot = TradeChecks.slotOf(stack);
                        if (slot >= 0) {
                            PacketDistributor.sendToServer(new ExchangePayloads.ListItem(slot, stack, each, qty));
                        }
                    });
            return;
        }
        ResourceLocation item = item();
        if (order != null) {
            if (TradeChecks.fillProblem(item, order, buying(), qty) != null) {
                return;
            }
            long value = TradeChecks.checkedTotal(order.price(), qty).orElse(0);
            List<Component> lines = new ArrayList<>();
            if (buying()) {
                long cost = TradeChecks.costWithTax(order.price(), qty);
                lines.add(Component.translatable("screen.variable_economy_exchange.confirm.buy.what", PriceFormat.amount(qty), name, order.poster()));
                lines.add(Component.translatable("screen.variable_economy_exchange.confirm.buy.price", TradeChecks.money(order.price()), TradeChecks.money(value)));
                lines.add(Component.translatable("screen.variable_economy_exchange.confirm.tax", TradeChecks.taxPercent(), TradeChecks.money(cost - value)));
                lines.add(Component.translatable("screen.variable_economy_exchange.confirm.total", TradeChecks.money(cost)).withColor(TEXT_MONEY));
                lines.add(Component.translatable("screen.variable_economy_exchange.confirm.to_collection"));
            } else {
                lines.add(Component.translatable("screen.variable_economy_exchange.confirm.sell.what", PriceFormat.amount(qty), name, order.poster()));
                lines.add(Component.translatable("screen.variable_economy_exchange.confirm.sell.get", TradeChecks.money(value)).withColor(TEXT_MONEY));
            }
            long id = order.id();
            long at = order.price();
            screen.confirm(Component.translatable(buying() ? "screen.variable_economy_exchange.confirm.buy.title" : "screen.variable_economy_exchange.confirm.sell.title"),
                    lines, Component.translatable(buying() ? "screen.variable_economy_exchange.confirm.buy" : "screen.variable_economy_exchange.confirm.sell"),
                    () -> PacketDistributor.sendToServer(ExchangePayloads.ExchangeAction.fill(id, qty, at)));
            return;
        }
        if (TradeChecks.postProblem(item, buying(), qty, each) != null) {
            return;
        }
        long value = TradeChecks.checkedTotal(each, qty).orElse(0);
        List<Component> lines = new ArrayList<>();
        if (buying()) {
            long cost = TradeChecks.costWithTax(each, qty);
            lines.add(Component.translatable("screen.variable_economy_exchange.confirm.order.buy", PriceFormat.amount(qty), name, TradeChecks.money(each)));
            lines.add(Component.translatable("screen.variable_economy_exchange.confirm.order.held",
                    TradeChecks.money(TradeChecks.saturatedAdd(cost, TradeChecks.fee()))).withColor(TEXT_MONEY));
            lines.add(Component.translatable("screen.variable_economy_exchange.confirm.order.held_detail", TradeChecks.money(value),
                    TradeChecks.taxPercent(), TradeChecks.money(cost - value)));
            lines.add(Component.translatable("screen.variable_economy_exchange.confirm.order.buy_matching"));
        } else {
            lines.add(Component.translatable("screen.variable_economy_exchange.confirm.order.sell", PriceFormat.amount(qty), name, TradeChecks.money(each)));
            lines.add(Component.translatable("screen.variable_economy_exchange.confirm.order.items_held", PriceFormat.amount(qty)));
            lines.add(Component.translatable("screen.variable_economy_exchange.confirm.order.sell_get", TradeChecks.money(value)).withColor(TEXT_MONEY));
            lines.add(Component.translatable("screen.variable_economy_exchange.confirm.order.sell_matching"));
        }
        addFee(lines);
        boolean buy = buying();
        screen.confirm(Component.translatable(buy ? "screen.variable_economy_exchange.confirm.order.buy_title" : "screen.variable_economy_exchange.confirm.order.sell_title"),
                lines, Component.translatable("screen.variable_economy_exchange.confirm.place"),
                () -> PacketDistributor.sendToServer(ExchangePayloads.ExchangeAction.post(item, buy, each, qty)));
    }

    private static void addFee(List<Component> lines) {
        if (TradeChecks.fee() > 0) {
            lines.add(Component.translatable("screen.variable_economy_exchange.exchange.fee", TradeChecks.money(TradeChecks.fee())));
        }
    }

    /** Units on the book's other side that an order at {@code each} would trade with now (not the player's own). */
    private long crossing(long each) {
        ExchangePayloads.ExchangeBook book = picked == null || listing() ? null : ClientExchangeState.book(item());
        if (book == null || each <= 0) {
            return 0;
        }
        long units = 0;
        for (ExchangeService.BookOrder order : buying() ? book.asks() : book.bids()) {
            if (!order.mine() && (buying() ? order.price() <= each : order.price() >= each)) {
                units += order.quantity();
            }
        }
        return units;
    }

    // ---- Input -------------------------------------------------------------------------------------------------

    @Override
    boolean clicked(double mouseX, double mouseY) {
        if (!inDetails()) {
            if (list.clicked(mouseX, mouseY)) {
                return true;
            }
            int index = list.rowAt(mouseX, mouseY);
            if (index >= 0) {
                screen.click();
                pick(items.get(index));
                return true;
            }
            return false;
        }
        for (BookRow row : bookRows) {
            if (row.contains(mouseX, mouseY)) {
                screen.click();
                ExchangeService.BookOrder order = row.order();
                if (ExchangeScreen.same(pickedOrder, order.id())) {
                    // Clicking the picked order again lets it go.
                    pickedOrder = null;
                    return true;
                }
                // Clicking an order copies its price; another player's on the side this form trades with is picked to fill.
                price.set(order.price());
                if (!order.mine() && row.ask() == buying()) {
                    pickedOrder = order.id();
                    int most = buying() ? order.quantity() : Math.min(order.quantity(), TradeChecks.freshHeld(item()));
                    quantity.set(Math.max(1, Math.min(most, TradeChecks.maxOrderSize())));
                }
                return true;
            }
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

    // ---- Drawing -----------------------------------------------------------------------------------------------

    @Override
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (inDetails()) {
            renderForm(graphics, mouseX, mouseY);
        } else {
            renderList(graphics, mouseX, mouseY);
        }
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        int listRight = list.right();
        int colHave = listRight - 4;
        int colBid = colHave - 44;
        int colAsk = colBid - 56;
        int headerY = top;
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.col.item"), list.left + 22, headerY, TEXT_DIM, false);
        if (buying()) {
            screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.exchange.col.ask"), colAsk, headerY, TEXT_DIM);
        }
        screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.exchange.col.bid"), colBid, headerY, TEXT_DIM);
        screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.col.have"), colHave, headerY, TEXT_DIM);
        int hovered = list.rowAt(mouseX, mouseY);
        for (int i = list.first(); i < list.end(); i++) {
            ItemStack stack = items.get(i);
            int rowY = list.rowTop(i);
            int textY = rowY + 5;
            list.rowBackground(graphics, i, false, hovered);
            screen.renderStack(graphics, stack, list.left + 2, rowY + 1);
            boolean data = !buying() && ExchangeService.listable(stack);
            screen.drawClipped(graphics, stack.getHoverName().getString(), list.left + 22, textY, (buying() ? colAsk - 50 : colBid - 50) - list.left - 22,
                    data ? 0xFFE6B3FF : TEXT);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            ExchangeService.Summary s = data ? null : summary(id);
            if (buying()) {
                boolean asks = s != null && s.bestAsk() > 0;
                screen.drawRight(graphics, Component.literal(asks ? PriceFormat.compact(s.bestAsk()) : "-"), colAsk, textY, asks ? TEXT_MONEY : TEXT_DIM);
            }
            if (data) {
                screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.trade.listing_short"), colBid, textY, TEXT_DIM);
            } else {
                boolean bids = s != null && s.bestBid() > 0;
                screen.drawRight(graphics, Component.literal(bids ? PriceFormat.compact(s.bestBid()) : "-"), colBid, textY, bids ? TEXT_GOOD : TEXT_DIM);
            }
            int held = data ? TradeChecks.heldLike(stack) : TradeChecks.freshHeld(id);
            screen.drawRight(graphics, Component.literal(PriceFormat.count(held)), colHave, textY, held > 0 ? TEXT : TEXT_DIM);
        }
        list.renderScrollbar(graphics);
        if (items.isEmpty()) {
            String key = ClientExchangeState.exchange() == null ? "screen.variable_economy_exchange.trades.loading"
                    : buying() ? (screen.search.getValue().isBlank() ? "screen.variable_economy_exchange.trade.none_buy" : "screen.variable_economy_exchange.no_matches")
                    : (screen.search.getValue().isBlank() ? "screen.variable_economy_exchange.trade.none_sell" : "screen.variable_economy_exchange.no_matches");
            screen.drawEmpty(graphics, Component.translatable(key), list.left, list.top, list.width);
        }
    }

    private void renderForm(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean wide = screen.wide();
        screen.renderLargeStack(graphics, picked, left, top);
        screen.drawClipped(graphics, picked.getHoverName().getString(), left + 38, top + 4, right - left - 38, listing() ? 0xFFE6B3FF : TEXT);
        Component kind = Component.translatable(listing() ? "screen.variable_economy_exchange.trade.kind.listing"
                : buying() ? "screen.variable_economy_exchange.trade.kind.buy" : "screen.variable_economy_exchange.trade.kind.sell");
        int held = listing() ? TradeChecks.heldLike(picked) : TradeChecks.freshHeld(item());
        screen.drawClipped(graphics, kind.getString() + "  ·  " + Component.translatable("screen.variable_economy_exchange.trade.you_have", PriceFormat.amount(held)).getString(),
                left + 38, top + 16, right - left - 38, TEXT_DIM);

        // The form's labels.
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.quantity"), formX, formTop + 5, TEXT_DIM, false);
        graphics.drawString(screen.font(), Component.translatable(listing() ? "screen.variable_economy_exchange.trade.price_each"
                : buying() ? "screen.variable_economy_exchange.trade.max_price" : "screen.variable_economy_exchange.trade.min_price"), formX, formTop + 27, TEXT_DIM, false);

        // What the order comes to, under the button.
        int qty = quantity.intValue();
        long each = price.value();
        ExchangeService.BookOrder order = pickedBookOrder();
        long at = order != null ? order.price() : each;
        long value = qty > 0 && at > 0 ? TradeChecks.checkedTotal(at, qty).orElse(0) : 0;
        int y = formTop + 66;
        int width = wide ? FORM_WIDTH : right - left;
        int notesBottom = wide ? bottom : bookTop - 4;
        if (value > 0) {
            long tax = ExchangeService.tax(value, TradeChecks.taxRate());
            if (listing()) {
                y = note(graphics, Component.translatable("screen.variable_economy_exchange.trade.note.listing_total", TradeChecks.money(value)), y, width, notesBottom, TEXT);
            } else if (buying()) {
                y = note(graphics, Component.translatable("screen.variable_economy_exchange.trade.note.value", TradeChecks.money(value), TradeChecks.taxPercent(),
                        TradeChecks.money(tax)), y, width, notesBottom, TEXT);
                if (order == null) {
                    y = note(graphics, Component.translatable("screen.variable_economy_exchange.trade.note.held",
                            TradeChecks.money(TradeChecks.saturatedAdd(TradeChecks.saturatedAdd(value, tax), TradeChecks.fee()))), y, width, notesBottom, TEXT_MONEY);
                }
            } else {
                y = note(graphics, Component.translatable("screen.variable_economy_exchange.trade.note.get", TradeChecks.money(value)), y, width, notesBottom, TEXT_MONEY);
            }
            if (TradeChecks.fee() > 0 && order == null) {
                y = note(graphics, Component.translatable("screen.variable_economy_exchange.exchange.fee", TradeChecks.money(TradeChecks.fee())), y, width, notesBottom, TEXT_DIM);
            }
        } else {
            y = note(graphics, Component.translatable("screen.variable_economy_exchange.exchange.note.tax", TradeChecks.taxPercent()), y, width, notesBottom, TEXT_DIM);
        }
        if (listing()) {
            note(graphics, Component.translatable("screen.variable_economy_exchange.trade.note.listing"), y, width, notesBottom, TEXT_DIM);
        } else if (order == null && each > 0) {
            long crossing = crossing(each);
            note(graphics, Component.translatable(crossing == 0
                    ? (buying() ? "screen.variable_economy_exchange.trade.note.no_match_buy" : "screen.variable_economy_exchange.trade.note.no_match_sell")
                    : (buying() ? "screen.variable_economy_exchange.trade.note.match_buy" : "screen.variable_economy_exchange.trade.note.match_sell"),
                    PriceFormat.amount(crossing)), y, width, notesBottom, crossing > 0 ? TEXT_GOOD : TEXT_DIM);
        } else if (order != null) {
            note(graphics, Component.translatable("screen.variable_economy_exchange.trade.note.picked", order.poster()), y, width, notesBottom, TEXT_DIM);
        }

        bookRows.clear();
        if (listing()) {
            // A listing has no book: show the item in full instead.
            int inspectTop = wide ? top + 38 : bookTop;
            screen.renderInspection(graphics, picked, bookLeft, inspectTop, bookRight - bookLeft, bookBottom);
        } else {
            renderBook(graphics, mouseX, mouseY);
        }
    }

    private int note(GuiGraphics graphics, Component text, int y, int width, int bottom, int color) {
        return screen.drawWrapped(graphics, text, formX, y, width, bottom, color);
    }

    /**
     * The picked item's book: other players' sell orders above the line with the cheapest nearest it, buy orders
     * below with the highest nearest it, one line each with who posted it.
     */
    private void renderBook(GuiGraphics graphics, int mouseX, int mouseY) {
        int l = bookLeft;
        int r = bookRight;
        int t = bookTop;
        if (bookBottom - t < 40) {
            return;
        }
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.exchange.book.price"), l, t, TEXT_DIM, false);
        screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.exchange.book.units"), r, t, TEXT_DIM);
        ExchangePayloads.ExchangeBook book = ClientExchangeState.book(item());
        if (book == null) {
            graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.trades.loading"), l, t + 12, TEXT_DIM, false);
            return;
        }
        int perSide = Mth.clamp((bookBottom - t - 34) / 20, 1, ExchangePayloads.ExchangeBook.DEPTH);
        int asksTop = t + 12;
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.trade.book.sellers"), l, asksTop, TEXT_OFF, false);
        asksTop += 10;
        int lineY = asksTop + perSide * 10;
        int bidsTop = lineY + 10;
        int shownAsks = Math.min(perSide, book.asks().size());
        for (int j = 0; j < shownAsks; j++) {
            ExchangeService.BookOrder order = book.asks().get(shownAsks - 1 - j);
            bookLine(graphics, order, true, l, r, asksTop + (perSide - shownAsks + j) * 10, TEXT_MONEY, mouseX, mouseY);
        }
        if (book.asks().isEmpty()) {
            graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.exchange.book.no_asks"), l, lineY - 10, TEXT_OFF, false);
        }
        graphics.fill(l, lineY + 4, r, lineY + 5, PANEL_BORDER);
        if (!book.asks().isEmpty() && !book.bids().isEmpty()) {
            Component spread = Component.translatable("screen.variable_economy_exchange.exchange.book.spread",
                    PriceFormat.amount(book.asks().get(0).price() - book.bids().get(0).price()));
            int w = screen.font().width(spread) + 4;
            int middle = (l + r) / 2;
            graphics.fill(middle - w / 2, lineY, middle + w / 2, lineY + 9, PANEL);
            graphics.drawCenteredString(screen.font(), spread, middle, lineY + 1, TEXT_DIM);
        }
        for (int j = 0; j < Math.min(perSide, book.bids().size()); j++) {
            bookLine(graphics, book.bids().get(j), false, l, r, bidsTop + j * 10, TEXT_GOOD, mouseX, mouseY);
        }
        if (book.bids().isEmpty()) {
            graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.exchange.book.no_bids"), l, bidsTop, TEXT_OFF, false);
        }
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.trade.book.buyers"), l, bidsTop + perSide * 10, TEXT_OFF, false);
    }

    /** One order in the book: its price, who posted it (the player's own marked with a star) and its units. */
    private void bookLine(GuiGraphics graphics, ExchangeService.BookOrder order, boolean ask, int l, int r, int y, int color, int mouseX, int mouseY) {
        BookRow row = new BookRow(l, y - 1, r, y + 9, order, ask);
        bookRows.add(row);
        if (ExchangeScreen.same(pickedOrder, order.id())) {
            graphics.fill(row.left(), row.top(), row.right(), row.bottom(), ROW_SELECTED);
        } else if (row.contains(mouseX, mouseY)) {
            graphics.fill(row.left(), row.top(), row.right(), row.bottom(), ROW_HOVER);
        }
        String priceText = PriceFormat.amount(order.price()) + (order.mine() ? " *" : "");
        graphics.drawString(screen.font(), priceText, l, y, color, false);
        String units = PriceFormat.count(order.quantity());
        int nameLeft = l + screen.font().width(priceText) + 6;
        int nameWidth = r - screen.font().width(units) - 6 - nameLeft;
        if (nameWidth > 12 && !order.mine()) {
            screen.drawClipped(graphics, order.poster(), nameLeft, y, nameWidth, TEXT_DIM);
        }
        screen.drawRight(graphics, Component.literal(units), r, y, TEXT);
    }

    @Override
    void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!inDetails()) {
            int index = list.rowAt(mouseX, mouseY);
            if (index >= 0) {
                ItemStack stack = items.get(index);
                List<Component> extra = new ArrayList<>();
                ExchangeService.Summary s = ExchangeService.listable(stack) && !buying() ? null : summary(BuiltInRegistries.ITEM.getKey(stack.getItem()));
                if (!buying() && ExchangeService.listable(stack)) {
                    extra.add(Component.translatable("screen.variable_economy_exchange.trade.listing_tooltip").withColor(TEXT_DIM));
                } else {
                    if (s != null && s.bestAsk() > 0) {
                        extra.add(Component.translatable("screen.variable_economy_exchange.exchange.tooltip.ask", TradeChecks.money(s.bestAsk()),
                                PriceFormat.count(s.askUnits())).withColor(TEXT_MONEY));
                    }
                    if (s != null && s.bestBid() > 0) {
                        extra.add(Component.translatable("screen.variable_economy_exchange.exchange.tooltip.bid", TradeChecks.money(s.bestBid()),
                                PriceFormat.count(s.bidUnits())).withColor(TEXT_GOOD));
                    }
                    if (s == null) {
                        extra.add(Component.translatable("screen.variable_economy_exchange.exchange.tooltip.none").withColor(TEXT_DIM));
                    }
                }
                screen.showTooltip(graphics, screen.tooltip(stack, extra), mouseX, mouseY);
            }
            return;
        }
        if (mouseX >= left && mouseX < left + 32 && mouseY >= top && mouseY < top + 32) {
            screen.showTooltip(graphics, screen.inspect(picked), mouseX, mouseY);
            return;
        }
        for (BookRow row : bookRows) {
            if (row.contains(mouseX, mouseY)) {
                ExchangeService.BookOrder order = row.order();
                List<Component> lines = new ArrayList<>();
                lines.add(Component.translatable(row.ask() ? "screen.variable_economy_exchange.exchange.book.sells" : "screen.variable_economy_exchange.exchange.book.buys",
                        order.mine() ? Component.translatable("screen.variable_economy_exchange.exchange.listing.you").getString() : order.poster(),
                        PriceFormat.count(order.quantity()), PriceFormat.amount(order.price())));
                if (order.daysLeft() >= 0) {
                    lines.add(TradeChecks.days(order.daysLeft()).withColor(TEXT_DIM));
                }
                lines.add(Component.translatable(order.mine() || row.ask() != buying() ? "screen.variable_economy_exchange.exchange.book.click"
                        : row.ask() ? "screen.variable_economy_exchange.exchange.book.click_buy" : "screen.variable_economy_exchange.exchange.book.click_sell")
                        .withColor(TEXT_DIM));
                graphics.renderComponentTooltip(screen.font(), lines, mouseX, mouseY);
                return;
            }
        }
    }
}
