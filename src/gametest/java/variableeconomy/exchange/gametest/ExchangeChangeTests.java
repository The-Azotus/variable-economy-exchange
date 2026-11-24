package variableeconomy.exchange.gametest;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.service.ItemGateService;
import variableeconomy.exchange.ExchangeConfig;
import variableeconomy.exchange.ExchangeModule;
import variableeconomy.exchange.ExchangeSettings;
import variableeconomy.exchange.data.ExchangeData;
import variableeconomy.exchange.data.ExchangeData.HistoryEntry;
import variableeconomy.exchange.data.ExchangeData.HistoryKind;
import variableeconomy.exchange.data.ExchangeData.Side;
import variableeconomy.exchange.service.ExchangeResult;
import variableeconomy.exchange.service.ExchangeService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static variableeconomy.exchange.gametest.CoreTestSupport.*;
import static variableeconomy.exchange.gametest.ExchangeTestSupport.*;

/**
 * Changing an open order or listing (its price, or lowering its quantity), each player's history, the Browse tab's
 * offers and the default category tags.
 */
@GameTestHolder(ExchangeModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExchangeChangeTests {
    private ExchangeChangeTests() {
    }

    private static final double TAX = 0.05;

    private static ExchangeService.Outcome reprice(CoreFixture f, ExchangeData data, long id, long price) {
        return ExchangeService.reprice(f.player, data, id, price);
    }

    private static ExchangeService.Outcome reduce(CoreFixture f, ExchangeData data, long id, int remaining) {
        return ExchangeService.reduce(f.player, data, id, remaining);
    }

    private static long boxed(ExchangeData data, CoreFixture f, Item item) {
        return data.box(f.player.getUUID()).getOrDefault(id(item), 0L);
    }

    private static List<HistoryKind> kinds(ExchangeData data, CoreFixture f) {
        return data.history(f.player.getUUID()).stream().map(HistoryEntry::kind).toList();
    }

    /** Planks renamed "Fancy": a stackable item with data of its own, so it's listed. */
    private static ItemStack fancy(int count) {
        ItemStack stack = new ItemStack(Items.OAK_PLANKS, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Fancy"));
        return stack;
    }

    /** Whether two history lines say the same (their items compared by item and data). */
    private static boolean same(HistoryEntry a, HistoryEntry b) {
        return a.kind() == b.kind() && a.side() == b.side() && a.orderId() == b.orderId() && ItemStack.isSameItemSameComponents(a.item(), b.item())
                && a.units() == b.units() && a.price() == b.price() && a.amount() == b.amount() && a.tax() == b.tax()
                && a.counterparty().equals(b.counterparty()) && a.time() == b.time();
    }

    private static ItemStack sword(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 2);
        return sword;
    }

    // ---- Changing the price ------------------------------------------------------------------------------------

    /**
     * A partly filled buy order changed to a higher, then a lower price holds exactly what its remaining units need at
     * the new price, and their tax on the order's whole spending: more is charged, then the rest comes back.
     */
    @GameTest(template = EMPTY)
    public static void repricingABuyOrderHoldsWhatItsRestNeeds(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture buyer = new CoreFixture(helper);
            CoreFixture seller = new CoreFixture(helper);
            buyer.deposit(1000);
            seller.give(new ItemStack(Items.COBBLESTONE, 4));
            long bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 10, 10).orderId();
            helper.assertValueEqual(buyer.balance(), 1000 - 105, "100 and its tax held");
            helper.assertValueEqual(ExchangeService.fill(seller.player, data, bid, 4, 0).result(), ExchangeResult.OK, "four sold into it");
            ExchangeData.Order before = data.find(bid).orElseThrow();
            helper.assertValueEqual(before.heldMoney(), 63L, "105 less 40 and the tax on 40 (2)");

            // Up to 12: 6 × 12 = 72, and the tax on 112 (6) less that on 40 (2): 76, so 13 more.
            ExchangeService.Outcome up = reprice(buyer, data, bid, 12);
            helper.assertValueEqual(up.result(), ExchangeResult.OK, "raised");
            ExchangeData.Order raised = data.find(bid).orElseThrow();
            helper.assertValueEqual(raised.price(), 12L, "at its new price");
            helper.assertValueEqual(raised.id(), bid, "it keeps its ID");
            helper.assertTrue(raised.seq() > before.seq(), "and moves to the back of the queue");
            helper.assertValueEqual(raised.heldMoney(), 76L, "it holds what its rest needs at 12");
            helper.assertValueEqual(raised.heldMoney(), ExchangeService.needed(raised, TAX), "exactly");
            helper.assertValueEqual(buyer.balance(), 1000 - 105 - 13, "the 13 more came from the buyer");
            helper.assertValueEqual(raised.filled(), 4, "what it bought stays bought");

            // Down to 8: 6 × 8 = 48, and the tax on 88 (5) less that on 40 (2): 51, so 25 back.
            helper.assertValueEqual(reprice(buyer, data, bid, 8).result(), ExchangeResult.OK, "lowered");
            ExchangeData.Order lowered = data.find(bid).orElseThrow();
            helper.assertValueEqual(lowered.heldMoney(), 51L, "it holds what its rest needs at 8");
            helper.assertValueEqual(buyer.balance(), 1000 - 105 - 13 + 25, "the 25 it no longer needs came back");

            // Everything it holds goes back on cancelling; the buyer has paid exactly 40 and its tax.
            helper.assertValueEqual(ExchangeService.cancel(buyer.player, data, bid).result(), ExchangeResult.OK, "cancelled");
            helper.assertValueEqual(buyer.balance(), 1000 - 42, "40 for four, and a tax of 2");
            helper.assertValueEqual(seller.balance(), 40, "the seller got the whole price");
        });
        helper.succeed();
    }

    /** A new price puts an order behind those already at that price, and trades at once if it now crosses. */
    @GameTest(template = EMPTY)
    public static void repricingMovesAnOrderToTheBackAndMatchesIt(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture first = new CoreFixture(helper);
            CoreFixture second = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            first.give(new ItemStack(Items.COBBLESTONE, 1));
            second.give(new ItemStack(Items.COBBLESTONE, 1));
            buyer.deposit(1000);
            long older = posted(helper, first, data, Items.COBBLESTONE, false, 10, 1).orderId();
            long newer = posted(helper, second, data, Items.COBBLESTONE, false, 12, 1).orderId();
            // The older one moves to 12: it now waits behind the newer one there.
            helper.assertValueEqual(reprice(first, data, older, 12).result(), ExchangeResult.OK, "repriced");
            helper.assertValueEqual(data.side(id(Items.COBBLESTONE), false).stream().map(ExchangeData.Order::id).toList(), List.of(newer, older),
                    "the order already at 12 comes first");
            posted(helper, buyer, data, Items.COBBLESTONE, true, 12, 1);
            helper.assertTrue(data.find(newer).isEmpty(), "the buyer bought from the one that was there first");
            helper.assertTrue(data.find(older).isPresent(), "the repriced one still waits");

            // A buy order raised to meet the waiting sell order trades at once, at the sell order's price.
            long bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 5, 1).orderId();
            ExchangeService.Outcome crossed = reprice(buyer, data, bid, 15);
            helper.assertValueEqual(crossed.filled(), 1, "it traded at once");
            helper.assertValueEqual(crossed.amount(), 13L, "at 12 and its tax of 1");
            helper.assertValueEqual(crossed.orderId(), 0L, "nothing left to wait");
            helper.assertTrue(data.find(bid).isEmpty() && data.find(older).isEmpty(), "both orders are done");
            helper.assertValueEqual(first.balance(), 12, "the seller got 12");
            helper.assertValueEqual(buyer.balance(), 1000 - 13 - 13, "the buyer paid 12 and 1 tax twice; the 15 bid's excess came back");
            helper.assertValueEqual(boxed(data, buyer, Items.COBBLESTONE), 2L, "both in the buyer's box");
        });
        helper.succeed();
    }

    /** A refused price change changes nothing: the order, the balance and the queue stay as they were. */
    @GameTest(template = EMPTY)
    public static void repricingIsRefusedWithoutChangingAnything(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture buyer = new CoreFixture(helper);
            CoreFixture other = new CoreFixture(helper);
            buyer.deposit(105);
            long bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 10, 10).orderId();
            ExchangeData.Order before = data.find(bid).orElseThrow();
            helper.assertValueEqual(buyer.balance(), 0, "everything is held");

            helper.assertValueEqual(reprice(buyer, data, bid, 11).result(), ExchangeResult.NO_FUNDS, "nothing left to pay more with");
            helper.assertValueEqual(reprice(other, data, bid, 9).result(), ExchangeResult.NOT_YOUR_ORDER, "someone else's");
            helper.assertValueEqual(reprice(buyer, data, bid, 0).result(), ExchangeResult.INVALID_PRICE, "no price");
            helper.assertValueEqual(reprice(buyer, data, 999_999, 9).result(), ExchangeResult.ORDER_GONE, "no such order");
            helper.assertValueEqual(reprice(buyer, data, bid, Long.MAX_VALUE / 5).result(), ExchangeResult.TOO_LARGE, "a total too large to count");
            ItemGateService.setTestLocks((player, item) -> player == buyer.player && item == Items.COBBLESTONE);
            try {
                helper.assertValueEqual(reprice(buyer, data, bid, 9).result(), ExchangeResult.NOT_LISTED, "a locked item");
            } finally {
                ItemGateService.setTestLocks(null);
            }
            ExchangeConfig.ENABLED.set(false);
            try {
                helper.assertValueEqual(reprice(buyer, data, bid, 9).result(), ExchangeResult.EXCHANGE_CLOSED, "the exchange is off");
            } finally {
                ExchangeConfig.ENABLED.set(true);
            }
            helper.assertValueEqual(data.find(bid).orElseThrow(), before, "the order is as it was");
            helper.assertValueEqual(buyer.balance(), 0, "and the balance");
            helper.assertValueEqual(reprice(buyer, data, bid, 10).result(), ExchangeResult.OK, "the same price is fine");
            helper.assertValueEqual(data.find(bid).orElseThrow(), before, "and changes nothing, not even its place");
            helper.assertTrue(kinds(data, buyer).isEmpty(), "nothing in its history");

            // Raising an order paid from another account would take more from that account: refused.
            ExchangeData.Order fromOther = new ExchangeData.Order(data.takeId(), other.player.getUUID(),
                    Optional.of(CoreApi.accounts().personal(buyer.player.getUUID()).value()), id(Items.OAK_LOG), true, 5, 2, 0, 0, 11, 0);
            data.add(fromOther);
            other.deposit(100);
            helper.assertValueEqual(reprice(other, data, fromOther.id(), 6).result(), ExchangeResult.WRONG_ACCOUNT, "paid from another account");
            helper.assertValueEqual(other.balance(), 100, "nothing taken");
            helper.assertValueEqual(data.find(fromOther.id()).orElseThrow(), fromOther, "the order is as it was");
            data.remove(fromOther.id());
        });
        helper.succeed();
    }

    /**
     * A listing's price changes in place. A buyer who saw the old price is refused, and so is a filler who saw an
     * order's old price.
     */
    @GameTest(template = EMPTY)
    public static void buyersWhoSawTheOldPriceAreRefused(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            seller.give(sword(helper));
            seller.give(new ItemStack(Items.COBBLESTONE, 3));
            buyer.deposit(1000);
            long listing = ExchangeService.list(seller.player, data, 0, seller.player.getInventory().items.get(0).copy(), 100, 1, 0).orderId();
            helper.assertValueEqual(reprice(seller, data, listing, 120).result(), ExchangeResult.OK, "the listing's price changed");
            helper.assertValueEqual(data.findListing(listing).orElseThrow().price(), 120L, "to 120");
            helper.assertValueEqual(ExchangeService.buyListing(buyer.player, data, listing, 1, 100).result(), ExchangeResult.PRICE_CHANGED,
                    "a buyer who saw 100");
            helper.assertValueEqual(buyer.balance(), 1000, "paid nothing");

            long ask = posted(helper, seller, data, Items.COBBLESTONE, false, 10, 3).orderId();
            helper.assertValueEqual(reprice(seller, data, ask, 11).result(), ExchangeResult.OK, "the sell order's price changed");
            helper.assertValueEqual(ExchangeService.fill(buyer.player, data, ask, 1, OptionalLong.of(10), 0).result(), ExchangeResult.PRICE_CHANGED,
                    "a filler who saw 10");
            helper.assertValueEqual(buyer.balance(), 1000, "paid nothing");
            helper.assertValueEqual(ExchangeService.fill(buyer.player, data, ask, 1, OptionalLong.of(11), 0).result(), ExchangeResult.OK, "at 11");
            helper.assertValueEqual(buyer.balance(), 1000 - 12, "11 and a tax of 1");
        });
        helper.succeed();
    }

    // ---- Lowering the quantity ---------------------------------------------------------------------------------

    /** Lowering an order or listing returns exactly what the units taken off held, and keeps its place. */
    @GameTest(template = EMPTY)
    public static void reducingReturnsWhatTheUnitsHeld(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture player = new CoreFixture(helper);
            CoreFixture other = new CoreFixture(helper);
            player.deposit(1000);
            player.give(new ItemStack(Items.OAK_LOG, 10));
            player.give(fancy(2));
            long bid = posted(helper, player, data, Items.COBBLESTONE, true, 10, 10).orderId();
            long ask = posted(helper, player, data, Items.OAK_LOG, false, 7, 10).orderId();
            int fancySlot = player.player.getInventory().findSlotMatchingItem(fancy(1));
            long listing = ExchangeService.list(player.player, data, fancySlot, fancy(1), 50, 2, 0).orderId();
            ExchangeData.Order bidBefore = data.find(bid).orElseThrow();
            int balance = player.balance();

            // The buy order: 10 at 10 held 105; 4 at 10 need 40 and a tax of 2, so 63 comes back.
            ExchangeService.Outcome lowered = reduce(player, data, bid, 4);
            helper.assertValueEqual(lowered.result(), ExchangeResult.OK, "lowered");
            helper.assertValueEqual(lowered.amount(), 63L, "63 back");
            helper.assertValueEqual(player.balance(), balance + 63, "into the account");
            ExchangeData.Order bidAfter = data.find(bid).orElseThrow();
            helper.assertValueEqual(bidAfter.remaining(), 4, "four still wanted");
            helper.assertValueEqual(bidAfter.heldMoney(), 42L, "holding just what they need");
            helper.assertValueEqual(bidAfter.seq(), bidBefore.seq(), "in the same place in the queue");

            // The sell order's six units go to the box; the listing's one to the box as it is.
            helper.assertValueEqual(reduce(player, data, ask, 4).result(), ExchangeResult.OK, "sell order lowered");
            helper.assertValueEqual(data.find(ask).orElseThrow().remaining(), 4, "four for sale");
            helper.assertValueEqual(boxed(data, player, Items.OAK_LOG), 6L, "six back in the box");
            helper.assertValueEqual(reduce(player, data, listing, 1).result(), ExchangeResult.OK, "listing lowered");
            helper.assertValueEqual(data.findListing(listing).orElseThrow().remaining(), 1, "one listed");
            helper.assertValueEqual(data.parcels(player.player.getUUID()).size(), 1, "one parcel");
            helper.assertTrue(ItemStack.isSameItemSameComponents(data.parcels(player.player.getUUID()).get(0).stack(), fancy(1)),
                    "the planks as they were listed, name and all");

            // Out of range, or someone else's: refused, nothing changes.
            helper.assertValueEqual(reduce(player, data, bid, 4).result(), ExchangeResult.INVALID_QUANTITY, "not fewer than now");
            helper.assertValueEqual(reduce(player, data, bid, 0).result(), ExchangeResult.INVALID_QUANTITY, "none: cancel instead");
            helper.assertValueEqual(reduce(other, data, bid, 1).result(), ExchangeResult.NOT_YOUR_ORDER, "someone else's");
            helper.assertValueEqual(data.find(bid).orElseThrow(), bidAfter, "unchanged");
            helper.assertValueEqual(kinds(data, player), List.of(HistoryKind.REDUCED, HistoryKind.REDUCED, HistoryKind.REDUCED), "each in the history");
        });
        helper.succeed();
    }

    /**
     * Changing and lowering an order, then cancelling it, costs nothing and gains nothing: no money is made or lost
     * by a round trip through any of them.
     */
    @GameTest(template = EMPTY)
    public static void changingAnOrderNeverMakesOrLosesMoney(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture buyer = new CoreFixture(helper);
            buyer.deposit(10_000);
            long bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 7, 33).orderId();
            for (long price : new long[] {9, 3, 101, 1, 57}) {
                helper.assertValueEqual(reprice(buyer, data, bid, price).result(), ExchangeResult.OK, "to " + price);
                ExchangeData.Order order = data.find(bid).orElseThrow();
                helper.assertValueEqual(order.heldMoney(), ExchangeService.needed(order, TAX), "holds just what it needs at " + price);
                helper.assertValueEqual((long) buyer.balance() + order.heldMoney(), 10_000L, "no money made or lost at " + price);
            }
            for (int left : new int[] {20, 13, 1}) {
                helper.assertValueEqual(reduce(buyer, data, bid, left).result(), ExchangeResult.OK, "to " + left);
                ExchangeData.Order order = data.find(bid).orElseThrow();
                helper.assertValueEqual((long) buyer.balance() + order.heldMoney(), 10_000L, "no money made or lost at " + left);
            }
            helper.assertValueEqual(ExchangeService.cancel(buyer.player, data, bid).result(), ExchangeResult.OK, "cancelled");
            helper.assertValueEqual(buyer.balance(), 10_000, "all of it back");
        });
        helper.succeed();
    }

    // ---- History -----------------------------------------------------------------------------------------------

    /** Each trade is in both players' history, and so is each order's end; history survives a restart. */
    @GameTest(template = EMPTY)
    public static void historyKeepsEveryTradeAndEnd(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 10));
            seller.give(sword(helper));
            buyer.deposit(1000);

            long ask = posted(helper, seller, data, Items.COBBLESTONE, false, 10, 4).orderId();
            helper.assertValueEqual(posted(helper, buyer, data, Items.COBBLESTONE, true, 10, 4).filled(), 4, "all four traded at once");
            HistoryEntry bought = data.history(buyer.player.getUUID()).get(0);
            helper.assertValueEqual(bought.kind(), HistoryKind.BOUGHT, "the buyer bought");
            helper.assertValueEqual(bought.side(), Side.BUY, "with a buy order");
            helper.assertTrue(bought.orderId() > ask, "its own, new order");
            helper.assertValueEqual(bought.units(), 4L, "four");
            helper.assertValueEqual(bought.price(), 10L, "at 10");
            helper.assertValueEqual(bought.amount(), 42L, "40 and a tax of 2");
            helper.assertValueEqual(bought.tax(), 2L, "the tax");
            helper.assertValueEqual(bought.counterparty(), Optional.of(seller.player.getUUID()), "from the seller");
            helper.assertValueEqual(kinds(data, buyer), List.of(HistoryKind.BOUGHT, HistoryKind.COMPLETED), "and the order completed");
            helper.assertValueEqual(kinds(data, seller), List.of(HistoryKind.SOLD, HistoryKind.COMPLETED), "the seller sold, and theirs completed");
            HistoryEntry sold = data.history(seller.player.getUUID()).get(0);
            helper.assertValueEqual(sold.orderId(), ask, "the seller's order");
            helper.assertValueEqual(sold.amount(), 40L, "paid the whole price");
            HistoryEntry completed = data.history(seller.player.getUUID()).get(1);
            helper.assertValueEqual(completed.units(), 4L, "all four");
            helper.assertValueEqual(completed.amount(), 40L, "came to 40");

            // Cancelled, expired, closed by an admin.
            long cancelled = posted(helper, seller, data, Items.COBBLESTONE, false, 50, 2).orderId();
            ExchangeService.cancel(seller.player, data, cancelled);
            posted(helper, seller, data, Items.COBBLESTONE, false, 50, 2);
            ExchangeService.expire(helper.getLevel().getServer(), data, 7, 7);
            long closed = posted(helper, seller, data, Items.COBBLESTONE, false, 50, 2).orderId();
            ExchangeService.close(helper.getLevel().getServer(), data, data.find(closed).orElseThrow());
            helper.assertValueEqual(kinds(data, seller).subList(2, 5), List.of(HistoryKind.CANCELLED, HistoryKind.EXPIRED, HistoryKind.CLOSED),
                    "each way an order ends");
            helper.assertValueEqual(data.history(seller.player.getUUID()).get(2).units(), 2L, "with the units that went back");

            // A purchase from a listing: the buyer's has no order of its own, the seller's is the listing's.
            int slot = seller.player.getInventory().findSlotMatchingItem(sword(helper));
            long listing = ExchangeService.list(seller.player, data, slot, sword(helper), 70, 1, 0).orderId();
            ExchangeService.buyListing(buyer.player, data, listing, 1, 70);
            HistoryEntry boughtListing = data.history(buyer.player.getUUID()).get(2);
            helper.assertValueEqual(boughtListing.side(), Side.LISTING, "from a listing");
            helper.assertValueEqual(boughtListing.orderId(), 0L, "no order of the buyer's");
            helper.assertTrue(ItemStack.isSameItemSameComponents(boughtListing.item(), sword(helper)), "the sword exactly as listed");
            helper.assertValueEqual(kinds(data, seller).subList(5, 7), List.of(HistoryKind.SOLD, HistoryKind.COMPLETED), "the listing sold out");
            helper.assertValueEqual(data.history(seller.player.getUUID()).get(5).orderId(), listing, "the listing's ID");

            // Saved and loaded, it's all still there.
            ExchangeData loaded = ExchangeData.fromTag(data.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
            helper.assertValueEqual(loaded.history(seller.player.getUUID()).size(), data.history(seller.player.getUUID()).size(), "the seller's history");
            helper.assertTrue(same(loaded.history(buyer.player.getUUID()).get(0), bought), "line for line");
            helper.assertTrue(ItemStack.isSameItemSameComponents(loaded.history(buyer.player.getUUID()).get(2).item(), sword(helper)),
                    "the sword's data too");
        });
        helper.succeed();
    }

    /** Each player keeps only {@code history.max_per_player} lines, the newest. */
    @GameTest(template = EMPTY)
    public static void historyKeepsTheNewestLines(GameTestHelper helper) {
        withExchange(() -> {
            int before = ExchangeConfig.HISTORY_PER_PLAYER.get();
            ExchangeConfig.HISTORY_PER_PLAYER.set(3);
            try {
                ExchangeData data = new ExchangeData();
                CoreFixture seller = new CoreFixture(helper);
                seller.give(new ItemStack(Items.COBBLESTONE, 6));
                for (long price = 1; price <= 5; price++) {
                    long id = posted(helper, seller, data, Items.COBBLESTONE, false, price, 1).orderId();
                    ExchangeService.cancel(seller.player, data, id);
                }
                List<HistoryEntry> kept = data.history(seller.player.getUUID());
                helper.assertValueEqual(kept.size(), 3, "three kept");
                helper.assertValueEqual(kept.stream().map(HistoryEntry::price).toList(), List.of(3L, 4L, 5L), "the newest three");
                ExchangeConfig.HISTORY_PER_PLAYER.set(0);
                long id = posted(helper, seller, data, Items.COBBLESTONE, false, 9, 1).orderId();
                ExchangeService.cancel(seller.player, data, id);
                helper.assertValueEqual(data.history(seller.player.getUUID()).size(), 3, "0 records nothing more");
            } finally {
                ExchangeConfig.HISTORY_PER_PLAYER.set(before);
            }
        });
        helper.succeed();
    }

    // ---- Browse and categories ---------------------------------------------------------------------------------

    /** Browse offers every sell order a player may see, newest first: no buy orders, no locked items. */
    @GameTest(template = EMPTY)
    public static void browseOffersEverySellOrderAPlayerMaySee(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture viewer = new CoreFixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 2));
            seller.give(new ItemStack(Items.OAK_LOG, 2));
            seller.deposit(100);
            long cobble = posted(helper, seller, data, Items.COBBLESTONE, false, 5, 2).orderId();
            long logs = posted(helper, seller, data, Items.OAK_LOG, false, 5, 2).orderId();
            posted(helper, seller, data, Items.DIRT, true, 1, 2);
            helper.assertValueEqual(ExchangeService.visibleSellOrders(viewer.player, data).stream().map(ExchangeData.Order::id).toList(),
                    List.of(logs, cobble), "both sell orders, newest first, and no buy order");
            ItemGateService.setTestLocks((player, item) -> player == viewer.player && item == Items.COBBLESTONE);
            try {
                helper.assertValueEqual(ExchangeService.visibleSellOrders(viewer.player, data).stream().map(ExchangeData.Order::id).toList(),
                        List.of(logs), "not the locked item");
            } finally {
                ItemGateService.setTestLocks(null);
            }
        });
        helper.succeed();
    }

    /** The default categories are valid config entries and their tags hold what they should. */
    @GameTest(template = EMPTY)
    public static void theDefaultCategoriesHoldTheirItems(GameTestHelper helper) {
        for (String category : ExchangeConfig.DEFAULT_CATEGORIES) {
            helper.assertTrue(ExchangeSettings.categoryTag(category).isPresent(), category + " is a valid category");
        }
        helper.assertValueEqual(ExchangeSettings.categoryTag("ores_and_minerals"),
                Optional.of(ResourceLocation.fromNamespaceAndPath(ExchangeModule.MOD_ID, "category/ores_and_minerals")), "a plain name is the mod's tag");
        helper.assertValueEqual(ExchangeSettings.categoryTag("c:ingots"), Optional.of(ResourceLocation.parse("c:ingots")), "a full ID is itself");
        helper.assertValueEqual(ExchangeConfig.DEFAULT_CATEGORIES.get(ExchangeConfig.DEFAULT_CATEGORIES.size() - 1), ExchangeSettings.MISCELLANEOUS,
                "Miscellaneous comes last");
        Map<String, List<Item>> expected = Map.of(
                "building_blocks", List.of(Items.OAK_PLANKS, Items.STONE_BRICKS, Items.GLASS),
                "ores_and_minerals", List.of(Items.DIAMOND, Items.IRON_INGOT, Items.RAW_COPPER, Items.COAL),
                "farming_and_food", List.of(Items.BREAD, Items.WHEAT_SEEDS, Items.OAK_SAPLING),
                "weapons_and_armor", List.of(Items.IRON_SWORD, Items.DIAMOND_CHESTPLATE, Items.BOW),
                "tools_and_equipment", List.of(Items.IRON_PICKAXE, Items.SHEARS, Items.COMPASS),
                "mob_drops", List.of(Items.BONE, Items.GUNPOWDER, Items.ENDER_PEARL, Items.ROTTEN_FLESH),
                "redstone_and_technology", List.of(Items.REDSTONE, Items.REPEATER, Items.PISTON),
                "potions_and_brewing", List.of(Items.POTION, Items.BREWING_STAND, Items.NETHER_WART));
        expected.forEach((category, items) -> {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, ExchangeSettings.categoryTag(category).orElseThrow());
            for (Item item : items) {
                helper.assertTrue(new ItemStack(item).is(tag), id(item) + " is in " + category);
            }
        });
        helper.assertTrue(!new ItemStack(Items.DIAMOND).is(TagKey.create(Registries.ITEM, ExchangeSettings.categoryTag("mob_drops").orElseThrow())),
                "a diamond isn't a mob drop");
        helper.succeed();
    }
}
