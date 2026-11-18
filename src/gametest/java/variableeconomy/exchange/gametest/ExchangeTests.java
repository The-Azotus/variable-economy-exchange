package variableeconomy.exchange.gametest;

import variableeconomy.core.CoreConfig;
import variableeconomy.core.coin.Coin;
import variableeconomy.core.coin.Coins;
import variableeconomy.core.service.ItemGateService;
import variableeconomy.exchange.ExchangeConfig;
import variableeconomy.exchange.ExchangeModule;
import variableeconomy.exchange.data.ExchangeData;
import variableeconomy.exchange.service.ExchangeResult;
import variableeconomy.exchange.service.ExchangeService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static variableeconomy.exchange.gametest.CoreTestSupport.*;
import static variableeconomy.exchange.gametest.ExchangeTestSupport.*;

/**
 * The player exchange: orders, matching, the collection box and listings.
 *
 * Shared setup is in {@link variableeconomy.exchange.gametest.CoreTestSupport}.
 */
@GameTestHolder(ExchangeModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExchangeTests {
    private ExchangeTests() {
    }


    // ---- Exchange ----------------------------------------------------------------------------------------------

    private static long boxed(ExchangeData data, CoreFixture f, Item item) {
        return data.box(f.player.getUUID()).getOrDefault(id(item), 0L);
    }

    @GameTest(template = EMPTY)
    public static void exchangeTradesAtTheWaitingOrdersPricePlusTheBuyersTax(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 10));
            buyer.deposit(100);

            ExchangeService.Outcome ask = posted(helper, seller, data, Items.COBBLESTONE, false, 5, 10);
            helper.assertValueEqual(ask.filled(), 0, "nothing to trade with yet");
            helper.assertValueEqual(seller.count(Items.COBBLESTONE), 0L, "the order holds the items");
            helper.assertValueEqual(data.find(ask.orderId()).orElseThrow().remaining(), 10, "the order waits on the book");

            ExchangeService.Outcome bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 7, 4);
            helper.assertValueEqual(bid.filled(), 4, "all four traded at once");
            helper.assertValueEqual(bid.amount(), 21L, "at the waiting seller's price, 5 each, and the 5% tax rounded up to 1");
            helper.assertValueEqual(bid.orderId(), 0L, "nothing left to wait");
            helper.assertValueEqual(buyer.balance(), 79, "the buyer paid 21; the 8 bid above the ask, and its tax, came straight back");
            helper.assertValueEqual(boxed(data, buyer, Items.COBBLESTONE), 4L, "bought units wait in the box");
            helper.assertValueEqual(buyer.count(Items.COBBLESTONE), 0L, "not in the inventory until collected");
            helper.assertValueEqual(seller.balance(), 20, "the seller got the whole 20");
            helper.assertValueEqual(data.find(ask.orderId()).orElseThrow().remaining(), 6, "six still for sale");
            helper.assertValueEqual(data.taxCollected(), 1L, "tax destroyed");
            helper.assertValueEqual(data.volume(), 20L, "value traded");
            // Test players aren't online, so the seller is told at their next login.
            helper.assertValueEqual(data.takeNotices(seller.player.getUUID()),
                    List.of(new ExchangeData.Notice(seller.player.getUUID(), id(Items.COBBLESTONE), ExchangeData.NoticeKind.SOLD, 4, 20)),
                    "the seller's notice");
        });
        helper.succeed();
    }

    /** Totals above an int trade whole: balances and payments are longs. */
    @GameTest(template = EMPTY)
    public static void ordersAboveAnIntTradeWhole(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            try {
                long price = 1_000_000_000L;
                seller.give(new ItemStack(Items.COBBLESTONE, 3));
                buyer.account().deposit(4 * price);
                posted(helper, seller, data, Items.COBBLESTONE, false, price, 3);
                ExchangeService.Outcome bid = posted(helper, buyer, data, Items.COBBLESTONE, true, price, 3);
                helper.assertValueEqual(bid.filled(), 3, "all three traded");
                helper.assertValueEqual(bid.amount(), 3 * price + 150_000_000L, "for more than an int holds, with the 5% tax");
                helper.assertValueEqual(buyer.account().balance(), price - 150_000_000L, "the buyer paid it and the tax in one payment");
                helper.assertValueEqual(seller.account().balance(), 3 * price, "the seller got the whole price");
            } finally {
                seller.account().deduct(seller.account().balance());
                buyer.account().deduct(buyer.account().balance());
            }
        });
        helper.succeed();
    }

    /**
     * The buyer pays the tax on top, rounded up once on what a buy order buys in all, so buying in small pieces costs no
     * more; a buy order holds its tax from the start. Each purchase from a listing is taxed on its own.
     */
    @GameTest(template = EMPTY)
    public static void theBuyerPaysTheTaxRoundedOncePerOrder(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 20));
            buyer.deposit(1000);

            // A waiting buy order filled one unit at a time, with a save and reload halfway.
            long bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 1, 20).orderId();
            helper.assertValueEqual(buyer.balance(), 1000 - 21, "it holds 20 and the tax on it, 1 (5% rounded up)");
            helper.assertValueEqual(data.find(bid).orElseThrow().heldMoney(), 21L, "held");
            for (int i = 0; i < 10; i++) {
                posted(helper, seller, data, Items.COBBLESTONE, false, 1, 1);
            }
            data = ExchangeData.fromTag(data.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
            helper.assertValueEqual(data.find(bid).orElseThrow().traded(), 10L, "what it bought so far survives a restart");
            helper.assertValueEqual(data.find(bid).orElseThrow().heldMoney(), 10L, "as does what it holds for the other 10");
            for (int i = 0; i < 10; i++) {
                posted(helper, seller, data, Items.COBBLESTONE, false, 1, 1);
            }
            helper.assertTrue(data.find(bid).isEmpty(), "all twenty bought");
            helper.assertValueEqual(seller.balance(), 20, "the seller gets the whole price");
            helper.assertValueEqual(buyer.balance(), 1000 - 21, "the buyer paid one tax of 1, not 1 on every unit");
            helper.assertValueEqual(data.taxCollected(), 1L, "one bit destroyed");

            // An incoming buy order filling several waiting asks: 3 × 7 = 21, tax 1.05 rounded up to 2, not 1 per ask.
            CoreFixture other = new CoreFixture(helper);
            CoreFixture asker = new CoreFixture(helper);
            asker.give(new ItemStack(Items.COBBLESTONE, 3));
            other.deposit(100);
            for (int i = 0; i < 3; i++) {
                posted(helper, asker, data, Items.COBBLESTONE, false, 7, 1);
            }
            helper.assertValueEqual(posted(helper, other, data, Items.COBBLESTONE, true, 7, 3).amount(), 23L, "21 and a tax of 2");
            helper.assertValueEqual(other.balance(), 77, "taken");
            helper.assertValueEqual(asker.balance(), 21, "the sellers got 21");

            // A listing: each purchase pays its own tax, 1 on a single unit, and 1 on six bought at once.
            ItemStack stone = named("Tax stone", 10);
            CoreFixture lister = new CoreFixture(helper);
            lister.give(stone.copy());
            long listing = listFrom(lister, data, 0, 1, 10).orderId();
            long before = buyer.balance();
            for (int i = 0; i < 4; i++) {
                helper.assertValueEqual(ExchangeService.buyListing(buyer.player, data, listing, 1, 1).amount(), 2L, "one unit and its tax");
            }
            helper.assertValueEqual(ExchangeService.buyListing(buyer.player, data, listing, 6, 1).amount(), 7L, "six and one tax");
            helper.assertValueEqual((long) buyer.balance(), before - 15, "paid");
            helper.assertValueEqual(lister.balance(), 10, "the lister got the whole price");
            helper.assertValueEqual(data.taxCollected(), 1L + 2L + 4L + 1L, "every tax destroyed");
        });
        helper.succeed();
    }

    /** An exchange saved in the older format (version 1) still loads: "team" is the account, and a buy order holds no tax. */
    @GameTest(template = EMPTY)
    public static void aVersionOneExchangeLoads(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture f = new CoreFixture(helper);
            f.deposit(1000);
            f.give(new ItemStack(Items.COBBLESTONE, 5));
            long bid = posted(helper, f, data, Items.COBBLESTONE, true, 10, 10).orderId();
            long ask = posted(helper, f, data, Items.COBBLESTONE, false, 50, 5).orderId();
            CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
            helper.assertValueEqual(ExchangeData.fromTag(saved.copy(), helper.getLevel().registryAccess()).orders(), data.orders(), "version 2 round-trips");

            saved.putInt("version", 1);
            net.minecraft.nbt.ListTag orders = saved.getList("orders", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < orders.size(); i++) {
                CompoundTag o = orders.getCompound(i);
                o.putUUID("team", o.getUUID("account"));
                o.remove("account");
                o.remove("traded");
                o.remove("held");
                if (!o.getBoolean("buy")) {
                    o.putLong("sold_value", 0);
                }
            }
            ExchangeData old = ExchangeData.fromTag(saved, helper.getLevel().registryAccess());
            ExchangeData.Order oldBid = old.find(bid).orElseThrow();
            helper.assertValueEqual(oldBid.account(), Optional.of(f.player.getUUID()), "team read as the account");
            helper.assertValueEqual(oldBid.heldMoney(), 100L, "a version-1 bid held its price × quantity, no tax");
            helper.assertValueEqual(old.find(ask).orElseThrow().traded(), 0L, "a sell order's takings from sold_value");
            f.account().deduct(f.balance());
        });
        helper.succeed();
    }

    /** A buy order that fills below its price gives back what it no longer needs, its tax included; cancelling, the rest. */
    @GameTest(template = EMPTY)
    public static void aBuyOrderHoldsExactlyWhatItStillNeeds(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 10));
            buyer.deposit(10_000);
            posted(helper, seller, data, Items.COBBLESTONE, false, 50, 10);
            // Bid 100 for 30: holds 3,000 + 150 tax. Ten fill at 50: 500 + 25 tax. The other 20 still need 2,000 + what the
            // tax on 2,500 adds to 25, 100.
            ExchangeService.Outcome bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 100, 30);
            helper.assertValueEqual(bid.amount(), 525L, "ten bought, with their tax");
            ExchangeData.Order open = data.find(bid.orderId()).orElseThrow();
            helper.assertValueEqual(open.heldMoney(), 2100L, "holds just what the rest needs");
            helper.assertValueEqual(buyer.balance(), 10_000 - 525 - 2100, "the rest came straight back");
            helper.assertValueEqual(ExchangeService.held(helper.getLevel().getServer(), data).values().stream().mapToLong(Long::longValue).sum(),
                    2100L, "the leaderboard counts it, tax included");
            helper.assertValueEqual(ExchangeService.cancel(buyer.player, data, bid.orderId()).amount(), 2100L, "cancelling returns it");
            helper.assertValueEqual(buyer.balance(), 10_000 - 525, "all of it");
        });
        helper.succeed();
    }

    /** The posting fee is charged once per order or listing, never refunded; short of it, nothing changes. */
    @GameTest(template = EMPTY)
    public static void thePostingFeeIsChargedOnce(GameTestHelper helper) {
        withExchange(() -> {
            long feeBefore = ExchangeConfig.LISTING_FEE.get();
            ExchangeConfig.LISTING_FEE.set(5L);
            try {
                ExchangeData data = new ExchangeData();
                CoreFixture f = new CoreFixture(helper);
                f.deposit(30);
                f.give(new ItemStack(Items.COBBLESTONE, 10));
                // 20 + a tax of 1 + the fee of 5 is 26.
                long bid = posted(helper, f, data, Items.COBBLESTONE, true, 2, 10).orderId();
                helper.assertValueEqual(f.balance(), 4, "escrow, tax and fee in one charge");
                helper.assertValueEqual(post(f, data, Items.COBBLESTONE, false, 3, 10).result(), ExchangeResult.NO_FUNDS,
                        "a sell order needs the fee too");
                helper.assertValueEqual(f.count(Items.COBBLESTONE), 10L, "and keeps its items when short");
                helper.assertValueEqual(ExchangeService.cancel(f.player, data, bid).amount(), 21L, "cancelling returns escrow and tax");
                helper.assertValueEqual(f.balance(), 25, "but not the fee");
                long ask = posted(helper, f, data, Items.COBBLESTONE, false, 3, 10).orderId();
                helper.assertValueEqual(f.balance(), 20, "the fee for the sell order");
                helper.assertValueEqual(f.count(Items.COBBLESTONE), 0L, "its items held");
                ExchangeService.cancel(f.player, data, ask);
                f.account().deduct(f.balance());
                f.deposit(20);
                helper.assertValueEqual(post(f, data, Items.COBBLESTONE, true, 2, 10).result(), ExchangeResult.NO_FUNDS,
                        "a buy order short of escrow, tax and fee");
                helper.assertValueEqual(f.balance(), 20, "takes nothing");
            } finally {
                ExchangeConfig.LISTING_FEE.set(feeBefore);
            }
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void exchangeFillsBestPriceFirstThenOldestFirst(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture dear = new CoreFixture(helper);
            CoreFixture early = new CoreFixture(helper);
            CoreFixture late = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            for (CoreFixture seller : List.of(dear, early, late)) {
                seller.give(new ItemStack(Items.COBBLESTONE, 5));
            }
            buyer.deposit(1000);
            long dearOrder = posted(helper, dear, data, Items.COBBLESTONE, false, 6, 5).orderId();
            long earlyOrder = posted(helper, early, data, Items.COBBLESTONE, false, 5, 5).orderId();
            long lateOrder = posted(helper, late, data, Items.COBBLESTONE, false, 5, 5).orderId();

            // 12 units: 5 from each seller at 5 (the earlier first), then 2 at 6.
            ExchangeService.Outcome bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 6, 12);
            helper.assertValueEqual(bid.filled(), 12, "all twelve traded");
            helper.assertValueEqual(bid.amount(), 66L, "25 + 25 + 12, and a tax of 3.1 rounded up once to 4");
            helper.assertValueEqual(buyer.balance(), 1000 - 66, "the buyer paid what they traded at, not their bid");
            helper.assertValueEqual(early.balance(), 25, "each seller got their whole price");
            helper.assertValueEqual(late.balance(), 25, "the same");
            helper.assertValueEqual(dear.balance(), 12, "and the dearer one too");
            helper.assertTrue(data.find(earlyOrder).isEmpty() && data.find(lateOrder).isEmpty(), "both cheaper orders are gone");
            helper.assertValueEqual(data.find(dearOrder).orElseThrow().remaining(), 3, "three left at 6");
            helper.assertValueEqual(boxed(data, buyer, Items.COBBLESTONE), 12L, "the buyer's box");

            // Time priority on its own: two bids at one price, a seller fills the older first.
            CoreFixture first = new CoreFixture(helper);
            CoreFixture second = new CoreFixture(helper);
            CoreFixture seller = new CoreFixture(helper);
            first.deposit(100);
            second.deposit(100);
            seller.give(new ItemStack(Items.OAK_LOG, 3));
            posted(helper, first, data, Items.OAK_LOG, true, 4, 2);
            long secondOrder = posted(helper, second, data, Items.OAK_LOG, true, 4, 2).orderId();
            posted(helper, seller, data, Items.OAK_LOG, false, 4, 3);
            helper.assertValueEqual(boxed(data, first, Items.OAK_LOG), 2L, "the older bid filled completely");
            helper.assertValueEqual(boxed(data, second, Items.OAK_LOG), 1L, "the newer one got the last unit");
            helper.assertValueEqual(data.find(secondOrder).orElseThrow().remaining(), 1, "and still wants one");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void anIncomingSellOrderGetsTheHigherWaitingBid(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture buyer = new CoreFixture(helper);
            CoreFixture seller = new CoreFixture(helper);
            buyer.deposit(100);
            seller.give(new ItemStack(Items.COBBLESTONE, 10));
            long bidOrder = posted(helper, buyer, data, Items.COBBLESTONE, true, 8, 10).orderId();
            helper.assertValueEqual(buyer.balance(), 16, "the bid holds 80 and its tax of 4");

            ExchangeService.Outcome ask = posted(helper, seller, data, Items.COBBLESTONE, false, 5, 10);
            helper.assertValueEqual(ask.filled(), 10, "it traded at once");
            helper.assertValueEqual(ask.amount(), 80L, "at the bid's 8 each");
            helper.assertValueEqual(seller.balance(), 80, "paid into the seller's bank in full");
            helper.assertValueEqual(buyer.balance(), 16, "the buyer's held money paid for it and the tax");
            helper.assertValueEqual(boxed(data, buyer, Items.COBBLESTONE), 10L, "the buyer's box");
            helper.assertTrue(data.find(bidOrder).isEmpty(), "the bid is filled");
            helper.assertTrue(data.orders().isEmpty(), "nothing waits");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void cancellingReturnsExactlyWhatTheOrderHolds(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture buyer = new CoreFixture(helper);
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture other = new CoreFixture(helper);
            buyer.deposit(100);
            seller.give(new ItemStack(Items.COBBLESTONE, 14));
            long bidOrder = posted(helper, buyer, data, Items.COBBLESTONE, true, 5, 10).orderId();
            posted(helper, seller, data, Items.COBBLESTONE, false, 5, 4);
            helper.assertValueEqual(buyer.balance(), 47, "the bid held 50 and a tax of 3; 21 of it paid for four and their tax");

            helper.assertValueEqual(ExchangeService.cancel(other.player, data, bidOrder).result(), ExchangeResult.NOT_YOUR_ORDER, "someone else's order");
            ExchangeService.Outcome cancelled = ExchangeService.cancel(buyer.player, data, bidOrder);
            helper.assertValueEqual(cancelled.result(), ExchangeResult.OK, "cancelling");
            helper.assertValueEqual(cancelled.amount(), 32L, "the 6 units still wanted held 30, and 2 more of the tax");
            helper.assertValueEqual(buyer.balance(), 79, "100 less the 21 the four cost");
            helper.assertValueEqual(ExchangeService.cancel(buyer.player, data, bidOrder).result(), ExchangeResult.ORDER_GONE, "cancelling twice");
            helper.assertValueEqual(buyer.balance(), 79, "the second cancel returned nothing");

            long askOrder = posted(helper, seller, data, Items.COBBLESTONE, false, 9, 10).orderId();
            helper.assertValueEqual(seller.count(Items.COBBLESTONE), 0L, "all ten held");
            ExchangeService.Outcome returned = ExchangeService.cancel(seller.player, data, askOrder);
            helper.assertValueEqual(returned.quantity(), 10, "ten returned");
            helper.assertValueEqual(boxed(data, seller, Items.COBBLESTONE), 10L, "to the collection box");
            helper.assertTrue(data.orders().isEmpty(), "no orders left");
        });
        helper.succeed();
    }

    /** A post that must be refused and change nothing: balance, main inventory, the book or any box. */
    private static void assertPostRejected(GameTestHelper helper, CoreFixture f, ExchangeData data, ExchangeResult expected,
                                           java.util.function.Supplier<ExchangeService.Outcome> post) {
        List<ItemStack> before = new ArrayList<>();
        f.player.getInventory().items.forEach(s -> before.add(s.copy()));
        int balance = f.balance();
        List<ExchangeData.Order> orders = List.copyOf(data.orders());
        Map<UUID, Map<ResourceLocation, Long>> boxes = Map.copyOf(data.boxes());
        helper.assertValueEqual(post.get().result(), expected, "post result");
        helper.assertValueEqual(f.balance(), balance, "balance after " + expected);
        helper.assertValueEqual(List.copyOf(data.orders()), orders, "orders after " + expected);
        helper.assertValueEqual(Map.copyOf(data.boxes()), boxes, "boxes after " + expected);
        NonNullList<ItemStack> after = f.player.getInventory().items;
        for (int slot = 0; slot < after.size(); slot++) {
            helper.assertTrue(ItemStack.matches(after.get(slot), before.get(slot)), "inventory slot " + slot + " changed after " + expected);
        }
    }

    @GameTest(template = EMPTY)
    public static void rejectedExchangeOrdersChangeNothing(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture f = new CoreFixture(helper);
            f.deposit(100);
            f.give(new ItemStack(Items.COBBLESTONE, 4));
            ItemStack renamed = new ItemStack(Items.COBBLESTONE, 1);
            renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Special"));
            f.give(renamed);

            ExchangeConfig.ENABLED.set(false);
            assertPostRejected(helper, f, data, ExchangeResult.EXCHANGE_CLOSED, () -> post(f, data, Items.COBBLESTONE, true, 1, 1));
            ExchangeConfig.ENABLED.set(true);
            assertPostRejected(helper, f, data, ExchangeResult.NOT_LISTED, () -> post(f, data, Coins.item(Coin.BIT), false, 1, 1));
            assertPostRejected(helper, f, data, ExchangeResult.NOT_LISTED, () -> post(f, data, Items.AIR, true, 1, 1));
            assertPostRejected(helper, f, data, ExchangeResult.INVALID_QUANTITY, () -> post(f, data, Items.COBBLESTONE, true, 1, 0));
            assertPostRejected(helper, f, data, ExchangeResult.INVALID_PRICE, () -> post(f, data, Items.COBBLESTONE, true, 0, 1));
            assertPostRejected(helper, f, data, ExchangeResult.TOO_LARGE, () -> post(f, data, Items.COBBLESTONE, true, 1, 6401));
            assertPostRejected(helper, f, data, ExchangeResult.TOO_LARGE, () -> post(f, data, Items.COBBLESTONE, true, Long.MAX_VALUE, 2));
            assertPostRejected(helper, f, data, ExchangeResult.NO_FUNDS, () -> post(f, data, Items.COBBLESTONE, true, 11, 10));
            // The renamed copy doesn't count.
            assertPostRejected(helper, f, data, ExchangeResult.NOT_ENOUGH_ITEMS, () -> post(f, data, Items.COBBLESTONE, false, 1, 5));

            ItemGateService.setTestLocks((player, item) -> player == f.player && item == Items.COBBLESTONE);
            try {
                assertPostRejected(helper, f, data, ExchangeResult.NOT_LISTED, () -> post(f, data, Items.COBBLESTONE, false, 1, 1));
            } finally {
                ItemGateService.setTestLocks(null);
            }

            ExchangeConfig.MAX_POSTS.set(2);
            posted(helper, f, data, Items.OAK_LOG, true, 1, 1);
            posted(helper, f, data, Items.OAK_LOG, true, 1, 1);
            assertPostRejected(helper, f, data, ExchangeResult.ORDER_LIMIT, () -> post(f, data, Items.OAK_LOG, true, 1, 1));
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aPlayersOwnOrdersNeverTradeWithEachOther(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture f = new CoreFixture(helper);
            CoreFixture other = new CoreFixture(helper);
            f.deposit(100);
            f.give(new ItemStack(Items.COBBLESTONE, 10));
            other.deposit(100);
            long ask = posted(helper, f, data, Items.COBBLESTONE, false, 5, 10).orderId();
            ExchangeService.Outcome bid = posted(helper, f, data, Items.COBBLESTONE, true, 6, 10);
            helper.assertValueEqual(bid.filled(), 0, "no trade with their own ask");
            helper.assertValueEqual(data.taxCollected(), 0L, "so no tax");
            helper.assertValueEqual(f.balance(), 37, "the bid holds 60 and its tax of 3");

            // Anyone else can still buy from that ask.
            posted(helper, other, data, Items.COBBLESTONE, true, 5, 3);
            helper.assertValueEqual(data.find(ask).orElseThrow().remaining(), 7, "three sold to the other player");
            helper.assertValueEqual(f.balance(), 37 + 15, "paid 15 in full");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void unlistedItemsCanBeTraded(GameTestHelper helper) {
        // The exchange never asks the market: sponge, which the market's test catalog doesn't list, trades like anything else.
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            seller.give(new ItemStack(Items.SPONGE, 2));
            buyer.deposit(10);
            posted(helper, seller, data, Items.SPONGE, false, 3, 2);
            helper.assertValueEqual(posted(helper, buyer, data, Items.SPONGE, true, 3, 2).filled(), 2, "traded");
            helper.assertValueEqual(boxed(data, buyer, Items.SPONGE), 2L, "the sponges wait in the box");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void lockedItemsAreHiddenOnTheExchange(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture locked = new CoreFixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 5));
            posted(helper, seller, data, Items.COBBLESTONE, false, 5, 5);
            ItemGateService.setTestLocks((player, item) -> player == locked.player && item == Items.COBBLESTONE);
            try {
                helper.assertTrue(ExchangeService.summaries(locked.player, data).isEmpty(), "the locked player sees no cobblestone");
                ExchangeService.Book book = ExchangeService.book(locked.player, data, id(Items.COBBLESTONE), 8);
                helper.assertTrue(book.asks().isEmpty() && book.bids().isEmpty(), "nor its book");
                helper.assertValueEqual(ExchangeService.summaries(seller.player, data).size(), 1, "others still see it");
            } finally {
                ItemGateService.setTestLocks(null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void collectingMovesOnlyWhatFits(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture f = new CoreFixture(helper);
            helper.assertValueEqual(ExchangeService.collect(f.player, data, Optional.empty()).result(), ExchangeResult.NOT_ENOUGH_ITEMS, "an empty box");
            data.addToBox(f.player.getUUID(), id(Items.COBBLESTONE), 100);
            for (int slot = 0; slot < 35; slot++) {
                f.give(new ItemStack(Items.DIRT, 64));
            }

            ExchangeService.Outcome first = ExchangeService.collect(f.player, data, Optional.empty());
            helper.assertValueEqual(first.result(), ExchangeResult.OK, "the free slot");
            helper.assertValueEqual(first.quantity(), 64, "one stack fits");
            helper.assertValueEqual(f.count(Items.COBBLESTONE), 64L, "in the inventory");
            helper.assertValueEqual(boxed(data, f, Items.COBBLESTONE), 36L, "the rest stays");
            helper.assertValueEqual(ExchangeService.collect(f.player, data, Optional.empty()).result(), ExchangeResult.NO_SPACE, "no room left");
            helper.assertValueEqual(boxed(data, f, Items.COBBLESTONE), 36L, "still there");

            f.clearInventory();
            ExchangeService.Outcome rest = ExchangeService.collect(f.player, data, Optional.of(id(Items.COBBLESTONE)));
            helper.assertValueEqual(rest.quantity(), 36, "the rest");
            helper.assertTrue(data.box(f.player.getUUID()).isEmpty(), "the box is empty");
            helper.assertTrue(data.boxes().isEmpty(), "and forgotten");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void expiryAndClosingReturnWhatOrdersHold(GameTestHelper helper) {
        withExchange(() -> {
            net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
            ExchangeData data = new ExchangeData();
            CoreFixture buyer = new CoreFixture(helper);
            CoreFixture seller = new CoreFixture(helper);
            buyer.deposit(100);
            seller.give(new ItemStack(Items.COBBLESTONE, 4));
            ExchangeService.post(buyer.player, data, Items.COBBLESTONE, true, 3, 5, 0);
            ExchangeService.post(seller.player, data, Items.COBBLESTONE, false, 9, 4, 1);
            helper.assertValueEqual(buyer.balance(), 84, "the bid holds 15 and its tax of 1");

            helper.assertValueEqual(ExchangeService.expire(server, data, 100, 0), 0, "0 days means never");
            helper.assertValueEqual(ExchangeService.expire(server, data, 2, 3), 0, "nothing is 3 days old on day 2");
            helper.assertValueEqual(ExchangeService.expire(server, data, 3, 3), 1, "the bid expires on day 3");
            helper.assertValueEqual(buyer.balance(), 100, "its money is back");
            helper.assertValueEqual(ExchangeService.expire(server, data, 4, 3), 1, "the ask on day 4");
            helper.assertValueEqual(boxed(data, seller, Items.COBBLESTONE), 4L, "its items wait in the box");
            helper.assertValueEqual(data.takeNotices(buyer.player.getUUID()).size(), 1, "the buyer is told");
            helper.assertValueEqual(data.takeNotices(seller.player.getUUID()).size(), 1, "and the seller");

            // Turning the exchange off closes everything the same way.
            ExchangeService.post(buyer.player, data, Items.OAK_LOG, true, 2, 10, 5);
            ExchangeService.collect(seller.player, data, Optional.empty());
            ExchangeService.post(seller.player, data, Items.COBBLESTONE, false, 9, 4, 5);
            ExchangeService.closeAll(server, data);
            helper.assertTrue(data.orders().isEmpty(), "every order closed");
            helper.assertValueEqual(buyer.balance(), 100, "money back");
            helper.assertValueEqual(boxed(data, seller, Items.COBBLESTONE), 4L, "items in the box");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theExchangeSurvivesARestartMidOrder(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            CoreFixture bidder = new CoreFixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 13));
            buyer.deposit(100);
            bidder.deposit(100);
            posted(helper, seller, data, Items.COBBLESTONE, false, 5, 10);
            posted(helper, buyer, data, Items.COBBLESTONE, true, 7, 4);
            long bid = posted(helper, bidder, data, Items.COBBLESTONE, true, 4, 3).orderId();

            ExchangeData restarted = ExchangeData.fromTag(data.save(new CompoundTag(), helper.getLevel().registryAccess()),
                    helper.getLevel().registryAccess());
            helper.assertValueEqual(restarted.orders(), data.orders(), "orders");
            helper.assertValueEqual(restarted.boxes(), data.boxes(), "boxes");
            helper.assertValueEqual(restarted.taxCollected(), data.taxCollected(), "tax");
            helper.assertValueEqual(restarted.volume(), data.volume(), "volume");
            helper.assertValueEqual(restarted.takeNotices(seller.player.getUUID()), data.takeNotices(seller.player.getUUID()), "notices");

            // Trading carries on where it stopped, and new orders never reuse an ID.
            ExchangeService.Outcome more = posted(helper, seller, restarted, Items.COBBLESTONE, false, 4, 3);
            helper.assertValueEqual(more.filled(), 3, "the waiting bid is still there");
            helper.assertTrue(restarted.find(bid).isEmpty(), "and now filled");
            helper.assertValueEqual(restarted.box(bidder.player.getUUID()), Map.of(id(Items.COBBLESTONE), 3L), "the bidder's box");
            long next = restarted.takeId();
            helper.assertTrue(data.orders().stream().allMatch(o -> o.id() < next), "IDs keep counting up");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void hundredsOfRandomExchangeRequestsReconcile(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            List<CoreFixture> players = List.of(new CoreFixture(helper), new CoreFixture(helper), new CoreFixture(helper));
            List<Item> items = List.of(Items.COBBLESTONE, Items.OAK_LOG);
            for (CoreFixture f : players) {
                f.deposit(1000);
                f.give(new ItemStack(Items.COBBLESTONE, 64));
                f.give(new ItemStack(Items.OAK_LOG, 64));
            }
            java.util.Random random = new java.util.Random(17);
            for (int step = 0; step < 400; step++) {
                CoreFixture f = players.get(random.nextInt(players.size()));
                Item item = items.get(random.nextInt(items.size()));
                switch (random.nextInt(5)) {
                    case 0, 1, 2 -> post(f, data, item, random.nextBoolean(), 1 + random.nextInt(10), 1 + random.nextInt(20));
                    case 3 -> {
                        List<ExchangeData.Order> mine = ExchangeService.ordersOf(f.player.getUUID(), data);
                        if (!mine.isEmpty()) {
                            ExchangeService.cancel(f.player, data, mine.get(random.nextInt(mine.size())).id());
                        }
                    }
                    default -> ExchangeService.collect(f.player, data, Optional.empty());
                }
                assertExchangeReconciles(helper, data, players, items, "step " + step);
            }
            helper.assertTrue(data.volume() > 0, "some trades happened");
            for (CoreFixture f : players) {
                ExchangeService.ordersOf(f.player.getUUID(), data).forEach(o -> ExchangeService.cancel(f.player, data, o.id()));
                ExchangeService.collect(f.player, data, Optional.empty());
            }
            assertExchangeReconciles(helper, data, players, items, "after cancelling everything");
            helper.assertTrue(data.orders().isEmpty(), "no orders left");
        });
        helper.succeed();
    }

    /**
     * Money: balances, plus what buy orders hold, plus the tax destroyed, is what the players started with. Items:
     * inventories, plus what sell orders hold, plus collection boxes, is what they started with.
     */
    private static void assertExchangeReconciles(GameTestHelper helper, ExchangeData data, List<CoreFixture> players, List<Item> items, String when) {
        long money = players.stream().mapToLong(CoreFixture::balance).sum();
        long held = data.orders().stream().mapToLong(ExchangeData.Order::heldMoney).sum();
        helper.assertValueEqual(money + held + data.taxCollected(), 1000L * players.size(), "money at " + when);
        for (Item item : items) {
            long carried = players.stream().mapToLong(f -> f.count(item)).sum();
            long selling = data.orders().stream().filter(o -> !o.buy() && o.item().equals(id(item))).mapToLong(ExchangeData.Order::remaining).sum();
            long boxes = players.stream().mapToLong(f -> boxed(data, f, item)).sum();
            helper.assertValueEqual(carried + selling + boxes, 64L * players.size(), id(item) + " at " + when);
        }
    }

    // ---- Exchange listings -------------------------------------------------------------------------------------

    /** {@code count} cobblestone renamed {@code name}: an item only a listing can sell. */
    private static ItemStack named(String name, int count) {
        ItemStack stack = new ItemStack(Items.COBBLESTONE, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    /** A damaged, renamed diamond sword with Sharpness. */
    private static ItemStack fineSword(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.DAMAGE, 40);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Edge"));
        sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3);
        return sword;
    }

    private static long countLike(CoreFixture f, ItemStack like) {
        return f.player.getInventory().items.stream().filter(s -> ItemStack.isSameItemSameComponents(s, like))
                .mapToLong(ItemStack::getCount).sum();
    }

    private static long parcelled(ExchangeData data, CoreFixture f, ItemStack like) {
        return data.parcels(f.player.getUUID()).stream().filter(p -> ItemStack.isSameItemSameComponents(p.stack(), like))
                .mapToLong(ExchangeData.Parcel::count).sum();
    }

    @GameTest(template = EMPTY)
    public static void itemsWithIgnoredDataAreListedNotPutOnTheBook(GameTestHelper helper) {
        if (!CoreConfig.SPEC.isLoaded()) {
            helper.succeed();
            return;
        }
        // custom_name stands in for relics:data (the default), which no mod in the dev runtime adds.
        List<? extends String> before = CoreConfig.IGNORED_COMPONENTS.get();
        CoreConfig.IGNORED_COMPONENTS.set(List.of("minecraft:custom_name"));
        try {
            withExchange(() -> {
                ExchangeData data = new ExchangeData();
                CoreFixture seller = new CoreFixture(helper);
                CoreFixture buyer = new CoreFixture(helper);
                ItemStack levelled = named("Levelled stone", 3);
                seller.give(levelled.copy());
                seller.give(new ItemStack(Items.COBBLESTONE, 2));
                buyer.deposit(100);

                helper.assertValueEqual(ExchangeService.post(seller.player, data, Items.COBBLESTONE, false, 5, 3, 0).result(),
                        ExchangeResult.NOT_ENOUGH_ITEMS, "the book takes only the 2 fresh stones, though the name is ignored for market sales");
                helper.assertValueEqual(countLike(seller, levelled), 3L, "the named stones stay put");
                helper.assertTrue(ExchangeService.listable(levelled), "they can be listed instead");
                helper.assertFalse(ExchangeService.listable(new ItemStack(Items.COBBLESTONE)), "fresh stones can't");

                long id = listFrom(seller, data, 0, 4, 3).orderId();
                helper.assertValueEqual(ExchangeService.buyListing(buyer.player, data, id, 3, 4).result(), ExchangeResult.OK, "bought");
                helper.assertValueEqual(parcelled(data, buyer, levelled), 3L, "the buyer gets them exactly as listed");
            });
        } finally {
            CoreConfig.IGNORED_COMPONENTS.set(before);
        }
        helper.succeed();
    }

    /** Lists from {@code slot}, as the screen does: with the item the player sees there. */
    private static ExchangeService.Outcome listFrom(CoreFixture f, ExchangeData data, int slot, long price, int qty) {
        return ExchangeService.list(f.player, data, slot, f.player.getInventory().items.get(slot).copy(), price, qty, 0);
    }

    @GameTest(template = EMPTY)
    public static void listingSellsAtItsPricePlusTheBuyersTax(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            ItemStack stone = named("Lucky stone", 16);
            seller.give(stone.copy());
            buyer.deposit(100);

            ExchangeService.Outcome listed = listFrom(seller, data, 0, 7, 10);
            helper.assertValueEqual(listed.result(), ExchangeResult.OK, "listed");
            helper.assertTrue(ItemStack.isSameItemSameComponents(listed.listed(), stone), "the outcome names the listed stone");
            long id = listed.orderId();
            helper.assertValueEqual(countLike(seller, stone), 6L, "the listing holds ten");
            helper.assertValueEqual(data.openOrders(seller.player.getUUID()), 1, "it counts as an open order");
            helper.assertTrue(data.orders().isEmpty() && ExchangeService.summaries(buyer.player, data).isEmpty(), "it's not on the book");

            ExchangeService.Outcome bought = ExchangeService.buyListing(buyer.player, data, id, 4, 7);
            helper.assertValueEqual(bought.result(), ExchangeResult.OK, "bought");
            helper.assertValueEqual(bought.amount(), 30L, "4 at 7 each, and a tax of 1.4 rounded up to 2");
            helper.assertValueEqual(buyer.balance(), 70, "the buyer paid 30");
            helper.assertValueEqual(seller.balance(), 28, "the seller got the whole 28");
            helper.assertValueEqual(data.taxCollected(), 2L, "the tax was destroyed");
            helper.assertValueEqual(parcelled(data, buyer, stone), 4L, "the units wait in the buyer's box");
            helper.assertValueEqual(data.findListing(id).orElseThrow().remaining(), 6, "six left");
            helper.assertTrue(ItemStack.isSameItemSameComponents(bought.listed(), stone), "the buyer's outcome names the stone");
            helper.assertValueEqual(data.takeNotices(seller.player.getUUID()), List.of(new ExchangeData.Notice(seller.player.getUUID(),
                    id(Items.COBBLESTONE), ExchangeData.NoticeKind.SOLD, 4, 28, "Lucky stone")), "the seller is told, by the stone's name");

            // They come out exactly as listed, never as plain cobblestone.
            helper.assertValueEqual(ExchangeService.collect(buyer.player, data, Optional.empty()).quantity(), 4, "collected");
            helper.assertValueEqual(countLike(buyer, stone), 4L, "the renamed stones");
            helper.assertValueEqual(buyer.count(Items.COBBLESTONE), 0L, "no plain cobblestone");
            helper.assertTrue(data.parcels(buyer.player.getUUID()).isEmpty(), "the box is empty");

            helper.assertValueEqual(ExchangeService.buyListing(buyer.player, data, id, 6, 7).result(), ExchangeResult.OK, "the rest");
            helper.assertTrue(data.findListing(id).isEmpty(), "sold out, so closed");
            helper.assertValueEqual(ExchangeService.cancel(seller.player, data, id).result(), ExchangeResult.ORDER_GONE, "nothing left to cancel");
            helper.assertValueEqual(data.openOrders(seller.player.getUUID()), 0, "no longer counted");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void cancellingAListingReturnsItsItemsToTheBox(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture other = new CoreFixture(helper);
            ItemStack sword = fineSword(helper);
            seller.give(sword.copy());
            long id = listFrom(seller, data, 0, 500, 1).orderId();
            helper.assertValueEqual(countLike(seller, sword), 0L, "the listing holds the sword");

            helper.assertValueEqual(ExchangeService.cancel(other.player, data, id).result(), ExchangeResult.NOT_YOUR_ORDER, "someone else's listing");
            ExchangeService.Outcome cancelled = ExchangeService.cancel(seller.player, data, id);
            helper.assertValueEqual(cancelled.result(), ExchangeResult.OK, "cancelled");
            helper.assertValueEqual(cancelled.quantity(), 1, "one sword back");
            helper.assertTrue(ItemStack.isSameItemSameComponents(cancelled.listed(), sword), "the outcome says it was the listed sword");
            helper.assertValueEqual(parcelled(data, seller, sword), 1L, "in the box");
            long parcel = data.parcels(seller.player.getUUID()).get(0).id();
            helper.assertValueEqual(ExchangeService.collectParcel(seller.player, data, parcel).quantity(), 1, "taken out");
            helper.assertTrue(ItemStack.matches(seller.player.getInventory().items.get(0), sword), "exactly the sword that was listed");
            helper.assertTrue(data.allParcels().isEmpty(), "the box is empty");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void refusedListingRequestsChangeNothing(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture f = new CoreFixture(helper);
            CoreFixture other = new CoreFixture(helper);
            CoreFixture poor = new CoreFixture(helper);
            f.give(new ItemStack(Items.COBBLESTONE, 5));
            f.give(named("Odd", 5));
            ItemStack full = new ItemStack(Items.SHULKER_BOX);
            full.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT))));
            f.give(full);
            f.give(fineSword(helper));
            ItemStack coin = new ItemStack(Coins.item(Coin.BIT));
            coin.set(DataComponents.CUSTOM_NAME, Component.literal("Lucky bit"));
            f.give(coin);
            f.deposit(100);
            other.deposit(100);

            assertListRejected(helper, f, data, ExchangeResult.NOT_LISTED, 0, 5, 1, "a plain item goes on the book");
            assertListRejected(helper, f, data, ExchangeResult.NOT_LISTED, 2, 5, 1, "a container with something inside");
            assertListRejected(helper, f, data, ExchangeResult.NOT_LISTED, 4, 5, 1, "a coin, even a renamed one");
            helper.assertValueEqual(ExchangeService.list(f.player, data, 1, new ItemStack(Items.COBBLESTONE), 5, 1, 0).result(),
                    ExchangeResult.NOT_ENOUGH_ITEMS, "the slot no longer holds what the player saw");
            assertListRejected(helper, f, data, ExchangeResult.NOT_ENOUGH_ITEMS, 10, 5, 1, "an empty slot");
            assertListRejected(helper, f, data, ExchangeResult.INVALID_QUANTITY, 1, 5, 0, "no quantity");
            assertListRejected(helper, f, data, ExchangeResult.INVALID_PRICE, 1, 0, 1, "no price");
            assertListRejected(helper, f, data, ExchangeResult.TOO_LARGE, 3, 5, 2, "more than one stack of swords");
            assertListRejected(helper, f, data, ExchangeResult.TOO_LARGE, 1, Long.MAX_VALUE, 2, "a total too large to count");
            assertListRejected(helper, f, data, ExchangeResult.NOT_ENOUGH_ITEMS, 1, 5, 6, "more than held");
            ExchangeConfig.MAX_POSTS.set(1);
            long order = posted(helper, f, data, Items.OAK_LOG, true, 1, 1).orderId();
            assertListRejected(helper, f, data, ExchangeResult.ORDER_LIMIT, 1, 5, 1, "orders and listings share the limit");
            ExchangeService.cancel(f.player, data, order);
            ExchangeConfig.MAX_POSTS.set(10);
            ExchangeConfig.ENABLED.set(false);
            assertListRejected(helper, f, data, ExchangeResult.EXCHANGE_CLOSED, 1, 5, 1, "the exchange is off");
            ExchangeConfig.ENABLED.set(true);

            long id = listFrom(f, data, 1, 5, 5).orderId();
            assertBuyRejected(helper, f, data, ExchangeResult.OWN_LISTING, id, 1, 5);
            assertBuyRejected(helper, other, data, ExchangeResult.INVALID_QUANTITY, id, 0, 5);
            assertBuyRejected(helper, other, data, ExchangeResult.NO_STOCK, id, 6, 5);
            assertBuyRejected(helper, other, data, ExchangeResult.PRICE_CHANGED, id, 1, 4);
            assertBuyRejected(helper, other, data, ExchangeResult.ORDER_GONE, id + 1000, 1, 5);
            assertBuyRejected(helper, poor, data, ExchangeResult.NO_FUNDS, id, 1, 5);
            ExchangeConfig.ENABLED.set(false);
            assertBuyRejected(helper, other, data, ExchangeResult.EXCHANGE_CLOSED, id, 1, 5);
        });
        helper.succeed();
    }

    /** A listing request that must be refused and change nothing: inventory and listings stay as they were. */
    private static void assertListRejected(GameTestHelper helper, CoreFixture f, ExchangeData data, ExchangeResult expected, int slot, long price, int qty,
                                           String why) {
        List<ItemStack> before = new ArrayList<>();
        f.player.getInventory().items.forEach(stack -> before.add(stack.copy()));
        List<ExchangeData.Listing> listings = List.copyOf(data.listings());
        helper.assertValueEqual(listFrom(f, data, slot, price, qty).result(), expected, why);
        NonNullList<ItemStack> after = f.player.getInventory().items;
        for (int i = 0; i < after.size(); i++) {
            helper.assertTrue(ItemStack.matches(after.get(i), before.get(i)), "slot " + i + " changed: " + why);
        }
        helper.assertValueEqual(data.listings(), listings, "listings changed: " + why);
    }

    /** A purchase from a listing that must be refused and change nothing: balance, listings and box. */
    private static void assertBuyRejected(GameTestHelper helper, CoreFixture f, ExchangeData data, ExchangeResult expected, long id, int qty, long price) {
        int balance = f.balance();
        List<ExchangeData.Listing> listings = List.copyOf(data.listings());
        int parcels = data.parcels(f.player.getUUID()).size();
        helper.assertValueEqual(ExchangeService.buyListing(f.player, data, id, qty, price).result(), expected, "buying");
        helper.assertValueEqual(f.balance(), balance, "balance after " + expected);
        helper.assertValueEqual(data.listings(), listings, "listings after " + expected);
        helper.assertValueEqual(data.parcels(f.player.getUUID()).size(), parcels, "box after " + expected);
    }

    @GameTest(template = EMPTY)
    public static void listingsSurviveARestartWithTheirComponents(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            ItemStack sword = fineSword(helper);
            ItemStack stone = named("Keepsake", 8);
            seller.give(sword.copy());
            seller.give(stone.copy());
            buyer.deposit(1000);
            long swordId = listFrom(seller, data, 0, 300, 1).orderId();
            long stoneId = listFrom(seller, data, 1, 9, 5).orderId();
            ExchangeService.buyListing(buyer.player, data, stoneId, 2, 9);

            ExchangeData restarted = ExchangeData.fromTag(data.save(new CompoundTag(), helper.getLevel().registryAccess()),
                    helper.getLevel().registryAccess());
            helper.assertValueEqual(restarted.listings().size(), 2, "both listings");
            for (int i = 0; i < 2; i++) {
                ExchangeData.Listing was = data.listings().get(i);
                ExchangeData.Listing is = restarted.listings().get(i);
                helper.assertTrue(was.id() == is.id() && was.owner().equals(is.owner()) && was.price() == is.price()
                        && was.quantity() == is.quantity() && was.sold() == is.sold(), "listing " + was.id());
                helper.assertTrue(ItemStack.matches(was.stack(), is.stack()), "with its enchantments, damage and name");
            }
            helper.assertValueEqual(parcelled(restarted, buyer, stone), 2L, "the buyer's box");
            helper.assertValueEqual(restarted.openOrders(seller.player.getUUID()), 2, "still counted");

            ExchangeService.Outcome bought = ExchangeService.buyListing(buyer.player, restarted, swordId, 1, 300);
            helper.assertValueEqual(bought.result(), ExchangeResult.OK, "buying carries on");
            helper.assertValueEqual(parcelled(restarted, buyer, sword), 1L, "the sword, exactly");

            // An item whose mod is gone is kept as saved, so it comes back with the mod.
            CompoundTag saved = restarted.save(new CompoundTag(), helper.getLevel().registryAccess());
            CompoundTag lost = saved.getList("listings", Tag.TAG_COMPOUND).getCompound(0).copy();
            lost.getCompound("stack").putString("id", "missing_mod:relic");
            lost.putLong("id", 777);
            saved.getList("listings", Tag.TAG_COMPOUND).add(lost);
            ExchangeData loaded = ExchangeData.fromTag(saved, helper.getLevel().registryAccess());
            helper.assertTrue(loaded.findListing(777).isEmpty(), "it can't be offered");
            helper.assertValueEqual(loaded.listings().size(), 1, "the readable listing is");
            CompoundTag again = loaded.save(new CompoundTag(), helper.getLevel().registryAccess());
            helper.assertValueEqual(again.getList("unreadable", Tag.TAG_COMPOUND).size(), 1, "but it's saved again");
            helper.assertValueEqual(again.getList("unreadable", Tag.TAG_COMPOUND).getCompound(0).getLong("id"), 777L, "untouched");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void listingsExpireAndCloseLikeOrders(GameTestHelper helper) {
        withExchange(() -> {
            net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            ItemStack old = named("Old", 3);
            ItemStack fresh = named("Fresh", 2);
            seller.give(old.copy());
            seller.give(fresh.copy());
            ExchangeService.list(seller.player, data, 0, old, 5, 3, 0);
            ExchangeService.list(seller.player, data, 1, fresh, 5, 2, 2);

            helper.assertValueEqual(ExchangeService.expire(server, data, 2, 3), 0, "nothing is 3 days old on day 2");
            helper.assertValueEqual(ExchangeService.expire(server, data, 3, 3), 1, "the old listing expires on day 3");
            helper.assertValueEqual(parcelled(data, seller, old), 3L, "its items wait in the box");
            // The notice says it was a listing, names the item as listed, and survives a restart.
            ExchangeData restarted = ExchangeData.fromTag(data.save(new CompoundTag(), helper.getLevel().registryAccess()),
                    helper.getLevel().registryAccess());
            List<ExchangeData.Notice> told = List.of(new ExchangeData.Notice(seller.player.getUUID(), id(Items.COBBLESTONE),
                    ExchangeData.NoticeKind.LISTING_RETURNED, 3, 0, "Old"));
            helper.assertValueEqual(restarted.takeNotices(seller.player.getUUID()), told, "kept across a restart");
            helper.assertValueEqual(data.takeNotices(seller.player.getUUID()), told, "the seller is told");

            ExchangeService.closeAll(server, data);
            helper.assertTrue(data.listings().isEmpty(), "turning the exchange off closes every listing");
            helper.assertValueEqual(parcelled(data, seller, fresh), 2L, "items in the box");
            ExchangeService.collect(seller.player, data, Optional.empty());
            helper.assertTrue(countLike(seller, old) == 3 && countLike(seller, fresh) == 2, "and back in the inventory");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void randomListingRequestsReconcile(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            List<CoreFixture> players = List.of(new CoreFixture(helper), new CoreFixture(helper), new CoreFixture(helper));
            List<ItemStack> kinds = List.of(named("Charm", 1), named("Totem", 1));
            for (CoreFixture f : players) {
                f.deposit(1000);
                kinds.forEach(kind -> f.give(kind.copyWithCount(64)));
            }
            java.util.Random random = new java.util.Random(23);
            for (int step = 0; step < 300; step++) {
                CoreFixture f = players.get(random.nextInt(players.size()));
                switch (random.nextInt(5)) {
                    case 0, 1 -> {
                        ItemStack kind = kinds.get(random.nextInt(kinds.size()));
                        NonNullList<ItemStack> items = f.player.getInventory().items;
                        for (int slot = 0; slot < items.size(); slot++) {
                            if (ItemStack.isSameItemSameComponents(items.get(slot), kind)) {
                                listFrom(f, data, slot, 1 + random.nextInt(10), 1 + random.nextInt(20));
                                break;
                            }
                        }
                    }
                    case 2 -> {
                        List<ExchangeData.Listing> open = data.listings();
                        if (!open.isEmpty()) {
                            ExchangeData.Listing l = open.get(random.nextInt(open.size()));
                            ExchangeService.buyListing(f.player, data, l.id(), 1 + random.nextInt(l.remaining() + 1),
                                    random.nextInt(10) == 0 ? l.price() + 1 : l.price());
                        }
                    }
                    case 3 -> {
                        List<ExchangeData.Listing> mine = ExchangeService.listingsOf(f.player.getUUID(), data);
                        if (!mine.isEmpty()) {
                            ExchangeService.cancel(f.player, data, mine.get(random.nextInt(mine.size())).id());
                        }
                    }
                    default -> ExchangeService.collect(f.player, data, Optional.empty());
                }
                assertListingsReconcile(helper, data, players, kinds, "step " + step);
            }
            helper.assertTrue(data.volume() > 0, "some sales happened");
            for (CoreFixture f : players) {
                ExchangeService.listingsOf(f.player.getUUID(), data).forEach(l -> ExchangeService.cancel(f.player, data, l.id()));
                ExchangeService.collect(f.player, data, Optional.empty());
            }
            assertListingsReconcile(helper, data, players, kinds, "after cancelling everything");
            helper.assertTrue(data.listings().isEmpty() && data.allParcels().isEmpty(), "nothing left on the exchange");
        });
        helper.succeed();
    }

    /**
     * Money: balances plus the tax destroyed is what the players started with (listings hold no money). Items:
     * inventories, plus what listings hold, plus boxes, is what they started with, for each kind.
     */
    private static void assertListingsReconcile(GameTestHelper helper, ExchangeData data, List<CoreFixture> players, List<ItemStack> kinds, String when) {
        long money = players.stream().mapToLong(CoreFixture::balance).sum();
        helper.assertValueEqual(money + data.taxCollected(), 1000L * players.size(), "money at " + when);
        for (ItemStack kind : kinds) {
            long carried = players.stream().mapToLong(f -> countLike(f, kind)).sum();
            long listed = data.listings().stream().filter(l -> ItemStack.isSameItemSameComponents(l.stack(), kind))
                    .mapToLong(ExchangeData.Listing::remaining).sum();
            long boxes = players.stream().mapToLong(f -> parcelled(data, f, kind)).sum();
            helper.assertValueEqual(carried + listed + boxes, 64L * players.size(), kind.getHoverName().getString() + " at " + when);
        }
    }

    /** The exchange keeps its own day count for expiry, by the board's rules: sleeping counts, setting time back doesn't. */
    @GameTest(template = EMPTY)
    public static void exchangeCountsItsOwnDays(GameTestHelper helper) {
        ExchangeData data = new ExchangeData();
        data.observe(10);
        helper.assertValueEqual(data.day(), 10L, "starts at the world's day");
        data.observe(12);
        helper.assertValueEqual(data.day(), 12L, "a night slept through counts");
        data.observe(3);
        helper.assertValueEqual(data.day(), 12L, "setting the time back doesn't");
        data.observe(4);
        helper.assertValueEqual(data.day(), 13L, "days after that count again");
        ExchangeData restarted = ExchangeData.fromTag(data.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(restarted.day(), 13L, "the day count survives a restart");
        restarted.observe(5);
        helper.assertValueEqual(restarted.day(), 14L, "and goes on from there");
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        helper.assertValueEqual(ExchangeService.today(server), ExchangeData.get(server).day(), "today is the world exchange's count");
        helper.succeed();
    }
}
