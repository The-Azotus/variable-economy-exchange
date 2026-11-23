package variableeconomy.exchange.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.lwjgl.glfw.GLFW;
import variableeconomy.exchange.menu.ExchangeMenu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The exchange screen: trading with other players. The frame holds the layout, the search box, the quantity controls,
 * scrolling and the status line; {@link ExchangeView} holds the list, the panel and the exchange's own controls.
 *
 * <p>The screen sizes itself to the window. On a wide window the panel sits beside the list; on a narrow one it sits
 * below. The list shows as many rows as fit.
 */
public final class ExchangeScreen extends AbstractContainerScreen<ExchangeMenu> {
    /** The largest the screen grows to, so rows stay readable on a big window at GUI scale 1. */
    static final int MAX_WIDTH = 600;
    static final int MAX_HEIGHT = 400;
    /** Below this width the panel goes under the list instead of beside it. */
    static final int WIDE_MIN_WIDTH = 440;
    static final int SIDE_WIDTH = 150;
    static final int MARGIN = 8;
    static final int TITLE_Y = 4;
    static final int TITLE_HEIGHT = 14;
    static final int TOOLBAR_Y = 22;
    static final int HEADER_Y = 42;
    static final int LIST_X = MARGIN;
    static final int LIST_Y = 52;
    static final int ROW_HEIGHT = 18;
    static final int SCROLLBAR_WIDTH = 4;
    /** The narrow layout's panel: the selection, the quantity controls, the buttons. */
    static final int BOTTOM_PANEL_HEIGHT = 62;
    /** Room left under the list and panel for the status line. */
    static final int STATUS_HEIGHT = 16;
    /** The wide panel's label/value lines. */
    static final int INFO_LINES = 5;
    static final int INFO_LINE_HEIGHT = 10;
    static final int MIN_SIDE_CHART_HEIGHT = 24;
    static final int MAX_SIDE_CHART_HEIGHT = 80;
    static final int NARROW_CHART_WIDTH = 118;
    static final int NARROW_CHART_HEIGHT = 16;
    /** Width of the quantity controls: -, the box, +, and All. */
    static final int CONTROLS_WIDTH = 132;

    static final int PANEL = 0xF0202024;
    static final int PANEL_BORDER = 0xFF5A5A64;
    static final int ROW_ALT = 0x18FFFFFF;
    static final int ROW_HOVER = 0x30FFFFFF;
    static final int ROW_SELECTED = 0x50FFFFFF;
    static final int TEXT = 0xFFFFFF;
    static final int TEXT_DIM = 0xA0A0A8;
    static final int TEXT_OFF = 0x606068;
    static final int TEXT_GOOD = 0x6EE07A;
    static final int TEXT_BAD = 0xFF6B6B;
    static final int TEXT_MONEY = 0xF2C94C;

    /** Lower-case mod display names by namespace, for {@code @mod} searches. The mod list never changes in a session. */
    private static final Map<String, String> MOD_NAMES = new HashMap<>();

    EditBox search;
    EditBox quantity;
    /** The quantity controls, shown only in the views that use them. */
    private final List<AbstractWidget> quantityControls = new ArrayList<>();
    private final ExchangeView view = new ExchangeView(this);

    // The layout, worked out from the window size in init(). Positions are relative to leftPos/topPos.
    boolean wide;
    /** Whether the balance fits beside the title; if not it goes at the end of the toolbar, abbreviated. */
    private boolean balanceInTitleRow;
    int listWidth;
    int rows;
    // Right edges of the numeric columns.
    int colBuy;
    int colSell;
    int colHave;
    int panelX;
    int panelY;
    int panelWidth;
    int chartX;
    int chartY;
    int chartWidth;
    int chartHeight;
    int controlsY;
    /** Top of the action buttons: one above the other when wide, side by side when narrow. */
    int tradeY;
    int noteX;
    int noteY;
    int noteWidth;
    int statusY;
    /** The price box: above the quantity controls when wide, beside them when narrow. */
    int priceX;
    int priceY;
    int priceWidth;
    /** The Listings view's Sort button: inside the panel, clear of the notes and the frame. */
    int sortX;
    int sortY;
    int sortWidth;

