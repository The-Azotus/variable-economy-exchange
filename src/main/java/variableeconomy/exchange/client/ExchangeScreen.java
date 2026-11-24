package variableeconomy.exchange.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import variableeconomy.exchange.menu.ExchangeMenu;
import variableeconomy.exchange.network.ExchangePayloads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The exchange screen: trading with other players, in four tabs. Browse lists everything for sale; Exchange posts buy
 * and sell orders; My Orders shows the player's orders and listings and their history; Collection holds what they've
 * bought and what came back, and what their sales paid. This class is the frame around the tabs: the title and
 * balance, the tab bar (with a mark on Collection while anything waits there), the sub-tabs or Back button, the search,
 * the status line, and the box that asks to confirm a purchase or a change.
 *
 * <p>The screen sizes itself to the window, up to {@link #MAX_WIDTH} by {@link #MAX_HEIGHT}. Each tab shows a list and,
 * once a row is picked, that row's details in its place, so every tab works on a narrow window too.
 */
public final class ExchangeScreen extends AbstractContainerScreen<ExchangeMenu> {
    /** The largest the screen grows to, so rows stay readable on a big window at GUI scale 1. */
    static final int MAX_WIDTH = 600;
    static final int MAX_HEIGHT = 400;
    /** At least this wide, a tab's details put their controls beside the item rather than below it. */
    static final int WIDE_MIN_WIDTH = 440;
    static final int MARGIN = 8;
    static final int TITLE_Y = 4;
    static final int TAB_Y = 20;
    static final int TOOLBAR_Y = 42;
    static final int CONTENT_Y = 64;
    /** Room left under the content for the status line. */
    static final int STATUS_HEIGHT = 14;

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
    static final int TEXT_BUY = 0x7EB6FF;
    static final int BADGE = 0xFFE0A030;

    /** Lower-case mod display names by namespace, for {@code @mod} searches. The mod list never changes in a session. */
    private static final Map<String, String> MOD_NAMES = new HashMap<>();
    /** The tab showing, kept while the game runs so the screen reopens where it was. */
    private static int lastTab;

    private final List<Tab> tabs = List.of(new BrowseTab(this), new TradeTab(this), new OrdersTab(this), new CollectionTab(this));
    private int current = lastTab;
    private final List<Button> tabButtons = new ArrayList<>();
    EditBox search;
    private String searchText = "";
    /** The confirm box showing, if any; while it shows, nothing beneath takes input. */
    @Nullable
    private ConfirmDialog dialog;
    @Nullable
    private Categories categories;

    private int seenVersion = -1;
    private String seenQuery = "";
    /** Set when a tab's list must be rebuilt now, whatever changed. */
    boolean listDirty = true;
    /** What the server was last asked to send, so it's asked again only when that changes. */
    @Nullable
    private ExchangePayloads.ExchangeRequest lastRequest;

    public ExchangeScreen(ExchangeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    // The tabs reach the screen's protected parts through these.

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

    /** Whether the screen is wide enough for a tab's details to put their controls beside the item. */
    boolean wide() {
        return imageWidth >= WIDE_MIN_WIDTH;
    }

    <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
        return addRenderableWidget(widget);
    }

    private Tab tab() {
        return tabs.get(current);
    }

    @Override
    protected void init() {
        imageWidth = Math.min(MAX_WIDTH, width - MARGIN);
        imageHeight = Math.min(MAX_HEIGHT, height - MARGIN);
        super.init();
        int x = leftPos;
        int y = topPos;
        Tab tab = tab();

        // The tab bar.
        tabButtons.clear();
        int tabWidth = Math.min(110, (imageWidth - 2 * MARGIN - 3 * 2) / tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            int index = i;
            Button button = addRenderableWidget(Button.builder(tabs.get(i).label(), b -> selectTab(index))
                    .bounds(x + MARGIN + i * (tabWidth + 2), y + TAB_Y, tabWidth, 18).build());
            button.active = i != current;
            tabButtons.add(button);
        }

        // The toolbar: Back or the sub-tabs, the search, then the tab's own controls.
        int toolX = x + MARGIN;
        int right = x + imageWidth - MARGIN;
        if (tab.inDetails()) {
            addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy_exchange.back"), b -> back())
                    .bounds(toolX, y + TOOLBAR_Y, 50, 18).build());
            toolX += 54;
        } else {
            List<Component> subTabs = tab.subTabs();
            for (int i = 0; i < subTabs.size(); i++) {
                int index = i;
                int w = Math.max(40, font.width(subTabs.get(i)) + 10);
                Button button = addRenderableWidget(Button.builder(subTabs.get(i), b -> selectSubTab(index))
                        .bounds(toolX, y + TOOLBAR_Y, w, 18).build());
                button.active = i != tab.subTab;
                toolX += w + 2;
            }
            if (!subTabs.isEmpty()) {
                toolX += 4;
            }
        }
        int searchWidth = Mth.clamp(right - tab.toolbarWidth() - 4 - toolX, 60, 150);
        search = new EditBox(font, toolX, y + TOOLBAR_Y + 2, searchWidth, 14, Component.translatable("screen.variable_economy_exchange.search"));
        search.setHint(Component.translatable("screen.variable_economy_exchange.search").withColor(TEXT_DIM));
        search.setTooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.search.tooltip")));
        search.setMaxLength(50);
        search.setValue(searchText);
        search.setResponder(value -> searchText = value);
        search.visible = !tab.inDetails();
        addRenderableWidget(search);
        tab.initToolbar(toolX + searchWidth + 4, y + TOOLBAR_Y);
        tab.init(x + MARGIN, y + CONTENT_Y, right, y + imageHeight - STATUS_HEIGHT);

        seenVersion = -1;
        requestWatched();
    }

    private void selectTab(int index) {
        click();
        current = index;
        lastTab = index;
        ClientExchangeState.clearStatus();
        relayout();
    }

    private void selectSubTab(int index) {
        click();
        tab().selectSubTab(index);
        relayout();
    }

    private void back() {
        click();
        tab().back();
        relayout();
    }

    /** Lays the screen out again: after the tab, sub-tab or details view changed. */
    void relayout() {
        listDirty = true;
        rebuildWidgets();
    }

    void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** Asks the server for what the tab shows, if that changed since last asked. */
    void requestWatched() {
        Tab tab = tab();
        ExchangePayloads.ExchangeRequest request = new ExchangePayloads.ExchangeRequest(tab.book(), tab.watchesBrowse(), tab.watchesHistory());
        if (!request.equals(lastRequest)) {
            lastRequest = request;
            PacketDistributor.sendToServer(request);
        }
    }

    /** Shows the box asking to confirm {@code onConfirm}. */
    void confirm(Component title, List<Component> lines, Component confirmLabel, Runnable onConfirm) {
        dialog = new ConfirmDialog(title, lines, confirmLabel, onConfirm);
        setFocused(null);
    }

    /** The Browse tab's categories, as the server last sent them. */
    @Nullable
    Categories categories() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        if (exchange == null) {
            return null;
        }
        if (categories == null || !categories.from(exchange.categories())) {
            categories = new Categories(exchange.categories());
        }
        return categories;
    }

    // ---- State -----------------------------------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        if (listDirty || seenVersion != ClientExchangeState.version() || !query.equals(seenQuery)) {
            listDirty = false;
            seenVersion = ClientExchangeState.version();
            seenQuery = query;
            tab().rebuild(query);
        }
        tab().tick();
        requestWatched();
        // The Collection tab's name carries how much waits there.
        tabButtons.get(3).setMessage(tabs.get(3).label());
        tabButtons.get(2).setMessage(tabs.get(2).label());
    }

    @Override
    public void removed() {
        super.removed();
        ClientExchangeState.clear();
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

    /** As {@link #matches}, also matching an item's own name, such as a renamed listing's. */
    static boolean matches(ItemStack stack, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return matches(BuiltInRegistries.ITEM.getKey(stack.getItem()), query)
                || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query);
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

    // ---- Input -----------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (dialog != null) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                int clicked = dialog.clicked(mouseX, mouseY, width);
                if (clicked != 0) {
                    click();
                    ConfirmDialog was = dialog;
                    dialog = null;
                    if (clicked > 0) {
                        was.confirm();
                    }
                }
            }
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && tab().clicked(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (dialog != null) {
            return true;
        }
        return tab().scrolled(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dialog == null && tab().dragged(mouseY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && tab().released()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (dialog != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                dialog = null;
            } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                ConfirmDialog was = dialog;
                dialog = null;
                was.confirm();
            }
            return true;
        }
        // Keep typing in a text box from triggering the inventory key, which would close the screen.
        if (keyCode != GLFW.GLFW_KEY_ESCAPE && getFocused() instanceof EditBox box && box.canConsumeInput()) {
            // Enter leaves a number box: every box but the search.
            if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && box != search) {
                setFocused(null);
                return true;
            }
            box.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- Rendering -------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Beneath the confirm box, nothing reacts to the mouse.
        int mx = dialog == null ? mouseX : -1;
        int my = dialog == null ? mouseY : -1;
        super.render(graphics, mx, my, partialTick);
        if (dialog == null) {
            tab().renderTooltip(graphics, mouseX, mouseY);
        } else {
            dialog.render(graphics, font, width, height, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        graphics.drawString(font, title, x + MARGIN, y + TITLE_Y + 3, TEXT, false);
        graphics.fill(x + MARGIN, y + TOOLBAR_Y - 3, x + imageWidth - MARGIN, y + TOOLBAR_Y - 2, PANEL_BORDER);
        graphics.fill(x + MARGIN, y + CONTENT_Y - 3, x + imageWidth - MARGIN, y + CONTENT_Y - 2, PANEL_BORDER);
        tab().render(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // The balance, top right, in full when it fits beside the title.
        Component balance = balanceText(ClientExchangeState.balance(), false);
        if (MARGIN + font.width(title) + 16 + font.width(balance) > imageWidth - MARGIN) {
            balance = balanceText(ClientExchangeState.balance(), true);
        }
        drawRight(graphics, balance, imageWidth - MARGIN, TITLE_Y + 3, TEXT_MONEY);
        // A mark on the Collection tab while anything waits there.
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        if (exchange != null && exchange.boxed() > 0 && tabButtons.size() > 3) {
            Button collection = tabButtons.get(3);
            int bx = collection.getX() - leftPos + collection.getWidth() - 5;
            int by = collection.getY() - topPos - 2;
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            graphics.fill(bx - 2, by, bx + 5, by + 7, 0xFF000000);
            graphics.fill(bx - 1, by + 1, bx + 4, by + 6, BADGE);
            graphics.pose().popPose();
        }
        Component status = ClientExchangeState.status();
        if (!status.getString().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status.getString(), imageWidth - 2 * MARGIN), MARGIN, imageHeight - 11,
                    ClientExchangeState.statusIsError() ? TEXT_BAD : TEXT_GOOD, false);
        }
    }

    /** "Bank: 1,234 bits"; abbreviated ("Bank: 123k bits") where the full amount doesn't fit. */
    private static Component balanceText(long balance, boolean abbreviated) {
        String amount = abbreviated ? PriceFormat.compact(balance) : PriceFormat.amount(balance);
        return Component.translatable("screen.variable_economy_exchange.balance", PriceFormat.money(amount));
    }

    // ---- Drawing helpers the tabs share ------------------------------------------------------------------------

    /** An item with its count and durability bar. */
    void renderStack(GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.renderItem(stack, x, y);
        graphics.renderItemDecorations(font, stack, x, y);
    }

    /** An item drawn twice its size, 32 pixels square. */
    void renderLargeStack(GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(2, 2, 1);
        graphics.renderItem(stack, 0, 0);
        graphics.renderItemDecorations(font, stack, 0, 0);
        graphics.pose().popPose();
    }

    void drawRight(GuiGraphics graphics, Component text, int right, int y, int color) {
        graphics.drawString(font, text, right - font.width(text), y, color, false);
    }

    /** Text cut to {@code width}. */
    void drawClipped(GuiGraphics graphics, String text, int x, int y, int width, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(text, Math.max(0, width)), x, y, color, false);
    }

    /** A label on the left and its value on the right of one line. */
    void infoLine(GuiGraphics graphics, Component label, Component value, int color, int left, int right, int y) {
        graphics.drawString(font, label, left, y, TEXT_DIM, false);
        drawRight(graphics, value, right, y, color);
    }

    /** Wrapped text from {@code top} down to {@code bottom}. Returns where the next line would go. */
    int drawWrapped(GuiGraphics graphics, Component text, int left, int top, int width, int bottom, int color) {
        int y = top;
        for (FormattedCharSequence line : font.split(text, Math.max(10, width))) {
            if (y + 9 > bottom) {
                break;
            }
            graphics.drawString(font, line, left, y, color, false);
            y += 10;
        }
        return y;
    }

    /** Centred, wrapped text for an empty list. */
    void drawEmpty(GuiGraphics graphics, Component text, int left, int top, int width) {
        List<FormattedCharSequence> lines = font.split(text, Math.max(10, width - 8));
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawCenteredString(font, lines.get(i), left + width / 2, top + 20 + i * 10, TEXT_DIM);
        }
    }

    /**
     * Everything about an item, as an advanced tooltip shows it (enchantments, name, durability, its ID and how many
     * components it carries), whether or not the player has advanced tooltips on: what a buyer inspects before buying.
     */
    List<Component> inspect(ItemStack stack) {
        return stack.getTooltipLines(Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.ADVANCED);
    }

    /** The item's full tooltip, below its name, from {@code top} down to {@code bottom}. */
    void renderInspection(GuiGraphics graphics, ItemStack stack, int left, int top, int width, int bottom) {
        List<Component> lines = inspect(stack);
        int y = top;
        // The first line is the name, shown already.
        for (int i = 1; i < lines.size() && y + 9 <= bottom; i++) {
            y = drawWrapped(graphics, lines.get(i), left, y, width, bottom, TEXT_DIM);
        }
    }

    /** The item's own tooltip with {@code extra} lines after it. */
    List<Component> tooltip(ItemStack stack, List<Component> extra) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, stack));
        lines.addAll(extra);
        return lines;
    }

    void showTooltip(GuiGraphics graphics, List<Component> lines, int mouseX, int mouseY) {
        graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    }

    static String displayName(ResourceLocation item) {
        return BuiltInRegistries.ITEM.get(item).getDescription().getString();
    }

    static ItemStack stack(ResourceLocation item) {
        return new ItemStack(BuiltInRegistries.ITEM.get(item));
    }

    /** Whether two IDs are the same order or listing; either may be null. */
    static boolean same(@Nullable Long a, long b) {
        return Objects.equals(a, b);
    }
}
