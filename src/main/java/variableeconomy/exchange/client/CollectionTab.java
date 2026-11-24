package variableeconomy.exchange.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import variableeconomy.exchange.data.ExchangeData.HistoryKind;
import variableeconomy.exchange.network.ExchangePayloads;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static variableeconomy.exchange.client.ExchangeScreen.*;

/**
 * Collection: what the exchange holds for the player. Items received lists what they bought (from Browse or by a buy
 * order) and what came back from a cancelled, lowered or expired sell order or listing; clicking a line takes it, and
 * Collect all takes everything, as far as the inventory has room (the rest stays). Currency received lists what their
 * sales paid: money is paid straight into the bank account an order belongs to, so there's nothing to collect, only
 * to see.
 */
final class CollectionTab extends Tab {
    private static final int ITEMS = 0;

    /** One line of the box: plain items by ID, or a parcel of an item with data of its own. */
    private record Waiting(ItemStack stack, long count, Optional<Long> parcel) {
    }

    private final List<Waiting> waiting = new ArrayList<>();
    private final List<ExchangePayloads.HistoryLine> sales = new ArrayList<>();
    private final ScrollList list = new ScrollList(() -> subTab == ITEMS ? waiting.size() : sales.size());
    private Button collectAll;
    private int top;
    private int bottom;

    CollectionTab(ExchangeScreen screen) {
        super(screen);
    }

