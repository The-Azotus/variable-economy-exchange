package variableeconomy.exchange.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import variableeconomy.exchange.data.ExchangeData.HistoryKind;
import variableeconomy.exchange.data.ExchangeData.Side;
import variableeconomy.exchange.network.ExchangePayloads;
import variableeconomy.exchange.service.ExchangeService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static variableeconomy.exchange.client.ExchangeScreen.*;

/**
 * My Orders: everything the player has posted. Active lists their open buy and sell orders and listings with how far
 * each has filled; picking one shows it in full, with its trades so far, and lets the player cancel it, change its
 * price (which moves it to the back of the queue at the new price, and trades at once if it now crosses) or lower its
 * quantity. Completed lists orders and listings that filled or sold out; History lists everything that happened, most
 * recent first: trades, cancellations, expiries and changes.
 */
final class OrdersTab extends Tab {
    private static final int ACTIVE = 0;
    private static final int COMPLETED = 1;
    private static final int CONTROLS_WIDTH = 170;

    /** One open order or listing, as the Active list shows it. */
    private record Active(long id, Side side, ItemStack stack, long price, int quantity, int filled, long held, long traded, long daysLeft,
                          long ageDays) {
        int remaining() {
            return quantity - filled;
        }
    }

    private final List<Active> active = new ArrayList<>();
    private final List<ExchangePayloads.HistoryLine> lines = new ArrayList<>();
    private final ScrollList list = new ScrollList(() -> subTab == ACTIVE ? active.size() : lines.size());
    /** The open order or listing picked, by ID. */
    @Nullable
    private Long pickedId;
    /** The history line picked (Completed and History), matched again by what it says when the history is resent. */
    @Nullable
    private ExchangePayloads.HistoryLine pickedLine;
    @Nullable
    private Active pickedActive;
    private final NumberField newPrice = new NumberField(Component.translatable("screen.variable_economy_exchange.orders.new_price"), 12, "");
    private final NumberField newQuantity = new NumberField(Component.translatable("screen.variable_economy_exchange.orders.new_quantity"), 7, "");
    private Button cancelButton;
    private Button repriceButton;
    private Button reduceButton;
    private int left;
    private int top;
    private int right;
    private int bottom;
    private int controlsX;

    OrdersTab(ExchangeScreen screen) {
        super(screen);
    }

