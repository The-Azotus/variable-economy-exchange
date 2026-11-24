package variableeconomy.exchange.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * A box over the screen asking the player to confirm a purchase, an order or a change before it's sent: what will
 * happen, line by line, and Confirm or Cancel. While it shows, the screen beneath takes no input. Enter confirms,
 * Escape cancels.
 */
final class ConfirmDialog {
    private static final int WIDTH = 220;
    private static final int PADDING = 8;
    private static final int BUTTON_WIDTH = 80;
    private static final int BUTTON_HEIGHT = 18;

    private final Component title;
    private final List<Component> lines;
    private final Component confirmLabel;
    private final Runnable onConfirm;
    private int left;
    private int top;
    private int height;
    private int buttonsY;

    ConfirmDialog(Component title, List<Component> lines, Component confirmLabel, Runnable onConfirm) {
        this.title = title;
        this.lines = lines;
        this.confirmLabel = confirmLabel;
        this.onConfirm = onConfirm;
    }

    void confirm() {
        onConfirm.run();
    }

    private List<FormattedCharSequence> wrapped(Font font, int width) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (Component line : lines) {
            out.addAll(font.split(line, width));
        }
        return out;
    }

    void render(GuiGraphics graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        int width = Math.min(WIDTH, screenWidth - 16);
        List<FormattedCharSequence> body = wrapped(font, width - 2 * PADDING);
        height = PADDING + 12 + body.size() * 10 + 8 + BUTTON_HEIGHT + PADDING;
        left = (screenWidth - width) / 2;
        top = (screenHeight - height) / 2;
        buttonsY = top + height - PADDING - BUTTON_HEIGHT;
        graphics.pose().pushPose();
        // Above the screen's widgets and items.
        graphics.pose().translate(0, 0, 400);
        graphics.fill(0, 0, screenWidth, screenHeight, 0x80000000);
        graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, ExchangeScreen.PANEL_BORDER);
        graphics.fill(left, top, left + width, top + height, 0xFF202024);
        graphics.drawString(font, title, left + PADDING, top + PADDING, ExchangeScreen.TEXT, false);
        for (int i = 0; i < body.size(); i++) {
            graphics.drawString(font, body.get(i), left + PADDING, top + PADDING + 14 + i * 10, ExchangeScreen.TEXT_DIM, false);
        }
        button(graphics, font, confirmLeft(width), confirmLabel, mouseX, mouseY, ExchangeScreen.TEXT_GOOD);
        button(graphics, font, cancelLeft(width), Component.translatable("screen.variable_economy_exchange.confirm.cancel"), mouseX, mouseY,
                ExchangeScreen.TEXT);
        graphics.pose().popPose();
    }

    private int confirmLeft(int width) {
        return left + width / 2 - BUTTON_WIDTH - 4;
    }

    private int cancelLeft(int width) {
        return left + width / 2 + 4;
    }

    private void button(GuiGraphics graphics, Font font, int x, Component label, int mouseX, int mouseY, int color) {
        boolean hover = mouseX >= x && mouseX < x + BUTTON_WIDTH && mouseY >= buttonsY && mouseY < buttonsY + BUTTON_HEIGHT;
        graphics.fill(x, buttonsY, x + BUTTON_WIDTH, buttonsY + BUTTON_HEIGHT, hover ? 0xFF6A6A78 : 0xFF404048);
        graphics.renderOutline(x, buttonsY, BUTTON_WIDTH, BUTTON_HEIGHT, hover ? 0xFFFFFFFF : 0xFF000000);
        String text = font.plainSubstrByWidth(label.getString(), BUTTON_WIDTH - 6);
        graphics.drawString(font, text, x + (BUTTON_WIDTH - font.width(text)) / 2, buttonsY + 5, color, false);
    }

    /** 1 if Confirm was clicked, -1 if Cancel was, 0 for anywhere else. */
    int clicked(double mouseX, double mouseY, int screenWidth) {
        int width = Math.min(WIDTH, screenWidth - 16);
        if (mouseY < buttonsY || mouseY >= buttonsY + BUTTON_HEIGHT) {
            return 0;
        }
        if (mouseX >= confirmLeft(width) && mouseX < confirmLeft(width) + BUTTON_WIDTH) {
            return 1;
        }
        if (mouseX >= cancelLeft(width) && mouseX < cancelLeft(width) + BUTTON_WIDTH) {
            return -1;
        }
        return 0;
    }
}