    private static long boxed() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange == null ? 0 : exchange.boxed();
    }

    @Override
    Component label() {
        long boxed = boxed();
        return boxed == 0 ? Component.translatable("screen.variable_economy_exchange.tab.collection")
                : Component.translatable("screen.variable_economy_exchange.tab.collection_count", PriceFormat.count(boxed));
    }

    @Override
    List<Component> subTabs() {
        return List.of(Component.translatable("screen.variable_economy_exchange.collection.items", PriceFormat.count(boxed())),
                Component.translatable("screen.variable_economy_exchange.collection.currency"));
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
        return false;
    }

    @Override
    void back() {
    }

    @Override
    int toolbarWidth() {
        return subTab == ITEMS ? 104 : 0;
    }

    @Override
    void initToolbar(int x, int y) {
        collectAll = null;
        if (subTab == ITEMS) {
            collectAll = screen.add(Button.builder(Component.translatable("screen.variable_economy_exchange.collection.collect_all"),
                            b -> PacketDistributor.sendToServer(ExchangePayloads.ExchangeAction.collect(Optional.empty())))
                    .bounds(x, y, 100, 18)
                    .tooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.exchange.take_all.tooltip")))
                    .build());
        }
    }

    @Override
    void init(int left, int top, int right, int bottom) {
        this.top = top;
        this.bottom = bottom;
        // Under a line saying how it works.
        list.place(left, top + 24, right - left, bottom - top - 24);
    }

    @Override
    void rebuild(String query) {
        waiting.clear();
        sales.clear();
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        if (exchange != null) {
            for (ExchangePayloads.BoxLine line : exchange.box()) {
                ItemStack stack = ExchangeScreen.stack(line.item());
                if (ExchangeScreen.matches(stack, query)) {
                    waiting.add(new Waiting(stack, line.count(), Optional.empty()));
                }
            }
            for (ExchangePayloads.ParcelLine line : exchange.parcels()) {
                if (ExchangeScreen.matches(line.stack(), query)) {
                    waiting.add(new Waiting(line.stack(), line.count(), Optional.of(line.id())));
                }
            }
        }
        ExchangePayloads.ExchangeHistory history = ClientExchangeState.history();
        if (history != null) {
            for (ExchangePayloads.HistoryLine line : history.lines()) {
                if (line.kind() == HistoryKind.SOLD && ExchangeScreen.matches(line.item(), query)) {
                    sales.add(line);
                }
            }
        }
        list.clamp();
    }

    @Override
    void tick() {
        if (collectAll != null) {
            collectAll.active = boxed() > 0;
        }
    }

    @Override
    boolean clicked(double mouseX, double mouseY) {
        if (list.clicked(mouseX, mouseY)) {
            return true;
        }
        int index = list.rowAt(mouseX, mouseY);
        if (index < 0 || subTab != ITEMS) {
            return false;
        }
        screen.click();
        Waiting line = waiting.get(index);
        // Clicking a line takes it; what doesn't fit stays.
        PacketDistributor.sendToServer(line.parcel().isPresent()
                ? ExchangePayloads.ExchangeAction.collectParcel(line.parcel().get())
                : ExchangePayloads.ExchangeAction.collect(Optional.of(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(line.stack().getItem()))));
        return true;
    }

    @Override
    boolean scrolled(double mouseX, double mouseY, double amount) {
        return list.scrolled(mouseX, mouseY, amount);
    }

    @Override
    boolean dragged(double mouseY) {
        return list.dragged(mouseY);
    }

    @Override
    boolean released() {
        return list.released();
    }

    @Override
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        screen.drawWrapped(graphics, Component.translatable(subTab == ITEMS ? "screen.variable_economy_exchange.collection.items_help"
                : "screen.variable_economy_exchange.collection.currency_help"), list.left, top, list.width, top + 20, TEXT_DIM);
        int r = list.right() - 2;
        int hovered = list.rowAt(mouseX, mouseY);
        for (int i = list.first(); i < list.end(); i++) {
            int rowY = list.rowTop(i);
            int textY = rowY + 5;
            list.rowBackground(graphics, i, false, hovered);
            if (subTab == ITEMS) {
                Waiting line = waiting.get(i);
                screen.renderStack(graphics, line.stack(), list.left + 2, rowY + 1);
                Component take = Component.translatable("screen.variable_economy_exchange.collection.take");
                screen.drawRight(graphics, take, r, textY, i == hovered ? TEXT_GOOD : TEXT_DIM);
                Component count = Component.literal(PriceFormat.amount(line.count()));
                int countRight = r - screen.font().width(take) - 12;
                screen.drawRight(graphics, count, countRight, textY, TEXT);
                screen.drawClipped(graphics, line.stack().getHoverName().getString(), list.left + 22, textY,
                        countRight - screen.font().width(count) - 8 - list.left - 22, line.parcel().isPresent() ? 0xFFE6B3FF : TEXT);
            } else {
                ExchangePayloads.HistoryLine line = sales.get(i);
                screen.renderStack(graphics, line.item(), list.left + 2, rowY + 1);
                Component when = TradeChecks.timeAge(line.time());
                screen.drawRight(graphics, when, r, textY, TEXT_DIM);
                Component amount = Component.literal("+" + PriceFormat.amount(line.amount()));
                int amountRight = r - screen.font().width(when) - 10;
                screen.drawRight(graphics, amount, amountRight, textY, TEXT_MONEY);
                Component what = Component.translatable("screen.variable_economy_exchange.collection.sale", PriceFormat.amount(line.units()),
                        line.item().getHoverName(), line.counterparty().isEmpty() ? "?" : line.counterparty());
                screen.drawClipped(graphics, what.getString(), list.left + 22, textY, amountRight - screen.font().width(amount) - 8 - list.left - 22, TEXT);
            }
        }
        list.renderScrollbar(graphics);
        if (list.size() == 0) {
            String key = subTab == ITEMS
                    ? (ClientExchangeState.exchange() == null ? "screen.variable_economy_exchange.trades.loading" : "screen.variable_economy_exchange.exchange.none_box")
                    : (ClientExchangeState.history() == null ? "screen.variable_economy_exchange.trades.loading" : "screen.variable_economy_exchange.collection.no_sales");
            screen.drawEmpty(graphics, Component.translatable(key), list.left, list.top, list.width);
        }
    }

    @Override
    void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int index = list.rowAt(mouseX, mouseY);
        if (index < 0) {
            return;
        }
        if (subTab == ITEMS) {
            Waiting line = waiting.get(index);
            screen.showTooltip(graphics, screen.tooltip(line.stack(), List.of(
                    Component.translatable("screen.variable_economy_exchange.exchange.box.click").withColor(TEXT_DIM))), mouseX, mouseY);
        } else {
            ExchangePayloads.HistoryLine line = sales.get(index);
            screen.showTooltip(graphics, screen.tooltip(line.item(), List.of(OrdersTab.describe(line).copy().withColor(TEXT_DIM))), mouseX, mouseY);
        }
    }
}
