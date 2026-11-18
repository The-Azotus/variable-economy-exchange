package variableeconomy.exchange.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.Event;
import variableeconomy.exchange.gametest.CoreTestSupport.CoreFixture;
import variableeconomy.exchange.ExchangeConfig;
import variableeconomy.exchange.api.event.ExchangeTradeEvent;
import variableeconomy.exchange.data.ExchangeData;
import variableeconomy.exchange.service.ExchangeResult;
import variableeconomy.exchange.service.ExchangeService;

import java.util.List;

import static variableeconomy.exchange.gametest.CoreTestSupport.id;
import static variableeconomy.exchange.gametest.CoreTestSupport.listenFor;
import static variableeconomy.exchange.gametest.CoreTestSupport.recordEvents;

/** What the exchange's GameTests share: the exchange turned on, posting orders, and the exchange's events. */
public final class ExchangeTestSupport {
    private ExchangeTestSupport() {
    }

    /**
     * Runs {@code body} with the exchange on, a 5% buyer's tax, 10 open orders each, no expiry, no posting fee and orders
     * of up to 6,400, then puts the config back. Tests use exchanges of their own ({@code new ExchangeData()}), never the world's.
     */
    public static void withExchange(Runnable body) {
        boolean enabled = ExchangeConfig.ENABLED.get();
        double tax = ExchangeConfig.BUYER_TAX_PERCENT.get();
        long fee = ExchangeConfig.LISTING_FEE.get();
        int orders = ExchangeConfig.MAX_POSTS.get();
        int expiry = ExchangeConfig.DURATION_DAYS.get();
        int size = ExchangeConfig.MAX_ORDER_SIZE.get();
        ExchangeConfig.ENABLED.set(true);
        ExchangeConfig.BUYER_TAX_PERCENT.set(5.0);
        ExchangeConfig.LISTING_FEE.set(0L);
        ExchangeConfig.MAX_POSTS.set(10);
        ExchangeConfig.DURATION_DAYS.set(0);
        ExchangeConfig.MAX_ORDER_SIZE.set(6400);
        try {
            body.run();
        } finally {
            ExchangeConfig.ENABLED.set(enabled);
            ExchangeConfig.BUYER_TAX_PERCENT.set(tax);
            ExchangeConfig.LISTING_FEE.set(fee);
            ExchangeConfig.MAX_POSTS.set(orders);
            ExchangeConfig.DURATION_DAYS.set(expiry);
            ExchangeConfig.MAX_ORDER_SIZE.set(size);
        }
    }

    public static ExchangeService.Outcome post(CoreFixture f, ExchangeData data, Item item, boolean buy, long price, int qty) {
        return ExchangeService.post(f.player, data, item, buy, price, qty, 0);
    }

    public static ExchangeService.Outcome posted(GameTestHelper helper, CoreFixture f, ExchangeData data, Item item, boolean buy, long price, int qty) {
        ExchangeService.Outcome outcome = post(f, data, item, buy, price, qty);
        helper.assertValueEqual(outcome.result(), ExchangeResult.OK, "posting " + (buy ? "buy " : "sell ") + qty + " × " + id(item) + " at " + price);
        return outcome;
    }

    /** The exchange's events posted while {@code body} runs, in order (with any other module's its tests listen for). */
    public static List<Event> exchangeEventsDuring(Runnable body) {
        listenFor(ExchangeTradeEvent.class);
        return recordEvents(body);
    }
}
