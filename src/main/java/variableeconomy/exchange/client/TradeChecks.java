package variableeconomy.exchange.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import variableeconomy.exchange.network.ExchangePayloads;
import variableeconomy.exchange.service.ExchangeService;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * What the tabs work out before sending a request: the rules the server last sent (tax, fee, limits), what the
 * player holds, totals, and why a request would be refused, as far as the client can tell. Hints only: the server
 * checks everything again and replies with the real outcome.
 */
final class TradeChecks {
    /** The largest order until the server says otherwise: its default. */
    static final int DEFAULT_MAX_ORDER = 6400;
    private static final String RESULT = "screen.variable_economy_exchange.result.";

    private TradeChecks() {
    }

    // ---- The rules ---------------------------------------------------------------------------------------------

    /** The buyer's tax as a fraction, as the server last said. */
    static double taxRate() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange == null ? 0 : exchange.tax();
    }

    /** The tax as a percentage, for notes: "5.0%". */
    static String taxPercent() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f%%", exchange.tax() * 100);
    }

    /** The fee per post, as the server last said, kept below what adding it to a total could overflow. */
    static long fee() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange == null ? 0 : Math.min(exchange.listingFee(), Long.MAX_VALUE / 2);
    }

    static int maxOrderSize() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange != null ? exchange.maxOrderSize() : DEFAULT_MAX_ORDER;
    }

    static boolean atPostLimit() {
        ExchangePayloads.Exchange exchange = ClientExchangeState.exchange();
        return exchange != null && exchange.open() >= exchange.maxOrders();
    }

    // ---- Arithmetic --------------------------------------------------------------------------------------------

    /** {@code unitPrice × qty}, or empty if it would overflow a {@code long}. */
    static OptionalLong checkedTotal(long unitPrice, long qty) {
        try {
            return OptionalLong.of(Math.multiplyExact(unitPrice, qty));
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    /** {@code price × qty} plus the buyer's tax, or {@link Long#MAX_VALUE} if that overflows. */
    static long costWithTax(long price, long qty) {
        return ExchangeService.withTax(checkedTotal(price, qty).orElse(Long.MAX_VALUE), taxRate()).orElse(Long.MAX_VALUE);
    }

    static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    // ---- What the player holds ---------------------------------------------------------------------------------

    private static List<ItemStack> inventory() {
        return Minecraft.getInstance().player.getInventory().items;
    }

    /** How many fresh copies of {@code item} the main inventory holds: what a sell order on the book can take. */
    static int freshHeld(ResourceLocation item) {
        return heldLike(new ItemStack(BuiltInRegistries.ITEM.get(item)));
    }

    /** How many units of exactly {@code like} the main inventory holds. */
    static int heldLike(ItemStack like) {
        int count = 0;
        for (ItemStack stack : inventory()) {
            if (ItemStack.isSameItemSameComponents(stack, like)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * What the player could sell, one entry each: a fresh copy of every plain item held (for a sell order), and every
     * different item with data of its own that can be listed (for a listing), from the client's copy of the inventory,
     * which the server keeps in sync.
     */
    static List<ItemStack> sellable() {
        List<ItemStack> found = new ArrayList<>();
        for (ItemStack stack : inventory()) {
            if (stack.isEmpty() || !ExchangeService.tradeable(stack.getItem())) {
                continue;
            }
            ItemStack one = ExchangeService.listable(stack) ? stack.copyWithCount(1) : new ItemStack(stack.getItem());
            if (!ExchangeService.listable(stack) && !ExchangeService.fresh(stack.getItem()).test(stack)) {
                // Neither fresh nor listable (a container with contents): can't be sold here.
                continue;
            }
            if (found.stream().noneMatch(s -> ItemStack.isSameItemSameComponents(s, one))) {
                found.add(one);
            }
        }
        return found;
    }

    /** The main-inventory slot holding {@code like}, or -1. */
    static int slotOf(ItemStack like) {
        List<ItemStack> items = inventory();
        for (int slot = 0; slot < items.size(); slot++) {
            if (ItemStack.isSameItemSameComponents(items.get(slot), like)) {
                return slot;
            }
        }
        return -1;
    }

    static ItemStack slot(int slot) {
        return inventory().get(slot);
    }

    // ---- Why a request would be refused ------------------------------------------------------------------------

    private static Component result(String key) {
        return Component.translatable(RESULT + key);
    }

    /** Posting a buy ({@code buy}) or sell order for {@code qty} of {@code item} at {@code each}. */
    @Nullable
    static Component postProblem(ResourceLocation item, boolean buy, int qty, long each) {
        if (!ClientExchangeState.enabled()) {
            return result("exchange_closed");
        } else if (qty <= 0) {
            return result("invalid_quantity");
        } else if (each <= 0) {
            return result("invalid_price");
        } else if (qty > maxOrderSize() || checkedTotal(each, qty).isEmpty()) {
            return result("too_large");
        } else if (atPostLimit()) {
            return result("order_limit");
        } else if (buy && !ClientExchangeState.looksAffordable(saturatedAdd(costWithTax(each, qty), fee()))) {
            return result("no_funds");
        } else if (!buy && freshHeld(item) < qty) {
            return result("not_enough_items");
        } else if (!buy && fee() > 0 && !ClientExchangeState.looksAffordable(fee())) {
            // Posting a sell order takes the fee from the account before the items.
            return result("no_funds");
        }
        return null;
    }

    /** Listing {@code qty} of exactly {@code stack} at {@code each}. */
    @Nullable
    static Component listProblem(ItemStack stack, int qty, long each) {
        if (!ClientExchangeState.enabled()) {
            return result("exchange_closed");
        } else if (qty <= 0) {
            return result("invalid_quantity");
        } else if (each <= 0) {
            return result("invalid_price");
        } else if (qty > stack.getMaxStackSize() || checkedTotal(each, qty).isEmpty()) {
            return result("too_large");
        } else if (atPostLimit()) {
            return result("order_limit");
        } else if (heldLike(stack) < qty) {
            return result("not_enough_items");
        } else if (fee() > 0 && !ClientExchangeState.looksAffordable(fee())) {
            return result("no_funds");
        }
        return null;
    }

    /** Buying {@code qty} from an offer in Browse: a listing, or another player's sell order. */
    @Nullable
    static Component buyOfferProblem(ExchangePayloads.OfferLine offer, int qty) {
        if (!ClientExchangeState.enabled()) {
            return result("exchange_closed");
        } else if (offer.mine()) {
            return result(offer.listing() ? "own_listing" : "own_order");
        } else if (qty <= 0) {
            return result("invalid_quantity");
        } else if (qty > offer.remaining()) {
            return result("no_stock");
        } else if (!offer.listing() && qty > maxOrderSize()) {
            return result("too_large");
        } else if (!ClientExchangeState.looksAffordable(costWithTax(offer.price(), qty))) {
            return result("no_funds");
        }
        return null;
    }

    /** Filling {@code qty} of another player's book order: buying from a sell order ({@code ask}) or selling into a buy order. */
    @Nullable
    static Component fillProblem(ResourceLocation item, ExchangeService.BookOrder order, boolean ask, int qty) {
        if (!ClientExchangeState.enabled()) {
            return result("exchange_closed");
        } else if (qty <= 0) {
            return result("invalid_quantity");
        } else if (qty > order.quantity()) {
            return result("no_stock");
        } else if (qty > maxOrderSize()) {
            return result("too_large");
        } else if (ask && !ClientExchangeState.looksAffordable(costWithTax(order.price(), qty))) {
            return result("no_funds");
        } else if (!ask && freshHeld(item) < qty) {
            return result("not_enough_items");
        }
        return null;
    }

    // ---- Words -------------------------------------------------------------------------------------------------

    /** "1 day" or "N days". */
    static MutableComponent days(long days) {
        return Component.translatable(days == 1 ? "screen.variable_economy_exchange.exchange.order.day"
                : "screen.variable_economy_exchange.exchange.order.days", days);
    }

    /** When a post expires: "never", or in how many days. */
    static Component expires(long daysLeft) {
        return daysLeft < 0 ? Component.translatable("screen.variable_economy_exchange.exchange.order.never") : days(daysLeft);
    }

    /** How long ago something was posted, in in-game days: "today", "1 day ago", "3 days ago". */
    static Component dayAge(long days) {
        return days <= 0 ? Component.translatable("screen.variable_economy_exchange.age.today")
                : Component.translatable(days == 1 ? "screen.variable_economy_exchange.age.day" : "screen.variable_economy_exchange.age.days", days);
    }

    /** How long ago something happened, by the server's clock: "just now", "5 min ago", "3 h ago", "2 d ago". */
    static Component timeAge(long time) {
        long seconds = Math.max(0, (ClientExchangeState.serverNow() - time) / 1000);
        if (seconds < 60) {
            return Component.translatable("screen.variable_economy_exchange.age.now");
        }
        if (seconds < 3600) {
            return Component.translatable("screen.variable_economy_exchange.age.minutes", seconds / 60);
        }
        if (seconds < 86400) {
            return Component.translatable("screen.variable_economy_exchange.age.hours", seconds / 3600);
        }
        return Component.translatable("screen.variable_economy_exchange.age.real_days", seconds / 86400);
    }

    /** An amount of money: "1,234 bits". */
    static Component money(long amount) {
        return PriceFormat.money(PriceFormat.amount(amount));
    }
}
