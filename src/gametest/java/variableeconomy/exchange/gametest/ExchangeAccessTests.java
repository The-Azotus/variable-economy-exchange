package variableeconomy.exchange.gametest;

import variableeconomy.exchange.menu.ExchangeMenu;
import variableeconomy.exchange.block.ExchangeBlocks;
import variableeconomy.exchange.ExchangeSettings;
import variableeconomy.exchange.ExchangeConfig;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.service.Leaderboard;
import variableeconomy.exchange.ExchangeModule;
import variableeconomy.exchange.api.event.ExchangeTradeEvent;
import variableeconomy.exchange.data.ExchangeData;
import variableeconomy.exchange.service.ExchangeResult;
import variableeconomy.exchange.service.ExchangeService;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static variableeconomy.exchange.gametest.CoreTestSupport.*;
import static variableeconomy.exchange.gametest.ExchangeTestSupport.*;

/**
 * The Exchange block, its access mode, the leaderboard's escrow, day counting and trade events.
 *
 * Shared setup is in {@link variableeconomy.exchange.gametest.CoreTestSupport}.
 */
@GameTestHolder(ExchangeModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExchangeAccessTests {
    private ExchangeAccessTests() {
    }


    @GameTest(template = EMPTY)
    public static void leaderboardCountsMoneyTheExchangeHolds(GameTestHelper helper) {
        withExchange(() -> {
            net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
            ExchangeData data = new ExchangeData();
            CoreFixture buyer = new CoreFixture(helper);
            CoreFixture seller = new CoreFixture(helper);
            UUID id = buyer.player.getUUID();
            AccountId own = CoreApi.accounts().personal(id);
            buyer.deposit(100);
            seller.give(new ItemStack(Items.COBBLESTONE, 5));
            Function<UUID, Optional<GameProfile>> known = uuid -> uuid.equals(id)
                    ? Optional.of(buyer.player.getGameProfile()) : Optional.empty();

            long bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 5, 10).orderId();
            posted(helper, seller, data, Items.COBBLESTONE, false, 50, 5);
            helper.assertValueEqual(ExchangeService.held(server, data), Map.of(own, 53L), "the bid's 50 and its tax of 3; not a sell order's items");
            // An order paid from a team account that's gone pays its owner back, so it counts for them.
            ExchangeData.Order fromTeam = new ExchangeData.Order(data.takeId(), id, Optional.of(UUID.randomUUID()), id(Items.COBBLESTONE),
                    true, 3, 4, 0, 0, 12, 0);
            data.add(fromTeam);
            helper.assertValueEqual(ExchangeService.held(server, data), Map.of(own, 65L), "and 12 more");

            Map<UUID, Long> bank = Map.of(id, (long) buyer.balance());
            List<Leaderboard.Entry> ranked = Leaderboard.rank(Leaderboard.withHeld(bank, Map.of(id, 53L)), known, p -> false);
            helper.assertValueEqual(ranked.get(0).balance(), 47L + 53L, "the bank's 47 plus what the bid holds");

            data.remove(fromTeam.id());
            helper.assertValueEqual(ExchangeService.cancel(buyer.player, data, bid).result(), ExchangeResult.OK, "cancel the bid");
            helper.assertValueEqual(ExchangeService.held(server, data), Map.of(), "nothing held now");
            helper.assertValueEqual((long) buyer.balance(), 100L, "the same money, now all in the bank");
            buyer.account().deduct(buyer.balance());
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void returnedMoneyAndItemsAreToldApart(GameTestHelper helper) {
        withExchange(() -> {
            net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
            ExchangeData data = new ExchangeData();
            CoreFixture trader = new CoreFixture(helper);
            trader.deposit(100);
            trader.give(new ItemStack(Items.COBBLESTONE, 4));
            ExchangeService.post(trader.player, data, Items.COBBLESTONE, true, 3, 5, 0);
            ExchangeService.post(trader.player, data, Items.COBBLESTONE, false, 9, 4, 0);
            ExchangeService.closeAll(server, data);
            UUID id = trader.player.getUUID();
            helper.assertValueEqual(data.takeNotices(id), List.of(
                    new ExchangeData.Notice(id, id(Items.COBBLESTONE), ExchangeData.NoticeKind.RETURNED, 0, 16),
                    new ExchangeData.Notice(id, id(Items.COBBLESTONE), ExchangeData.NoticeKind.RETURNED, 4, 0)),
                    "the bid's money and the ask's items, as two notices");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void expiryIsCheckedOncePerDay(GameTestHelper helper) {
        ExchangeData data = new ExchangeData();
        helper.assertTrue(data.expiryCheckDue(5, 3), "first check");
        helper.assertFalse(data.expiryCheckDue(5, 3), "not again the same day");
        helper.assertTrue(data.expiryCheckDue(5, 2), "again when the setting changes");
        helper.assertTrue(data.expiryCheckDue(6, 2), "and on the next day");
        helper.succeed();
    }

    // ---- Exchange events ---------------------------------------------------------------------------------------

    private static ItemStack named(String name, int count) {
        ItemStack stack = new ItemStack(Items.COBBLESTONE, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    @GameTest(template = EMPTY)
    public static void exchangeTradesPostOneEventPerFill(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            CoreFixture seller = new CoreFixture(helper);
            CoreFixture buyer = new CoreFixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 10));
            buyer.deposit(1000);

            List<Event> events = exchangeEventsDuring(() -> posted(helper, seller, data, Items.COBBLESTONE, false, 5, 10));
            helper.assertValueEqual(events, List.of(), "an order that waits trades nothing");
            events = exchangeEventsDuring(() -> posted(helper, buyer, data, Items.COBBLESTONE, true, 7, 4));
            helper.assertValueEqual(events.size(), 1, "one fill: " + events);
            ExchangeTradeEvent fill = (ExchangeTradeEvent) events.get(0);
            helper.assertTrue(fill.getServer() == helper.getLevel().getServer(), "the server");
            helper.assertValueEqual(fill.getBuyer(), buyer.player.getUUID(), "buyer");
            helper.assertValueEqual(fill.getSeller(), seller.player.getUUID(), "seller");
            helper.assertTrue(ItemStack.matches(fill.getItem(), new ItemStack(Items.COBBLESTONE)), "one fresh cobblestone: " + fill.getItem());
            helper.assertValueEqual(fill.getQuantity(), 4, "quantity");
            helper.assertValueEqual(fill.getUnitPrice(), 5L, "the waiting order's price");
            helper.assertValueEqual(fill.getValue(), 20L, "value");
            helper.assertValueEqual(fill.getTax(), 1L, "5% of 20, rounded up");
            helper.assertFalse(fill.isFromListing(), "two orders");
            fill.getItem().setCount(64);
            helper.assertValueEqual(fill.getItem().getCount(), 1, "each call gives a new copy");

            // A sell order filling two waiting bids posts two events.
            CoreFixture other = new CoreFixture(helper);
            other.deposit(1000);
            posted(helper, other, data, Items.COBBLESTONE, true, 3, 2);
            posted(helper, buyer, data, Items.COBBLESTONE, true, 4, 1);
            CoreFixture second = new CoreFixture(helper);
            second.give(new ItemStack(Items.COBBLESTONE, 3));
            events = exchangeEventsDuring(() -> posted(helper, second, data, Items.COBBLESTONE, false, 3, 3));
            List<ExchangeTradeEvent> fills = only(events, ExchangeTradeEvent.class);
            helper.assertValueEqual(fills.size(), 2, "one per waiting order filled: " + events);
            helper.assertValueEqual(fills.get(0).getBuyer(), buyer.player.getUUID(), "the best bid first");
            helper.assertValueEqual(fills.get(0).getUnitPrice(), 4L, "at its price");
            helper.assertValueEqual(fills.get(1).getBuyer(), other.player.getUUID(), "then the next");
            helper.assertValueEqual(fills.get(1).getQuantity(), 2, "for the rest");
            helper.assertTrue(fills.stream().allMatch(e -> e.getSeller().equals(second.player.getUUID())), "all sold by the incoming order");
            helper.assertValueEqual(fills.stream().mapToLong(ExchangeTradeEvent::getTax).sum(),
                    ExchangeService.tax(4, 0.05) + ExchangeService.tax(6, 0.05), "each buy order's tax, rounded once on what it bought");
            helper.assertValueEqual(fills.get(0).getBuyerPaid(), 4L + ExchangeService.tax(4, 0.05), "what the buyer paid: value and tax");

            // Buying from a listing.
            CoreFixture lister = new CoreFixture(helper);
            ItemStack stone = named("Event stone", 8);
            lister.give(stone.copy());
            ExchangeService.Outcome listed = ExchangeService.list(lister.player, data, 0, lister.player.getInventory().items.get(0).copy(),
                    10, 8, 0);
            helper.assertValueEqual(listed.result(), ExchangeResult.OK, "listed");
            events = exchangeEventsDuring(() -> helper.assertValueEqual(ExchangeService.buyListing(buyer.player, data, listed.orderId(), 3, 10).result(),
                    ExchangeResult.OK, "bought from the listing"));
            helper.assertValueEqual(events.size(), 1, "one purchase: " + events);
            ExchangeTradeEvent purchase = (ExchangeTradeEvent) events.get(0);
            helper.assertTrue(purchase.isFromListing(), "from a listing");
            helper.assertValueEqual(purchase.getBuyer(), buyer.player.getUUID(), "buyer");
            helper.assertValueEqual(purchase.getSeller(), lister.player.getUUID(), "seller");
            helper.assertValueEqual(purchase.getQuantity(), 3, "quantity");
            helper.assertValueEqual(purchase.getUnitPrice(), 10L, "the listing's price");
            helper.assertValueEqual(purchase.getValue(), 30L, "value");
            helper.assertValueEqual(purchase.getTax(), ExchangeService.tax(30, 0.05), "tax");
            helper.assertTrue(ItemStack.isSameItemSameComponents(purchase.getItem(), stone) && purchase.getItem().getCount() == 1,
                    "the listed item with its name: " + purchase.getItem());

            events = exchangeEventsDuring(() -> helper.assertValueEqual(ExchangeService.buyListing(lister.player, data, listed.orderId(), 1, 10).result(),
                    ExchangeResult.OWN_LISTING, "refused"));
            helper.assertValueEqual(events, List.of(), "a refused purchase posts nothing");
        });
        helper.succeed();
    }

    // ---- The Exchange: its block, access mode, ids and commands ------------------------------------------------------

    private static EconomyBlock exchange() {
        return new EconomyBlock("exchange", ExchangeBlocks.EXCHANGE.get(), ExchangeBlocks.EXCHANGE_ITEM.get(), ExchangeMenu::forBlock, ExchangeMenu::forCommand,
                net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE);
    }

    @GameTest(template = EMPTY)
    public static void blockOnlyModeKeepsCommandsForOperators(GameTestHelper helper) {
        helper.assertTrue(ExchangeSettings.commandsAllowed(false, false), "everyone, by default");
        helper.assertTrue(!ExchangeSettings.commandsAllowed(true, false), "players need the block");
        helper.assertTrue(ExchangeSettings.commandsAllowed(true, true), "operators keep commands");
        helper.assertTrue(ExchangeSettings.blockOnly() == (ExchangeConfig.accessMode() == ExchangeConfig.AccessMode.BLOCK_ONLY),
                "the exchange reads its own access_mode");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void exchangeScreenClosesWhenYouWalkAwayOrItsBlockBreaks(GameTestHelper helper) {
        assertScreenFollowsTheBlock(helper, exchange());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void exchangeHasARecipeAndFacing(GameTestHelper helper) {
        assertRecipeAndFacing(helper, exchange());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void exchangeIsSolidAndDropsItself(GameTestHelper helper) {
        assertSolidAndDropsItself(helper, exchange());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void commandsSitUnderTheirOwnRoot(GameTestHelper helper) {
        for (String command : List.of("exchange", "exchange orders", "exchange listings", "exchange admin")) {
            helper.assertTrue(commandExists(helper, command), "/" + command + " exists");
        }
        for (String command : List.of("market exchange", "market admin exchange")) {
            helper.assertTrue(!commandExists(helper, command), "/" + command + " is gone");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everythingUsesTheModId(GameTestHelper helper) {
        assertOwnNamespace(helper, ExchangeModule.MOD_ID, List.of("money.variable_economy_exchange.bits"));
        helper.assertTrue(ExchangeData.ID.startsWith("variable_economy_"), "saved data " + ExchangeData.ID + " is named after the mod");
        helper.succeed();
    }
}