    @Override
    Component label() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange == null || exchange.open() == 0 ? Component.translatable("screen.variable_economy_exchange.tab.orders")
                : Component.translatable("screen.variable_economy_exchange.tab.orders_count", exchange.open());
    }

    @Override
    List<Component> subTabs() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return List.of(Component.translatable("screen.variable_economy_exchange.orders.active", exchange == null ? 0 : exchange.open(),
                        exchange == null ? 0 : exchange.maxOrders()),
                Component.translatable("screen.variable_economy_exchange.orders.completed"),
                Component.translatable("screen.variable_economy_exchange.orders.history"));
    }

    @Override
    void selectSubTab(int index) {
        super.selectSubTab(index);
        list.reset();
    }

    @Override
    boolean watchesHistory() {
        return true;
    }

    @Override
    boolean inDetails() {
        return pickedId != null || pickedLine != null;
    }

    @Override
    void back() {
        pickedId = null;
        pickedActive = null;
        pickedLine = null;
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
        if (pickedId == null) {
            return;
        }
        // The controls for an open order: beside its details when wide, at the bottom when narrow.
        boolean wide = screen.wide();
        controlsX = wide ? right - CONTROLS_WIDTH : left;
        int y = wide ? top + 38 : bottom - 66;
        int fieldWidth = 64;
        int buttonX = controlsX + fieldWidth + 4;
        int buttonWidth = (wide ? CONTROLS_WIDTH : Math.min(240, right - left)) - fieldWidth - 4;
        newPrice.init(screen, controlsX, y, fieldWidth, Component.translatable("screen.variable_economy_exchange.orders.price_hint"));
        repriceButton = screen.add(Button.builder(Component.translatable("screen.variable_economy_exchange.orders.reprice"), b -> reprice())
                .bounds(buttonX, y, buttonWidth, 18).build());
        newQuantity.init(screen, controlsX, y + 22, fieldWidth, Component.translatable("screen.variable_economy_exchange.orders.quantity_hint"));
        reduceButton = screen.add(Button.builder(Component.translatable("screen.variable_economy_exchange.orders.reduce"), b -> reduce())
                .bounds(buttonX, y + 22, buttonWidth, 18).build());
        cancelButton = screen.add(Button.builder(Component.translatable("screen.variable_economy_exchange.orders.cancel"), b -> cancel())
                .bounds(controlsX, y + 44, wide ? CONTROLS_WIDTH : Math.min(240, right - left), 18).build());
    }

    // ---- Lists -------------------------------------------------------------------------------------------------

    @Override
    void rebuild(String query) {
        active.clear();
        lines.clear();
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        if (exchange != null) {
            for (ExchangePayloads.OrderLine o : exchange.orders()) {
                Active a = new Active(o.id(), o.buy() ? Side.BUY : Side.SELL, ExchangeScreen.stack(o.item()), o.price(), o.quantity(), o.filled(),
                        o.held(), o.traded(), o.daysLeft(), o.ageDays());
                if (ExchangeScreen.matches(a.stack(), query)) {
                    active.add(a);
                }
            }
            for (ExchangePayloads.ListingLine l : exchange.listings()) {
                Active a = new Active(l.id(), Side.LISTING, l.stack(), l.price(), l.quantity(), l.sold(), 0, 0, l.daysLeft(), l.ageDays());
                if (ExchangeScreen.matches(a.stack(), query)) {
                    active.add(a);
                }
            }
            // Newest first: IDs grow.
            active.sort((a, b) -> Long.compare(b.id(), a.id()));
        }
        ExchangePayloads.ExchangeHistory history = ClientExchangeState.history();
        if (history != null && subTab != ACTIVE) {
            for (ExchangePayloads.HistoryLine line : history.lines()) {
                if ((subTab != COMPLETED || line.kind() == HistoryKind.COMPLETED) && ExchangeScreen.matches(line.item(), query)) {
                    lines.add(line);
                }
            }
        }
        list.clamp();
        if (pickedId != null) {
            Active now = exchange == null ? null : findActive(exchange, pickedId);
            if (now != null) {
                pickedActive = now;
            } else if (pickedActive != null && pickedActive.remaining() > 0) {
                // It filled, was cancelled or expired: its details stay as last seen, without its controls' effect.
                pickedActive = new Active(pickedActive.id(), pickedActive.side(), pickedActive.stack(), pickedActive.price(), pickedActive.quantity(),
                        pickedActive.quantity(), 0, pickedActive.traded(), pickedActive.daysLeft(), pickedActive.ageDays());
            }
        }
    }

    @Nullable
    private static Active findActive(ExchangePayloads.Exchange exchange, long id) {
        for (ExchangePayloads.OrderLine o : exchange.orders()) {
            if (o.id() == id) {
                return new Active(o.id(), o.buy() ? Side.BUY : Side.SELL, ExchangeScreen.stack(o.item()), o.price(), o.quantity(), o.filled(),
                        o.held(), o.traded(), o.daysLeft(), o.ageDays());
            }
        }
        for (ExchangePayloads.ListingLine l : exchange.listings()) {
            if (l.id() == id) {
                return new Active(l.id(), Side.LISTING, l.stack(), l.price(), l.quantity(), l.sold(), 0, 0, l.daysLeft(), l.ageDays());
            }
        }
        return null;
    }

    /** Whether the picked open order or listing is still open. */
    private boolean pickedOpen() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return pickedId != null && exchange != null && findActive(exchange, pickedId) != null;
    }

    /** History lines about the order or listing {@code id}, newest first. */
    private static List<ExchangePayloads.HistoryLine> linesFor(long id, Side side) {
        ExchangePayloads.ExchangeHistory history = ClientExchangeState.history();
        List<ExchangePayloads.HistoryLine> found = new ArrayList<>();
        if (history != null && id != 0) {
            for (ExchangePayloads.HistoryLine line : history.lines()) {
                if (line.orderId() == id && line.side() == side) {
                    found.add(line);
                }
            }
        }
        return found;
    }

    /** What a completed order or listing came to: an order's own count, a listing's sales added up. */
    private static long completedTotal(ExchangePayloads.HistoryLine completed) {
        if (completed.side() != Side.LISTING) {
            return completed.amount();
        }
        long total = 0;
        for (ExchangePayloads.HistoryLine line : linesFor(completed.orderId(), Side.LISTING)) {
            if (line.kind() == HistoryKind.SOLD) {
                total = TradeChecks.saturatedAdd(total, line.amount());
            }
        }
        return total;
    }

    // ---- Changing an order -------------------------------------------------------------------------------------

    /** What a buy order would hold for {@code remaining} units at {@code price} each, and their tax, as the server works it out. */
    private static long needed(Active order, long price, long remaining) {
        long rest = TradeChecks.checkedTotal(price, remaining).orElse(Long.MAX_VALUE);
        if (rest == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return TradeChecks.saturatedAdd(rest, ExchangeService.taxOnMore(order.traded(), rest, TradeChecks.taxRate()));
    }

    @Override
    void tick() {
        if (pickedId == null || repriceButton == null) {
            return;
        }
        boolean open = pickedOpen();
        Active order = pickedActive;
        long price = newPrice.value();
        int qty = newQuantity.intValue();
        Component priceProblem = !open ? Component.translatable("screen.variable_economy_exchange.result.order_gone")
                : !ClientExchangeState.enabled() ? Component.translatable("screen.variable_economy_exchange.result.exchange_closed")
                : price <= 0 ? Component.translatable("screen.variable_economy_exchange.result.invalid_price")
                : price == order.price() ? Component.translatable("screen.variable_economy_exchange.orders.same_price")
                : order.side() == Side.BUY && needed(order, price, order.remaining()) - order.held() > ClientExchangeState.balance()
                ? Component.translatable("screen.variable_economy_exchange.result.no_funds") : null;
        repriceButton.active = priceProblem == null;
        repriceButton.setTooltip(Tooltip.create(priceProblem != null ? priceProblem
                : Component.translatable("screen.variable_economy_exchange.orders.reprice.tooltip")));
        Component quantityProblem = !open ? Component.translatable("screen.variable_economy_exchange.result.order_gone")
                : qty <= 0 || qty >= order.remaining()
                ? Component.translatable("screen.variable_economy_exchange.orders.reduce.range", PriceFormat.amount(Math.max(1, order.remaining() - 1)))
                : null;
        reduceButton.active = quantityProblem == null && order.remaining() > 1;
        reduceButton.setTooltip(Tooltip.create(quantityProblem != null ? quantityProblem
                : Component.translatable("screen.variable_economy_exchange.orders.reduce.tooltip")));
        cancelButton.active = open;
        cancelButton.setTooltip(Tooltip.create(!open ? Component.translatable("screen.variable_economy_exchange.result.order_gone")
                : order.side() == Side.BUY ? Component.translatable("screen.variable_economy_exchange.exchange.cancel.money", TradeChecks.money(order.held()))
                : Component.translatable("screen.variable_economy_exchange.exchange.cancel.items", PriceFormat.count(order.remaining()))));
    }

    private void cancel() {
        Active order = pickedActive;
        if (order == null || !pickedOpen()) {
            return;
        }
        Component back = order.side() == Side.BUY
                ? Component.translatable("screen.variable_economy_exchange.confirm.cancel.money", TradeChecks.money(order.held()))
                : Component.translatable("screen.variable_economy_exchange.confirm.cancel.items", PriceFormat.amount(order.remaining()));
        screen.confirm(Component.translatable("screen.variable_economy_exchange.confirm.cancel.title"),
                List.of(Component.translatable("screen.variable_economy_exchange.confirm.cancel.what", sideName(order.side()), order.stack().getHoverName()), back),
                Component.translatable("screen.variable_economy_exchange.confirm.cancel.yes"),
                () -> PacketDistributor.sendToServer(ExchangePayloads.ExchangeAction.cancel(order.id())));
    }

    private void reprice() {
        Active order = pickedActive;
        long price = newPrice.value();
        if (order == null || !repriceButton.active) {
            return;
        }
        List<Component> text = new ArrayList<>();
        text.add(Component.translatable("screen.variable_economy_exchange.confirm.reprice.what", sideName(order.side()), order.stack().getHoverName(),
                TradeChecks.money(order.price()), TradeChecks.money(price)));
        if (order.side() == Side.BUY) {
            long change = needed(order, price, order.remaining()) - order.held();
            text.add(change > 0
                    ? Component.translatable("screen.variable_economy_exchange.confirm.reprice.more", TradeChecks.money(change)).withColor(TEXT_MONEY)
                    : change < 0 ? Component.translatable("screen.variable_economy_exchange.confirm.reprice.back", TradeChecks.money(-change)).withColor(TEXT_GOOD)
                    : Component.translatable("screen.variable_economy_exchange.confirm.reprice.same"));
        }
        if (order.side() != Side.LISTING) {
            text.add(Component.translatable("screen.variable_economy_exchange.confirm.reprice.queue"));
        }
        screen.confirm(Component.translatable("screen.variable_economy_exchange.confirm.reprice.title"), text,
                Component.translatable("screen.variable_economy_exchange.confirm.reprice.yes"),
                () -> PacketDistributor.sendToServer(ExchangePayloads.ExchangeAction.reprice(order.id(), price)));
    }

    private void reduce() {
        Active order = pickedActive;
        int qty = newQuantity.intValue();
        if (order == null || !reduceButton.active) {
            return;
        }
        int off = order.remaining() - qty;
        List<Component> text = new ArrayList<>();
        text.add(Component.translatable("screen.variable_economy_exchange.confirm.reduce.what", sideName(order.side()), order.stack().getHoverName(),
                PriceFormat.amount(order.remaining()), PriceFormat.amount(qty)));
        if (order.side() == Side.BUY) {
            long back = Math.max(0, order.held() - needed(order, order.price(), qty));
            text.add(Component.translatable("screen.variable_economy_exchange.confirm.reprice.back", TradeChecks.money(back)).withColor(TEXT_GOOD));
        } else {
            text.add(Component.translatable("screen.variable_economy_exchange.confirm.cancel.items", PriceFormat.amount(off)).withColor(TEXT_GOOD));
        }
        screen.confirm(Component.translatable("screen.variable_economy_exchange.confirm.reduce.title"), text,
                Component.translatable("screen.variable_economy_exchange.confirm.reduce.yes"),
                () -> PacketDistributor.sendToServer(ExchangePayloads.ExchangeAction.reduce(order.id(), qty)));
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
        if (index < 0) {
            return false;
        }
        screen.click();
        ClientExchangeState.clearStatus();
        if (subTab == ACTIVE) {
            Active order = active.get(index);
            pickedId = order.id();
            pickedActive = order;
            newPrice.set(order.price());
            newQuantity.clear();
        } else {
            pickedLine = lines.get(index);
        }
        screen.relayout();
        return true;
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

    // ---- Words -------------------------------------------------------------------------------------------------

    static Component sideName(Side side) {
        return Component.translatable("screen.variable_economy_exchange.side." + side.name().toLowerCase(Locale.ROOT));
    }

    static int sideColor(Side side) {
        return switch (side) {
            case BUY -> TEXT_BUY;
            case SELL -> TEXT_GOOD;
            case LISTING -> 0xE6B3FF;
        };
    }

    /** What a history line says happened, in a few words. */
    static Component kindName(ExchangePayloads.HistoryLine line) {
        return Component.translatable("screen.variable_economy_exchange.history." + line.kind().name().toLowerCase(Locale.ROOT));
    }

    static int kindColor(HistoryKind kind) {
        return switch (kind) {
            case BOUGHT -> TEXT_BUY;
            case SOLD, COMPLETED -> TEXT_GOOD;
            case CANCELLED, EXPIRED, CLOSED -> TEXT_BAD;
            case REPRICED, REDUCED -> TEXT_MONEY;
        };
    }

    /** One history line in full: what, how many, at what price, for how much, with whom. */
    static Component describe(ExchangePayloads.HistoryLine line) {
        String p = "screen.variable_economy_exchange.history.line.";
        Component item = line.item().getHoverName();
        Component who = Component.literal(line.counterparty().isEmpty() ? "?" : line.counterparty());
        return switch (line.kind()) {
            case BOUGHT -> Component.translatable(p + "bought", PriceFormat.amount(line.units()), item, TradeChecks.money(line.price()),
                    TradeChecks.money(line.amount()), who);
            case SOLD -> Component.translatable(p + "sold", PriceFormat.amount(line.units()), item, TradeChecks.money(line.price()),
                    TradeChecks.money(line.amount()), who);
            case COMPLETED -> Component.translatable(p + "completed", sideName(line.side()), PriceFormat.amount(line.units()), item);
            case CANCELLED, EXPIRED, CLOSED -> line.side() == Side.BUY
                    ? Component.translatable(p + "ended_money", sideName(line.side()), item, TradeChecks.money(line.amount()))
                    : Component.translatable(p + "ended_items", sideName(line.side()), item, PriceFormat.amount(line.units()));
            case REPRICED -> Component.translatable(p + "repriced", sideName(line.side()), item, TradeChecks.money(line.amount()),
                    TradeChecks.money(line.price()));
            case REDUCED -> Component.translatable(p + "reduced", sideName(line.side()), item, PriceFormat.amount(line.units()));
        };
    }

    // ---- Drawing -----------------------------------------------------------------------------------------------

    @Override
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (pickedId != null) {
            renderActive(graphics);
        } else if (pickedLine != null) {
            renderLine(graphics);
        } else if (subTab == ACTIVE) {
            renderActiveList(graphics, mouseX, mouseY);
        } else {
            renderHistoryList(graphics, mouseX, mouseY);
        }
    }

    private void renderActiveList(GuiGraphics graphics, int mouseX, int mouseY) {
        int r = list.right() - 2;
        boolean full = list.width >= 360;
        int colStatus = r;
        int colTotal = full ? r - 70 : r;
        int colPrice = colTotal - (full ? 64 : 0);
        int colProgress = colPrice - 64;
        int colType = colProgress - 72;
        int headerY = top;
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.col.item"), list.left + 22, headerY, TEXT_DIM, false);
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.orders.col.type"), colType, headerY, TEXT_DIM, false);
        screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.orders.col.filled"), colProgress, headerY, TEXT_DIM);
        screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.browse.col.each"), colPrice, headerY, TEXT_DIM);
        if (full) {
            screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.orders.col.total"), colTotal, headerY, TEXT_DIM);
            screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.orders.col.status"), colStatus, headerY, TEXT_DIM);
        }
        int hovered = list.rowAt(mouseX, mouseY);
        for (int i = list.first(); i < list.end(); i++) {
            Active order = active.get(i);
            int rowY = list.rowTop(i);
            int textY = rowY + 5;
            list.rowBackground(graphics, i, false, hovered);
            screen.renderStack(graphics, order.stack(), list.left + 2, rowY + 1);
            screen.drawClipped(graphics, order.stack().getHoverName().getString(), list.left + 22, textY, colType - 4 - list.left - 22, TEXT);
            graphics.drawString(screen.font(), sideName(order.side()), colType, textY, sideColor(order.side()), false);
            // Filled so far, with a bar.
            String progress = PriceFormat.count(order.filled()) + "/" + PriceFormat.count(order.quantity());
            screen.drawRight(graphics, Component.literal(progress), colProgress, textY - 2, order.filled() > 0 ? TEXT : TEXT_DIM);
            progressBar(graphics, colProgress - 40, rowY + 14, 40, order.filled(), order.quantity());
            screen.drawRight(graphics, Component.literal(PriceFormat.compact(order.price())), colPrice, textY, TEXT_MONEY);
            if (full) {
                long total = TradeChecks.checkedTotal(order.price(), order.quantity()).orElse(Long.MAX_VALUE);
                screen.drawRight(graphics, Component.literal(PriceFormat.compact(total)), colTotal, textY, TEXT);
                screen.drawRight(graphics, status(order), colStatus, textY, order.filled() > 0 ? TEXT_GOOD : TEXT_DIM);
            }
        }
        list.renderScrollbar(graphics);
        if (active.isEmpty()) {
            screen.drawEmpty(graphics, Component.translatable(ClientExchangeState.exchange() == null ? "screen.variable_economy_exchange.trades.loading"
                    : "screen.variable_economy_exchange.orders.none_active"), list.left, list.top, list.width);
        }
    }

    /** Open, or partly filled; with the days left when posts expire. */
    private static Component status(Active order) {
        Component state = Component.translatable(order.filled() > 0 ? "screen.variable_economy_exchange.orders.partial"
                : "screen.variable_economy_exchange.orders.open");
        return order.daysLeft() < 0 ? state : Component.translatable("screen.variable_economy_exchange.orders.status_days", state, order.daysLeft());
    }

    private static void progressBar(GuiGraphics graphics, int x, int y, int width, long done, long of) {
        graphics.fill(x, y, x + width, y + 2, 0x40FFFFFF);
        if (of > 0 && done > 0) {
            graphics.fill(x, y, x + (int) Math.max(1, Math.min(width, width * done / of)), y + 2, 0xFF6EE07A);
        }
    }

    private void renderHistoryList(GuiGraphics graphics, int mouseX, int mouseY) {
        int r = list.right() - 2;
        int headerY = top;
        graphics.drawString(screen.font(), Component.translatable(subTab == COMPLETED ? "screen.variable_economy_exchange.orders.completed_title"
                : "screen.variable_economy_exchange.orders.history_title"), list.left + 4, headerY, TEXT_DIM, false);
        screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.orders.col.when"), r, headerY, TEXT_DIM);
        int hovered = list.rowAt(mouseX, mouseY);
        for (int i = list.first(); i < list.end(); i++) {
            ExchangePayloads.HistoryLine line = lines.get(i);
            int rowY = list.rowTop(i);
            int textY = rowY + 5;
            list.rowBackground(graphics, i, false, hovered);
            screen.renderStack(graphics, line.item(), list.left + 2, rowY + 1);
            Component when = TradeChecks.timeAge(line.time());
            int whenWidth = screen.font().width(when);
            Component kind = subTab == COMPLETED ? sideName(line.side()) : kindName(line);
            int kindWidth = 70;
            graphics.drawString(screen.font(), kind, list.left + 22, textY, subTab == COMPLETED ? sideColor(line.side()) : kindColor(line.kind()), false);
            Component text = subTab == COMPLETED
                    ? Component.translatable("screen.variable_economy_exchange.orders.completed_line", PriceFormat.amount(line.units()),
                    line.item().getHoverName(), TradeChecks.money(completedTotal(line)))
                    : describe(line);
            screen.drawClipped(graphics, text.getString(), list.left + 22 + kindWidth, textY, r - whenWidth - 8 - (list.left + 22 + kindWidth), TEXT);
            screen.drawRight(graphics, when, r, textY, TEXT_DIM);
        }
        list.renderScrollbar(graphics);
        ExchangePayloads.ExchangeHistory history = ClientExchangeState.history();
        if (lines.isEmpty()) {
            screen.drawEmpty(graphics, Component.translatable(history == null ? "screen.variable_economy_exchange.trades.loading"
                    : subTab == COMPLETED ? "screen.variable_economy_exchange.orders.none_completed" : "screen.variable_economy_exchange.orders.none_history"),
                    list.left, list.top, list.width);
        } else if (history != null && history.more() && list.end() >= lines.size()) {
            graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.orders.more"), list.left + 4,
                    list.top + Math.min(list.rows, lines.size()) * list.rowHeight() + 2, TEXT_DIM, false);
        }
    }

    /** An open order or listing in full, its trades so far, and its controls. */
    private void renderActive(GuiGraphics graphics) {
        Active order = pickedActive;
        if (order == null) {
            return;
        }
        boolean wide = screen.wide();
        int infoRight = wide ? controlsX - 12 : right;
        screen.renderLargeStack(graphics, order.stack(), left, top);
        screen.drawClipped(graphics, order.stack().getHoverName().getString(), left + 38, top + 4, infoRight - left - 38, TEXT);
        graphics.drawString(screen.font(), sideName(order.side()), left + 38, top + 16, sideColor(order.side()), false);
        boolean open = pickedOpen();
        if (!open) {
            screen.drawRight(graphics, Component.translatable("screen.variable_economy_exchange.orders.closed"), infoRight, top + 16, TEXT_DIM);
        }
        int y = top + 38;
        String p = "screen.variable_economy_exchange.orders.";
        screen.infoLine(graphics, Component.translatable(p + "price"), TradeChecks.money(order.price()), TEXT_MONEY, left, infoRight, y);
        screen.infoLine(graphics, Component.translatable(p + "ordered"), Component.literal(PriceFormat.amount(order.quantity())), TEXT, left, infoRight, y + 10);
        screen.infoLine(graphics, Component.translatable(order.side() == Side.LISTING ? p + "sold" : p + "filled"),
                Component.literal(PriceFormat.amount(order.filled())), TEXT, left, infoRight, y + 20);
        screen.infoLine(graphics, Component.translatable(p + "remaining"), Component.literal(PriceFormat.amount(order.remaining())), TEXT, left, infoRight, y + 30);
        progressBar(graphics, left, y + 41, infoRight - left, order.filled(), order.quantity());
        Component holds = order.side() == Side.BUY ? TradeChecks.money(order.held())
                : Component.translatable("screen.variable_economy_exchange.exchange.order.items", PriceFormat.count(order.remaining()));
        screen.infoLine(graphics, Component.translatable(p + "holds"), holds, TEXT, left, infoRight, y + 46);
        long total = TradeChecks.checkedTotal(order.price(), order.quantity()).orElse(Long.MAX_VALUE);
        screen.infoLine(graphics, Component.translatable(p + "total"), TradeChecks.money(total), TEXT, left, infoRight, y + 56);
        screen.infoLine(graphics, Component.translatable("screen.variable_economy_exchange.browse.posted"), TradeChecks.dayAge(order.ageDays()), TEXT_DIM,
                left, infoRight, y + 66);
        screen.infoLine(graphics, Component.translatable("screen.variable_economy_exchange.exchange.order.expires"), TradeChecks.expires(order.daysLeft()),
                TEXT_DIM, left, infoRight, y + 76);
        // Its trades and changes so far.
        int historyTop = y + 92;
        int historyBottom = wide ? bottom : (newPrice.box != null ? newPrice.box.getY() - 6 : bottom);
        renderOrderHistory(graphics, order.id(), order.side(), left, historyTop, infoRight, historyBottom);
        if (wide && open) {
            graphics.drawString(screen.font(), Component.translatable(p + "change"), controlsX, top + 26, TEXT_DIM, false);
            screen.drawWrapped(graphics, Component.translatable(order.side() == Side.LISTING ? p + "change_listing" : p + "change_help"),
                    controlsX, top + 106, CONTROLS_WIDTH, bottom, TEXT_DIM);
        }
    }

    /** The lines of history about one order, newest first, between {@code top} and {@code bottom}. */
    private void renderOrderHistory(GuiGraphics graphics, long id, Side side, int l, int t, int r, int b) {
        if (b - t < 20) {
            return;
        }
        graphics.drawString(screen.font(), Component.translatable("screen.variable_economy_exchange.orders.trades"), l, t, TEXT_DIM, false);
        List<ExchangePayloads.HistoryLine> found = linesFor(id, side);
        int y = t + 12;
        if (found.isEmpty()) {
            graphics.drawString(screen.font(), Component.translatable(ClientExchangeState.history() == null ? "screen.variable_economy_exchange.trades.loading"
                    : "screen.variable_economy_exchange.orders.no_trades"), l, y, TEXT_OFF, false);
            return;
        }
        for (ExchangePayloads.HistoryLine line : found) {
            if (y + 9 > b) {
                break;
            }
            Component when = TradeChecks.timeAge(line.time());
            screen.drawClipped(graphics, describe(line).getString(), l, y, r - l - screen.font().width(when) - 6, kindColor(line.kind()));
            screen.drawRight(graphics, when, r, y, TEXT_OFF);
            y += 10;
        }
    }

    /** One history line in full, with the rest of its order's history. */
    private void renderLine(GuiGraphics graphics) {
        ExchangePayloads.HistoryLine line = pickedLine;
        screen.renderLargeStack(graphics, line.item(), left, top);
        screen.drawClipped(graphics, line.item().getHoverName().getString(), left + 38, top + 4, right - left - 38, TEXT);
        graphics.drawString(screen.font(), kindName(line), left + 38, top + 16, kindColor(line.kind()), false);
        int y = top + 38;
        String p = "screen.variable_economy_exchange.orders.";
        screen.infoLine(graphics, Component.translatable(p + "kind"), sideName(line.side()), sideColor(line.side()), left, right, y);
        screen.infoLine(graphics, Component.translatable(p + "units"), Component.literal(PriceFormat.amount(line.units())), TEXT, left, right, y + 10);
        screen.infoLine(graphics, Component.translatable(p + "price"), TradeChecks.money(line.price()), TEXT_MONEY, left, right, y + 20);
        int next = y + 30;
        switch (line.kind()) {
            case BOUGHT -> {
                screen.infoLine(graphics, Component.translatable(p + "paid"), TradeChecks.money(line.amount()), TEXT_MONEY, left, right, next);
                screen.infoLine(graphics, Component.translatable(p + "tax"), TradeChecks.money(line.tax()), TEXT_DIM, left, right, next + 10);
                screen.infoLine(graphics, Component.translatable(p + "from"), Component.literal(line.counterparty()), TEXT, left, right, next + 20);
                next += 30;
            }
            case SOLD -> {
                screen.infoLine(graphics, Component.translatable(p + "received"), TradeChecks.money(line.amount()), TEXT_GOOD, left, right, next);
                screen.infoLine(graphics, Component.translatable(p + "to"), Component.literal(line.counterparty()), TEXT, left, right, next + 10);
                next += 20;
            }
            case COMPLETED -> {
                screen.infoLine(graphics, Component.translatable(p + "came_to"), TradeChecks.money(completedTotal(line)), TEXT, left, right, next);
                next += 10;
            }
            case CANCELLED, EXPIRED, CLOSED, REDUCED -> {
                if (line.side() == Side.BUY && line.amount() > 0) {
                    screen.infoLine(graphics, Component.translatable(p + "returned"), TradeChecks.money(line.amount()), TEXT_GOOD, left, right, next);
                    next += 10;
                }
            }
            case REPRICED -> {
                screen.infoLine(graphics, Component.translatable(p + "old_price"), TradeChecks.money(line.amount()), TEXT_DIM, left, right, next);
                next += 10;
            }
        }
        screen.infoLine(graphics, Component.translatable(p + "when"), TradeChecks.timeAge(line.time()), TEXT_DIM, left, right, next);
        screen.drawWrapped(graphics, describe(line), left, next + 14, right - left, next + 34, TEXT);
        if (line.orderId() != 0) {
            renderOrderHistory(graphics, line.orderId(), line.side(), left, next + 38, right, bottom);
        }
    }

    @Override
    void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (inDetails()) {
            ItemStack stack = pickedActive != null && pickedId != null ? pickedActive.stack() : pickedLine != null ? pickedLine.item() : ItemStack.EMPTY;
            if (!stack.isEmpty() && mouseX >= left && mouseX < left + 32 && mouseY >= top && mouseY < top + 32) {
                screen.showTooltip(graphics, screen.inspect(stack), mouseX, mouseY);
            }
            return;
        }
        int index = list.rowAt(mouseX, mouseY);
        if (index < 0) {
            return;
        }
        if (subTab == ACTIVE) {
            Active order = active.get(index);
            screen.showTooltip(graphics, screen.tooltip(order.stack(), List.of(status(order).copy().withColor(TEXT_DIM),
                    Component.translatable("screen.variable_economy_exchange.orders.click").withColor(TEXT_DIM))), mouseX, mouseY);
        } else {
            ExchangePayloads.HistoryLine line = lines.get(index);
            screen.showTooltip(graphics, screen.tooltip(line.item(), List.of(describe(line).copy().withColor(TEXT_DIM))), mouseX, mouseY);
        }
    }
}
