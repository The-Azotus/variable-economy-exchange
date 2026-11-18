package variableeconomy.exchange.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.exchange.gametest.CoreTestSupport.CoreFixture;
import variableeconomy.core.service.ItemGateService;
import variableeconomy.exchange.ExchangeConfig;
import variableeconomy.exchange.ExchangeModule;
import variableeconomy.exchange.api.event.ExchangeTradeEvent;
import variableeconomy.exchange.data.ExchangeData;
import variableeconomy.exchange.service.ExchangeResult;
import variableeconomy.exchange.service.ExchangeService;

import java.util.List;

import static variableeconomy.exchange.gametest.CoreTestSupport.*;
import static variableeconomy.exchange.gametest.ExchangeTestSupport.*;

/** Filling one chosen order, with automatic matching on or off, and the book listing each order. */
@GameTestHolder(ExchangeModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExchangeFillTests {
    private ExchangeFillTests() {
    }

    private static void withoutMatching(Runnable body) {
        withExchange(() -> {
            boolean before = ExchangeConfig.MATCHING_ENABLED.get();
            ExchangeConfig.MATCHING_ENABLED.set(false);
            try {
                body.run();
            } finally {
                ExchangeConfig.MATCHING_ENABLED.set(before);
            }
        });
    }

    private static long boxed(ExchangeData data, CoreFixture f, net.minecraft.world.item.Item item) {
        return data.box(f.player.getUUID()).getOrDefault(id(item), 0L);
    }

    private static ExchangeService.Outcome fill(CoreFixture f, ExchangeData data, long order, int qty) {
        return ExchangeService.fill(f.player, data, order, qty, 0);
    }

    @GameTest(template = EMPTY)
    public static void withMatchingOffPlayersFillTheOrdersTheyPick(GameTestHelper helper) {
        withoutMatching(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            CoreFixture taker = new CoreFixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 5));
            buyer.deposit(1000);
            taker.deposit(1000);
            taker.give(new ItemStack(Items.COBBLESTONE, 3));

            long ask = posted(helper, seller, data, Items.COBBLESTONE, false, 10, 5).orderId();
            ExchangeService.Outcome bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 12, 5);
            helper.assertValueEqual(bid.filled(), 0, "crossing orders don't trade by themselves");
            helper.assertValueEqual(data.orders().size(), 2, "both wait on the book");

            // Buying from the sell order: 2 × 10 and a tax of 1.
            List<Event> events = exchangeEventsDuring(() -> helper.assertValueEqual(fill(taker, data, ask, 2).amount(), 21L,
                    "pays the value and the tax"));
            helper.assertValueEqual(only(events, ExchangeTradeEvent.class).size(), 1, "one trade");
            helper.assertValueEqual(taker.balance(), 1000 - 21, "taken from the filler");
            helper.assertValueEqual(seller.balance(), 20, "the seller gets the whole price");
            helper.assertValueEqual(boxed(data, taker, Items.COBBLESTONE), 2L, "the units wait in the filler's box");
            helper.assertValueEqual(data.find(ask).orElseThrow().remaining(), 3, "three left");
            helper.assertValueEqual(data.takeNotices(seller.player.getUUID()).size(), 1, "the seller is told");

            // Selling into the buy order: 3 × 12, paid from what it holds.
            long held = data.find(bid.orderId()).orElseThrow().heldMoney();
            helper.assertValueEqual(fill(taker, data, bid.orderId(), 3).amount(), 36L, "the filler gets the bid's price");
            helper.assertValueEqual(taker.balance(), 1000 - 21 + 36, "paid in full");
            helper.assertValueEqual(taker.count(Items.COBBLESTONE), 0L, "the items went");
            helper.assertValueEqual(boxed(data, buyer, Items.COBBLESTONE), 3L, "to the buyer's box");
            ExchangeData.Order rest = data.find(bid.orderId()).orElseThrow();
            helper.assertValueEqual(rest.traded(), 36L, "the bid bought 36");
            helper.assertValueEqual(rest.heldMoney(), held - 36 - ExchangeService.tax(36, 0.05), "its held money paid the value and the tax");
            helper.assertValueEqual(rest.heldMoney(), ExchangeService.needed(rest, 0.05), "and holds just what the rest needs");
            helper.assertValueEqual(data.takeNotices(buyer.player.getUUID()).size(), 1, "the buyer is told");

            // Filling the rest closes the order.
            taker.give(new ItemStack(Items.COBBLESTONE, 2));
            helper.assertValueEqual(fill(taker, data, bid.orderId(), 2).result(), ExchangeResult.OK, "the last two");
            helper.assertTrue(data.find(bid.orderId()).isEmpty(), "the bid is filled");
            helper.assertValueEqual((long) buyer.balance(), 1000 - 60 - ExchangeService.tax(60, 0.05), "the buyer paid 60 and one tax on it");
            helper.assertValueEqual(ExchangeService.held(helper.getLevel().getServer(), data).values().stream().mapToLong(Long::longValue).sum(),
                    0L, "nothing held for it any more");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void fillingIsRefusedWithoutChangingAnything(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture taker = new CoreFixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 5));
            seller.deposit(100);
            taker.deposit(10);
            long ask = posted(helper, seller, data, Items.COBBLESTONE, false, 10, 5).orderId();
            long bid = posted(helper, seller, data, Items.OAK_LOG, true, 4, 5).orderId();

            helper.assertValueEqual(fill(seller, data, ask, 1).result(), ExchangeResult.OWN_ORDER, "one's own order");
            helper.assertValueEqual(fill(taker, data, ask, 6).result(), ExchangeResult.NO_STOCK, "more than it has");
            helper.assertValueEqual(fill(taker, data, ask, 0).result(), ExchangeResult.INVALID_QUANTITY, "nothing");
            helper.assertValueEqual(fill(taker, data, ask, 1).result(), ExchangeResult.NO_FUNDS, "10 and a tax of 1 is more than 10");
            helper.assertValueEqual(fill(taker, data, bid, 1).result(), ExchangeResult.NOT_ENOUGH_ITEMS, "no logs to sell");
            helper.assertValueEqual(fill(taker, data, 999_999, 1).result(), ExchangeResult.ORDER_GONE, "no such order");
            ItemGateService.setTestLocks((player, item) -> player == taker.player && item == Items.COBBLESTONE);
            try {
                helper.assertValueEqual(fill(taker, data, ask, 1).result(), ExchangeResult.NOT_LISTED, "a locked item");
            } finally {
                ItemGateService.setTestLocks(null);
            }
            helper.assertValueEqual(taker.balance(), 10, "nothing taken");
            helper.assertValueEqual(data.find(ask).orElseThrow().remaining(), 5, "nothing sold");
            helper.assertValueEqual(data.find(bid).orElseThrow().remaining(), 5, "nothing bought");
            ExchangeConfig.ENABLED.set(false);
            try {
                helper.assertValueEqual(fill(taker, data, ask, 1).result(), ExchangeResult.EXCHANGE_CLOSED, "the exchange off");
            } finally {
                ExchangeConfig.ENABLED.set(true);
            }
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theBookListsEachOrder(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture a = new CoreFixture(helper);
            CoreFixture b = new CoreFixture(helper);
            a.give(new ItemStack(Items.COBBLESTONE, 5));
            b.give(new ItemStack(Items.COBBLESTONE, 5));
            b.deposit(100);
            long first = posted(helper, a, data, Items.COBBLESTONE, false, 7, 2).orderId();
            long second = posted(helper, b, data, Items.COBBLESTONE, false, 7, 3).orderId();
            long cheap = posted(helper, a, data, Items.COBBLESTONE, false, 6, 1).orderId();
            long bid = posted(helper, b, data, Items.COBBLESTONE, true, 3, 4).orderId();
            ExchangeService.Book book = ExchangeService.book(a.player, data, id(Items.COBBLESTONE), 10);
            helper.assertValueEqual(book.asks().stream().map(ExchangeService.BookOrder::id).toList(), List.of(cheap, first, second),
                    "cheapest first, then oldest, one line per order");
            helper.assertValueEqual(book.asks().stream().map(ExchangeService.BookOrder::quantity).toList(), List.of(1, 2, 3), "their units");
            helper.assertTrue(book.asks().get(0).mine() && !book.asks().get(2).mine(), "the viewer's own marked");
            helper.assertValueEqual(book.asks().get(2).poster(), ExchangeService.name(helper.getLevel().getServer(), b.player.getUUID()).orElse("?"),
                    "who posted it, by name when the server knows it");
            helper.assertValueEqual(book.asks().get(0).daysLeft(), -1L, "posts don't expire here");
            helper.assertValueEqual(book.bids().stream().map(ExchangeService.BookOrder::id).toList(), List.of(bid), "the bid");
            helper.assertValueEqual(ExchangeService.book(a.player, data, id(Items.COBBLESTONE), 2).asks().size(), 2, "at most the depth asked for");
        });
        helper.succeed();
    }
}
