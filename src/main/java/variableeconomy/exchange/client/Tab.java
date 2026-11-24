package variableeconomy.exchange.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * One of the exchange screen's four tabs. A tab shows a list (with sub-tabs, if it has them) and, once a row is
 * picked, that row's details with a Back button. The screen owns the frame (title, balance, tabs, search, status and
 * the confirm dialog) and passes input on; coordinates here are the screen's.
 */
abstract class Tab {
    protected final ExchangeScreen screen;
    /** The sub-tab showing, if the tab has any. */
    int subTab;

    Tab(ExchangeScreen screen) {
        this.screen = screen;
    }

    /** The tab's name in the tab bar. */
    abstract Component label();

    /** The tab's sub-tabs' names, shown in the toolbar while the list shows; none by default. */
    List<Component> subTabs() {
        return List.of();
    }

    /** Shows another sub-tab. */
    void selectSubTab(int index) {
        subTab = index;
    }

    /** Whether a row's details show (with a Back button) instead of the list. */
    abstract boolean inDetails();

    /** Back from the details to the list. */
    abstract void back();

    /** Room the tab's own toolbar controls need, right of the search box. */
    int toolbarWidth() {
        return 0;
    }

    /** Adds the tab's own toolbar controls, from {@code x} across. */
    void initToolbar(int x, int y) {
    }

    /** Adds the tab's widgets for its content, between {@code left}, {@code top}, {@code right} and {@code bottom}. */
    abstract void init(int left, int top, int right, int bottom);

    /** Rebuilds what the list shows, after the exchange or the search changed. */
    abstract void rebuild(String query);

    /** Every tick: refreshes buttons' labels and whether they can be pressed. */
    void tick() {
    }

    abstract void render(GuiGraphics graphics, int mouseX, int mouseY);

    void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    /** A left click that no widget took first, as far as the screen knows. True if the tab used it. */
    boolean clicked(double mouseX, double mouseY) {
        return false;
    }

    boolean scrolled(double mouseX, double mouseY, double amount) {
        return false;
    }

    boolean dragged(double mouseY) {
        return false;
    }

    boolean released() {
        return false;
    }

    /** The item whose book the tab shows, if any: the server sends it, and again whenever it changes. */
    Optional<ResourceLocation> book() {
        return Optional.empty();
    }

    /** Whether the tab shows every offer for sale. */
    boolean watchesBrowse() {
        return false;
    }

    /** Whether the tab shows the player's history. */
    boolean watchesHistory() {
        return false;
    }
}