    private int seenVersion = -1;
    private String seenQuery = "";
    boolean listDirty = true;
    int scroll;
    /** Whether the scrollbar's thumb is being dragged. */
    private boolean draggingScrollbar;

    /** A short explanation under the quantity, set by All; it lasts until the quantity changes. */
    Component quantityNote = Component.empty();
    int quantityNoteFor = -1;

    public ExchangeScreen(ExchangeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    // The view lives outside the screen's class hierarchy, so it reaches its protected parts through these.

    net.minecraft.client.gui.Font font() {
        return font;
    }

    net.minecraft.client.Minecraft minecraft() {
        return minecraft;
    }

    int leftPos() {
        return leftPos;
    }

    int topPos() {
        return topPos;
    }

    <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
        return addRenderableWidget(widget);
    }

    @Override
    protected void init() {
        imageWidth = Math.min(MAX_WIDTH, width - MARGIN);
        imageHeight = Math.min(MAX_HEIGHT, height - MARGIN);
        super.init();
        layout();
        int x = leftPos;
        int y = topPos;

        // init() runs again on every resize; keep what the player typed.
        String searchText = search != null ? search.getValue() : "";
        String quantityText = quantity != null ? quantity.getValue() : "1";
        quantityControls.clear();

        int toolbarEnd = (wide ? panelX - MARGIN : imageWidth - MARGIN) - (balanceInTitleRow ? 0 : balanceReserve() + 4);
        int searchWidth = Mth.clamp(toolbarEnd - LIST_X - 104, 60, 160);
        search = new EditBox(font, x + LIST_X, y + TOOLBAR_Y + 1, searchWidth, 14, Component.translatable("screen.variable_economy_exchange.search"));
        search.setHint(Component.translatable("screen.variable_economy_exchange.search").withColor(TEXT_DIM));
        search.setTooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.search.tooltip")));
        search.setMaxLength(50);
        search.setValue(searchText);
        addRenderableWidget(search);

        int controlsX = wide ? panelX : MARGIN;
        quantityControls.add(addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustQuantity(-step()))
                .bounds(x + controlsX, y + controlsY, 16, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.step_hint")))
                .build()));
        quantity = new EditBox(font, x + controlsX + 18, y + controlsY + 2, 40, 14, Component.translatable("screen.variable_economy_exchange.quantity"));
        quantity.setFilter(s -> s.isEmpty() || (s.length() <= 7 && s.chars().allMatch(Character::isDigit)));
        quantity.setValue(quantityText);
        quantityControls.add(addRenderableWidget(quantity));
        quantityControls.add(addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustQuantity(step()))
                .bounds(x + controlsX + 60, y + controlsY, 16, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.step_hint")))
                .build()));
        quantityControls.add(addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy_exchange.all"), b -> view.fillAll())
                .bounds(x + controlsX + 78, y + controlsY, 24, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.all.tooltip")))
                .build()));

        view.init(x, y, LIST_X + searchWidth + 2);
        updateListButtons();
        seenVersion = -1;
        scroll = Mth.clamp(scroll, 0, maxScroll());
        view.refreshButtons();
    }

    /** Where an action button goes: the first ({@code first}) above the second when wide, side by side when narrow. */
    int[] buttonBounds(boolean first) {
        if (wide) {
            return new int[] {leftPos + panelX, topPos + tradeY + (first ? 0 : 20), panelWidth};
        }
        int half = (panelWidth - 2) / 2;
        return new int[] {leftPos + MARGIN + (first ? 0 : panelWidth - half), topPos + tradeY, half};
    }

    /** Works out where everything goes for the current screen size. */
    private void layout() {
        int right = imageWidth - MARGIN;
        int bottom = imageHeight - STATUS_HEIGHT;
        statusY = imageHeight - 12;
        balanceInTitleRow = MARGIN + font.width(title) + 16 + font.width(balanceText(widestBalance(), false)) <= right;

        // Wide: the panel is a column on the right, stacked from the top.
        wide = imageWidth >= WIDE_MIN_WIDTH;
        if (wide) {
            panelWidth = SIDE_WIDTH;
            panelX = right - SIDE_WIDTH;
            panelY = HEADER_Y;
            chartX = panelX;
            chartY = panelY + 20;
            chartWidth = panelWidth;
            // Under the book: 4 gap, the info lines, 4 gap, controls (21), note (11), the two buttons (38).
            int below = 4 + INFO_LINES * INFO_LINE_HEIGHT + 4 + 21 + 11 + 38;
            chartHeight = Math.min(MAX_SIDE_CHART_HEIGHT, bottom - below - chartY);
            int infoY = chartY + chartHeight + 4;
            controlsY = infoY + INFO_LINES * INFO_LINE_HEIGHT + 4;
            noteX = panelX;
            noteY = controlsY + 21;
            noteWidth = panelWidth;
            tradeY = noteY + 11;
            wide = chartHeight >= MIN_SIDE_CHART_HEIGHT;
        }
        if (wide) {
            listWidth = panelX - MARGIN - SCROLLBAR_WIDTH - 2 - LIST_X;
            rows = Math.max(1, (bottom - LIST_Y) / ROW_HEIGHT);
            priceWidth = 70;
            priceX = panelX + panelWidth - priceWidth;
            priceY = controlsY - 18;
            sortWidth = 90;
            sortX = panelX + panelWidth - sortWidth;
            sortY = priceY - 1;
        } else {
            panelX = MARGIN;
            panelWidth = right - MARGIN;
            panelY = bottom - BOTTOM_PANEL_HEIGHT;
            chartWidth = Math.min(NARROW_CHART_WIDTH, panelWidth / 2);
            chartHeight = NARROW_CHART_HEIGHT;
            chartX = right - chartWidth;
            chartY = panelY;
            controlsY = panelY + 20;
            tradeY = panelY + 40;
            noteX = MARGIN + CONTROLS_WIDTH + 4;
            noteY = controlsY + 5;
            noteWidth = right - noteX;
            listWidth = right - SCROLLBAR_WIDTH - 2 - LIST_X;
            rows = Math.max(1, (panelY - 4 - LIST_Y) / ROW_HEIGHT);
            priceX = noteX + 10;
            priceY = controlsY + 2;
            priceWidth = Math.min(70, right - priceX);
            // On the panel's first row, right-aligned; the selected listing's summary there is shortened to make room.
            sortWidth = Math.min(90, panelWidth / 3);
            sortX = right - sortWidth;
            sortY = panelY - 1;
        }
        int listRight = LIST_X + listWidth;
        colHave = listRight - 13;
        colSell = colHave - 34;
        colBuy = colSell - 42;
    }

    // ---- State -----------------------------------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (listDirty || seenVersion != ClientExchangeState.version() || !query.equals(seenQuery)) {
            listDirty = false;
            seenVersion = ClientExchangeState.version();
            seenQuery = query;
            view.rebuildList(query);
            scroll = Mth.clamp(scroll, 0, maxScroll());
        }
        view.refreshButtons();
    }

    /** Shows the controls the current view uses, and moves focus off any box that's now hidden. */
    void updateListButtons() {
        search.visible = view.showsSearch();
        boolean quantityShown = view.showsQuantity();
        quantityControls.forEach(widget -> widget.visible = quantityShown);
        view.updateWidgets();
        GuiEventListener focused = getFocused();
        if (focused == search && !search.visible || focused == quantity && !quantityShown || view.hides(focused)) {
            setFocused(null);
        }
    }

    /** Every space-separated term must match: {@code @mod}, {@code #tag}, or a plain word (see {@link #matchesTerm}). */
    static boolean matches(ResourceLocation item, String query) {
        for (String term : query.split("\\s+")) {
            if (!term.isEmpty() && !matchesTerm(item, term)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code @create} matches the mod's display name or ID; {@code #ingots} matches a tag's path, and {@code #c:ingots}
     * the full tag ID. A plain word matches the display name or the ID's path. The namespace only counts when the
     * word contains ':', or every vanilla item would match "mine".
     */
    private static boolean matchesTerm(ResourceLocation item, String term) {
        if (term.startsWith("@")) {
            String mod = term.substring(1);
            return item.getNamespace().contains(mod) || modName(item.getNamespace()).contains(mod);
        }
        if (term.startsWith("#")) {
            String tag = term.substring(1);
            boolean full = tag.contains(":");
            return BuiltInRegistries.ITEM.wrapAsHolder(BuiltInRegistries.ITEM.get(item)).tags()
                    .anyMatch(key -> (full ? key.location().toString() : key.location().getPath()).contains(tag));
        }
        if (term.contains(":")) {
            return item.toString().contains(term);
        }
        return item.getPath().contains(term) || displayName(item).toLowerCase(Locale.ROOT).contains(term);
    }

    /** A mod's display name in lower case, or "" for a namespace no loaded mod owns. */
    private static String modName(String namespace) {
        return MOD_NAMES.computeIfAbsent(namespace, ns -> ModList.get().getModContainerById(ns)
                .map(container -> container.getModInfo().getDisplayName().toLowerCase(Locale.ROOT))
                .orElse(""));
    }

    int quantityValue() {
        try {
            return Math.max(0, Integer.parseInt(quantity.getValue()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int step() {
        return hasShiftDown() ? 16 : 1;
    }

    private void adjustQuantity(int delta) {
        setQuantity(Mth.clamp(quantityValue() + delta, 1, view.quantityLimit()), Component.empty());
    }

    void setQuantity(int value, Component note) {
        quantity.setValue(String.valueOf(value));
        quantityNote = note;
        quantityNoteFor = value;
    }

    @Override
    public void removed() {
        super.removed();
        ClientExchangeState.clear();
    }

    // ---- Input -----------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && isOverScrollbar(mouseX, mouseY)) {
            // A click on the track jumps there; holding the button drags the thumb.
            draggingScrollbar = true;
            scrollTo(mouseY);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int index = rowAt(mouseX, mouseY);
            if (index >= 0 && view.rowClicked(index)) {
                return true;
            }
            if (view.panelClicked(mouseX, mouseY)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isOverList(mouseX, mouseY)) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Keep typing in a text box from triggering the inventory key, which would close the screen.
        if (keyCode != GLFW.GLFW_KEY_ESCAPE && getFocused() instanceof EditBox box && box.canConsumeInput()) {
            // Enter leaves a number box: every box but the search.
            if (keyCode == GLFW.GLFW_KEY_ENTER && box != search) {
                setFocused(null);
                return true;
            }
            box.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean isOverList(double mouseX, double mouseY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        return x >= LIST_X && x < LIST_X + listWidth + 2 + SCROLLBAR_WIDTH && y >= LIST_Y && y < LIST_Y + rows * ROW_HEIGHT;
    }

    int rowAt(double mouseX, double mouseY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        if (x < LIST_X || x >= LIST_X + listWidth || y < LIST_Y || y >= LIST_Y + rows * ROW_HEIGHT) {
            return -1;
        }
        int index = scroll + (int) ((y - LIST_Y) / ROW_HEIGHT);
        return index < view.listSize() ? index : -1;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScrollbar && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Whether the mouse is on the scrollbar of a list long enough to scroll. */
    private boolean isOverScrollbar(double mouseX, double mouseY) {
        double x = mouseX - leftPos - (LIST_X + listWidth + 2);
        double y = mouseY - topPos - LIST_Y;
        return maxScroll() > 0 && x >= -1 && x < SCROLLBAR_WIDTH + 1 && y >= 0 && y < rows * ROW_HEIGHT;
    }

    /** Scrolls so the thumb's middle sits at {@code mouseY}, as far as the list allows. */
    private void scrollTo(double mouseY) {
        int height = rows * ROW_HEIGHT;
        int thumb = thumbHeight();
        if (height <= thumb || maxScroll() == 0) {
            return;
        }
        double fraction = (mouseY - topPos - LIST_Y - thumb / 2.0) / (height - thumb);
        scroll = Mth.clamp((int) Math.round(fraction * maxScroll()), 0, maxScroll());
    }

    /** The scrollbar thumb's height: the share of the list that shows, at least 12 pixels. */
    private int thumbHeight() {
        int height = rows * ROW_HEIGHT;
        return Math.min(height, Math.max(12, height * rows / Math.max(1, view.listSize())));
    }

    private int maxScroll() {
        return Math.max(0, view.listSize() - rows);
    }

    // ---- Rendering -------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        view.renderTooltip(graphics, rowAt(mouseX, mouseY), mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        graphics.drawString(font, title, x + MARGIN, y + TITLE_Y + 3, TEXT, false);
        graphics.fill(x + MARGIN, y + TITLE_Y + TITLE_HEIGHT, x + imageWidth - MARGIN, y + TITLE_Y + TITLE_HEIGHT + 1, PANEL_BORDER);
        view.renderList(graphics, x, y, mouseX, mouseY);
        renderScrollbar(graphics, x, y);
        view.renderPanel(graphics, x, y, mouseX, mouseY);
    }

    /** An item with its count and durability bar. */
    void renderStack(GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.renderItem(stack, x, y);
        graphics.renderItemDecorations(font, stack, x, y);
    }

    void infoLine(GuiGraphics graphics, String labelKey, String value, int color, int left, int right, int y) {
        graphics.drawString(font, Component.translatable("screen.variable_economy_exchange." + labelKey), left, y, TEXT_DIM, false);
        drawRight(graphics, Component.literal(value), right, y, color);
    }

    private void renderScrollbar(GuiGraphics graphics, int x, int y) {
        int left = x + LIST_X + listWidth + 2;
        int top = y + LIST_Y;
        int height = rows * ROW_HEIGHT;
        graphics.fill(left, top, left + SCROLLBAR_WIDTH, top + height, 0x40000000);
        if (maxScroll() > 0) {
            int thumb = thumbHeight();
            int thumbY = top + (height - thumb) * scroll / maxScroll();
            graphics.fill(left, thumbY, left + SCROLLBAR_WIDTH, thumbY + thumb, 0xFFA0A0A8);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        drawRight(graphics, balanceText(ClientExchangeState.balance(), !balanceInTitleRow), imageWidth - MARGIN,
                balanceInTitleRow ? TITLE_Y + 3 : TOOLBAR_Y + 4, TEXT_MONEY);
        Component status = ClientExchangeState.status();
        if (!status.getString().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status.getString(), imageWidth - 2 * MARGIN), MARGIN, statusY,
                    ClientExchangeState.statusIsError() ? TEXT_BAD : TEXT_GOOD, false);
        }
    }

    /** "Bank: 1,234 bits"; abbreviated ("Bank: 123k bits") where it has to share the toolbar. */
    private static Component balanceText(long balance, boolean abbreviated) {
        String amount = abbreviated ? PriceFormat.compact(balance) : PriceFormat.amount(balance);
        return Component.translatable("screen.variable_economy_exchange.balance", PriceFormat.money(amount));
    }

    /** The widest balance the layout makes room for: about 100 billion, far beyond any likely fortune. */
    private static long widestBalance() {
        return 99_999_999_999L;
    }

    /** The toolbar room kept for the abbreviated balance: its widest form. */
    private int balanceReserve() {
        return font.width(balanceText(widestBalance(), true));
    }

    void drawRight(GuiGraphics graphics, Component text, int right, int y, int color) {
        graphics.drawString(font, text, right - font.width(text), y, color, false);
    }

    static String displayName(ResourceLocation item) {
        return BuiltInRegistries.ITEM.get(item).getDescription().getString();
    }
}
