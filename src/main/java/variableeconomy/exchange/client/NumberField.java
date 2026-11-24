package variableeconomy.exchange.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.IntSupplier;

/**
 * A box for a whole number (a quantity or a price) that keeps what was typed when the screen is laid out again, with
 * optional - and + buttons (shift for 16) and an All button.
 */
final class NumberField {
    /** The width {@link #init} takes with the - and + buttons and an All button. */
    static final int STEPPED_WIDTH = 104;

    private final int maxDigits;
    private final Component name;
    private String text;
    EditBox box;

    NumberField(Component name, int maxDigits, String initial) {
        this.name = name;
        this.maxDigits = maxDigits;
        this.text = initial;
    }

    /** Just the box, {@code width} wide. */
    EditBox init(ExchangeScreen screen, int x, int y, int width, Component hint) {
        box = new EditBox(screen.font(), x, y + 2, width, 14, name);
        box.setFilter(s -> s.isEmpty() || (s.length() <= maxDigits && s.chars().allMatch(Character::isDigit)));
        box.setValue(text);
        box.setResponder(value -> text = value);
        if (hint != null) {
            box.setHint(hint.copy().withColor(ExchangeScreen.TEXT_DIM));
        }
        return screen.add(box);
    }

    /**
     * The box with - and + buttons, between 1 and {@code max}, and an All button setting {@code all} (none when
     * {@code all} is null). {@link #STEPPED_WIDTH} wide.
     */
    void initStepped(ExchangeScreen screen, int x, int y, IntSupplier max, IntSupplier all, Component allTooltip) {
        screen.add(Button.builder(Component.literal("-"), b -> step(-step(), max))
                .bounds(x, y, 16, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.step_hint")))
                .build());
        init(screen, x + 18, y, 40, null);
        screen.add(Button.builder(Component.literal("+"), b -> step(step(), max))
                .bounds(x + 60, y, 16, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy_exchange.step_hint")))
                .build());
        if (all != null) {
            screen.add(Button.builder(Component.translatable("screen.variable_economy_exchange.all"), b -> set(Math.max(1, all.getAsInt())))
                    .bounds(x + 78, y, 26, 18)
                    .tooltip(allTooltip == null ? null : Tooltip.create(allTooltip))
                    .build());
        }
    }

    private static int step() {
        return Screen.hasShiftDown() ? 16 : 1;
    }

    private void step(int delta, IntSupplier max) {
        set((int) Mth.clamp((long) intValue() + delta, 1, Math.max(1, max.getAsInt())));
    }

    void set(long value) {
        text = String.valueOf(value);
        if (box != null) {
            box.setValue(text);
        }
    }

    void clear() {
        text = "";
        if (box != null) {
            box.setValue("");
        }
    }

    /** What was typed, 0 if nothing or too large. */
    long value() {
        try {
            return text.isEmpty() ? 0 : Math.max(0, Long.parseLong(text));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    int intValue() {
        return (int) Math.min(Integer.MAX_VALUE, value());
    }
}
