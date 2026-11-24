package variableeconomy.exchange.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.function.IntSupplier;

/**
 * A scrolling list of rows in screen coordinates: which row is where, the scroll wheel, and a scrollbar whose thumb can
 * be dragged or whose track can be clicked. The tab that owns it draws the rows.
 */
final class ScrollList {
    static final int ROW_HEIGHT = 18;
    static final int SCROLLBAR_WIDTH = 4;
    private static final int TRACK = 0x40000000;
    private static final int THUMB = 0xFFA0A0A8;

    private final IntSupplier size;
    private final int rowHeight;
    /** Where the rows go, scrollbar excluded; set by {@link #place}. */
    int left;
    int top;
    int width;
    int rows;
    int scroll;
    private boolean dragging;

    ScrollList(IntSupplier size) {
        this(size, ROW_HEIGHT);
    }

    ScrollList(IntSupplier size, int rowHeight) {
        this.size = size;
        this.rowHeight = rowHeight;
    }

    /** Fills the box from ({@code x}, {@code y}), {@code w} wide and {@code h} high, its scrollbar inside on the right. */
    void place(int x, int y, int w, int h) {
        left = x;
        top = y;
        width = w - SCROLLBAR_WIDTH - 2;
        rows = Math.max(1, h / rowHeight);
        clamp();
    }

    int rowHeight() {
        return rowHeight;
    }

    int bottom() {
        return top + rows * rowHeight;
    }

    int right() {
        return left + width;
    }

    int size() {
        return size.getAsInt();
    }

    void clamp() {
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    void reset() {
        scroll = 0;
    }

    private int maxScroll() {
        return Math.max(0, size() - rows);
    }

    /** The row index under the mouse, or -1. */
    int rowAt(double mouseX, double mouseY) {
        if (mouseX < left || mouseX >= left + width || mouseY < top || mouseY >= bottom()) {
            return -1;
        }
        int index = scroll + (int) ((mouseY - top) / rowHeight);
        return index < size() ? index : -1;
    }

    /** The top of the row at {@code index}, which must be visible. */
    int rowTop(int index) {
        return top + (index - scroll) * rowHeight;
    }

    /** The index of the first and one past the last visible row. */
    int first() {
        return scroll;
    }

    int end() {
        return Math.min(size(), scroll + rows);
    }

    boolean over(double mouseX, double mouseY) {
        return mouseX >= left && mouseX < left + width + 2 + SCROLLBAR_WIDTH && mouseY >= top && mouseY < bottom();
    }

    boolean scrolled(double mouseX, double mouseY, double amount) {
        if (!over(mouseX, mouseY)) {
            return false;
        }
        scroll = Mth.clamp(scroll - (int) Math.signum(amount), 0, maxScroll());
        return true;
    }

    /** A click on the track jumps there; holding the button drags the thumb. */
    boolean clicked(double mouseX, double mouseY) {
        double x = mouseX - (left + width + 2);
        if (maxScroll() > 0 && x >= -1 && x < SCROLLBAR_WIDTH + 1 && mouseY >= top && mouseY < bottom()) {
            dragging = true;
            scrollTo(mouseY);
            return true;
        }
        return false;
    }

    boolean dragged(double mouseY) {
        if (dragging) {
            scrollTo(mouseY);
        }
        return dragging;
    }

    boolean released() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    private void scrollTo(double mouseY) {
        int height = rows * rowHeight;
        int thumb = thumbHeight();
        if (height <= thumb || maxScroll() == 0) {
            return;
        }
        double fraction = (mouseY - top - thumb / 2.0) / (height - thumb);
        scroll = Mth.clamp((int) Math.round(fraction * maxScroll()), 0, maxScroll());
    }

    private int thumbHeight() {
        int height = rows * rowHeight;
        return Math.min(height, Math.max(12, height * rows / Math.max(1, size())));
    }

    /** A row's background: picked, under the mouse, or every other one shaded. */
    void rowBackground(GuiGraphics graphics, int index, boolean picked, int hovered) {
        int background = picked ? ExchangeScreen.ROW_SELECTED : index == hovered ? ExchangeScreen.ROW_HOVER
                : (index - scroll) % 2 == 1 ? ExchangeScreen.ROW_ALT : 0;
        if (background != 0) {
            int y = rowTop(index);
            graphics.fill(left, y, left + width, y + rowHeight, background);
        }
    }

    void renderScrollbar(GuiGraphics graphics) {
        int x = left + width + 2;
        int height = rows * rowHeight;
        graphics.fill(x, top, x + SCROLLBAR_WIDTH, top + height, TRACK);
        if (maxScroll() > 0) {
            int thumb = thumbHeight();
            int thumbY = top + (height - thumb) * scroll / maxScroll();
            graphics.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumb, THUMB);
        }
    }
}
