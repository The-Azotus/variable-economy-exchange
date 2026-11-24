package variableeconomy.exchange.command;

import variableeconomy.core.api.CoreApi;
import variableeconomy.exchange.ExchangeSettings;
import variableeconomy.exchange.menu.ExchangeMenu;
import com.mojang.brigadier.CommandDispatcher;
import variableeconomy.exchange.network.ExchangePayloads;
import variableeconomy.exchange.network.ExchangeSync;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import variableeconomy.exchange.data.ExchangeData;
import variableeconomy.exchange.service.ExchangeService;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /exchange [orders|book|buy|sell|list|listings|fill|buylisting|cancel|reprice|reduce|history|collect]}: the exchange
 * screen as commands, for
 * scripting and testing without the screen. {@code /exchange admin [cancel <id>]} shows the whole exchange and
 * its money counts, or closes any order or listing, returning what it holds.
 */
public final class ExchangeCommands {
    private ExchangeCommands() {
    }

    /**
     * Registers {@code /exchange}: bare, it opens the exchange; its subcommands follow the access mode, and
     * {@code /exchange admin} needs operator permission.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal("exchange")
                .executes(ExchangeCommands::open)
                .then(Commands.literal("orders")
                        .requires(ExchangeSettings::commandsAllowed)
                        .executes(ExchangeCommands::list))
                .then(Commands.literal("book")
                        .requires(ExchangeSettings::commandsAllowed)
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .executes(ExchangeCommands::book)))
                .then(Commands.literal("buy")
                        .requires(ExchangeSettings::commandsAllowed)
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                                .executes(ctx -> post(ctx, true))))))
                .then(Commands.literal("sell")
                        .requires(ExchangeSettings::commandsAllowed)
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                                .executes(ctx -> post(ctx, false))))))
                .then(Commands.literal("list")
                        .requires(ExchangeSettings::commandsAllowed)
                        .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                .then(Commands.argument("price", LongArgumentType.longArg(1))
                                        .executes(ExchangeCommands::listHeld))))
                .then(Commands.literal("listings")
                        .requires(ExchangeSettings::commandsAllowed)
                        .executes(ExchangeCommands::listings))
                .then(Commands.literal("fill")
                        .requires(ExchangeSettings::commandsAllowed)
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .executes(ctx -> report(ctx, ExchangeService.fill(ctx.getSource().getPlayerOrException(),
                                                LongArgumentType.getLong(ctx, "id"), IntegerArgumentType.getInteger(ctx, "qty")))))))
                .then(Commands.literal("buylisting")
                        .requires(ExchangeSettings::commandsAllowed)
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .executes(ExchangeCommands::buyListing))))
                .then(Commands.literal("cancel")
                        .requires(ExchangeSettings::commandsAllowed)
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> report(ctx, ExchangeService.cancel(ctx.getSource().getPlayerOrException(),
                                        LongArgumentType.getLong(ctx, "id"))))))
                .then(Commands.literal("reprice")
                        .requires(ExchangeSettings::commandsAllowed)
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .then(Commands.argument("price", LongArgumentType.longArg(1))
                                        .executes(ctx -> report(ctx, ExchangeService.reprice(ctx.getSource().getPlayerOrException(),
                                                LongArgumentType.getLong(ctx, "id"), LongArgumentType.getLong(ctx, "price")))))))
                .then(Commands.literal("reduce")
                        .requires(ExchangeSettings::commandsAllowed)
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .executes(ctx -> report(ctx, ExchangeService.reduce(ctx.getSource().getPlayerOrException(),
                                                LongArgumentType.getLong(ctx, "id"), IntegerArgumentType.getInteger(ctx, "qty")))))))
                .then(Commands.literal("history")
                        .requires(ExchangeSettings::commandsAllowed)
                        .executes(ExchangeCommands::history))
                .then(Commands.literal("collect")
                        .requires(ExchangeSettings::commandsAllowed)
                        .executes(ctx -> report(ctx, ExchangeService.collect(ctx.getSource().getPlayerOrException(), Optional.empty())))
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .executes(ctx -> report(ctx, ExchangeService.collect(ctx.getSource().getPlayerOrException(),
                                        Optional.of(BuiltInRegistries.ITEM.getKey(item(ctx))))))))
                .then(Commands.literal("admin")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ExchangeCommands::adminInfo)
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("id", LongArgumentType.longArg(1))
                                        .executes(ExchangeCommands::adminCancel)))));
    }

    /** Opens the exchange screen; in block-only mode only operators may, and everyone else is told where to go. */
    private static int open(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!ExchangeSettings.commandsAllowed(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.translatable("message.variable_economy_exchange.block_only.exchange"));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ExchangeMenu.open(player);
        ExchangeSync.sendFullState(player);
        return 1;
    }

    private static Item item(CommandContext<CommandSourceStack> ctx) {
        return ItemArgument.getItem(ctx, "item").getItem();
    }

    private static int post(CommandContext<CommandSourceStack> ctx, boolean buy) throws CommandSyntaxException {
        return report(ctx, ExchangeService.post(ctx.getSource().getPlayerOrException(), item(ctx), buy,
                LongArgumentType.getLong(ctx, "price"), IntegerArgumentType.getInteger(ctx, "qty")));
    }

    /** Lists the item in the player's selected hotbar slot. */
    private static int listHeld(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int slot = player.getInventory().selected;
        return report(ctx, ExchangeService.list(player, slot, player.getInventory().items.get(slot).copy(),
                LongArgumentType.getLong(ctx, "price"), IntegerArgumentType.getInteger(ctx, "qty")));
    }

    /** Buys from a listing at the price it's listed at. */
    private static int buyListing(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long id = LongArgumentType.getLong(ctx, "id");
        long price = ExchangeData.get(player.server).findListing(id).map(ExchangeData.Listing::price).orElse(0L);
        return report(ctx, ExchangeService.buyListing(player, id, IntegerArgumentType.getInteger(ctx, "qty"), price));
    }

    private static int listings(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<ExchangeData.Listing> listings = ExchangeService.visibleListings(player, ExchangeData.get(player.server));
        String unit = "bits";
        ctx.getSource().sendSuccess(() -> Component.literal("Exchange listings: " + listings.size()), false);
        for (ExchangeData.Listing l : listings) {
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d: %d × ".formatted(l.id(), l.remaining()))
                    .append(l.stack().getDisplayName())
                    .append(" at %d %s each, by %s".formatted(l.price(), unit, name(player.server, l.owner()))), false);
        }
        return listings.size();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ExchangeData data = ExchangeData.get(player.server);
        String unit = "bits";
        List<ExchangeData.Order> orders = ExchangeService.ordersOf(player.getUUID(), data);
        List<ExchangeData.Listing> listings = ExchangeService.listingsOf(player.getUUID(), data);
        // Orders and listings share the limit, so the count is of both.
        int open = orders.size() + listings.size();
        ctx.getSource().sendSuccess(() -> Component.literal("Exchange (%s): your open orders and listings, %d of %d:".formatted(
                ExchangeSettings.exchangeEnabled() ? "on" : "off", open, ExchangeSettings.maxOpenOrders())), false);
        for (ExchangeData.Order o : orders) {
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d: %s %d × %s at %d %s each, %d filled".formatted(o.id(),
                    o.buy() ? "buy" : "sell", o.quantity(), o.item(), o.price(), unit, o.filled())), false);
        }
        for (ExchangeData.Listing l : listings) {
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d: listed %d × ".formatted(l.id(), l.quantity()))
                    .append(l.stack().getDisplayName())
                    .append(" at %d %s each, %d sold".formatted(l.price(), unit, l.sold())), false);
        }
        if (orders.isEmpty() && listings.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  none"), false);
        }
        Map<ResourceLocation, Long> box = data.box(player.getUUID());
        if (!box.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Collection box: " + box.entrySet().stream()
                    .map(e -> e.getValue() + " × " + e.getKey()).reduce((a, b) -> a + ", " + b).orElse("")), false);
        }
        for (ExchangeData.Parcel parcel : data.parcels(player.getUUID())) {
            ctx.getSource().sendSuccess(() -> Component.literal("  %d × ".formatted(parcel.count())).append(parcel.stack().getDisplayName()), false);
        }
        return orders.size() + listings.size();
    }

    private static int book(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item(ctx));
        ExchangeService.Book book = ExchangeService.book(player, ExchangeData.get(player.server), id, ExchangePayloads.ExchangeBook.DEPTH);
        String unit = "bits";
        ctx.getSource().sendSuccess(() -> Component.literal("Exchange book for " + id + ":"), false);
        for (ExchangeService.BookOrder order : book.asks().reversed()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d: %s sells %d at %d %s%s".formatted(order.id(), order.mine() ? "you" : order.poster(),
                    order.quantity(), order.price(), unit, daysLeft(order))), false);
        }
        for (ExchangeService.BookOrder order : book.bids()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d: %s buys %d at %d %s%s".formatted(order.id(), order.mine() ? "you" : order.poster(),
                    order.quantity(), order.price(), unit, daysLeft(order))), false);
        }
        if (book.asks().isEmpty() && book.bids().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  no orders"), false);
        }
        return book.asks().size() + book.bids().size();
    }

    private static String daysLeft(ExchangeService.BookOrder order) {
        return order.daysLeft() < 0 ? "" : order.daysLeft() == 1 ? ", 1 day left" : ", %d days left".formatted(order.daysLeft());
    }

    private static int report(CommandContext<CommandSourceStack> ctx, ExchangeService.Outcome outcome) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!outcome.ok()) {
            // The same sentences the screen shows.
            ctx.getSource().sendFailure(Component.translatable(
                    "screen.variable_economy_exchange.result." + outcome.result().name().toLowerCase(Locale.ROOT)));
            return 0;
        }
        String unit = "bits";
        // A listing is named as listed (a custom name, say); anything else by its id.
        String item = outcome.listed().isEmpty() ? outcome.item().toString() : outcome.listed().getHoverName().getString();
        String message = switch (outcome.action()) {
            case POST -> "Posted an order for %d × %s: %d filled now (%d %s)%s".formatted(outcome.quantity(), outcome.item(),
                    outcome.filled(), outcome.amount(), unit, outcome.orderId() > 0 ? "; the rest is order #" + outcome.orderId() : "");
            case CANCEL -> !outcome.listed().isEmpty()
                    ? "Cancelled listing #%d; %d × %s went to your collection box".formatted(outcome.orderId(), outcome.quantity(), item)
                    : outcome.amount() > 0
                    ? "Cancelled order #%d; %d %s returned".formatted(outcome.orderId(), outcome.amount(), unit)
                    : "Cancelled order #%d; %d × %s went to your collection box".formatted(outcome.orderId(), outcome.quantity(), outcome.item());
            case COLLECT -> "Took %d item(s) out of your collection box".formatted(outcome.quantity());
            case LIST -> "Listed %d × %s as listing #%d".formatted(outcome.quantity(), item, outcome.orderId());
            case BUY_LISTING -> "Bought %d × %s from listing #%d for %d %s; it's in your collection box".formatted(outcome.quantity(),
                    item, outcome.orderId(), outcome.amount(), unit);
            case FILL -> "Filled %d of order #%d for %s: %d %s".formatted(outcome.quantity(), outcome.orderId(), outcome.item(),
                    outcome.amount(), unit);
            case REPRICE -> !outcome.listed().isEmpty()
                    ? "Listing #%d's price changed".formatted(outcome.orderId())
                    : "Order for %s repriced: %d filled now (%d %s)%s".formatted(outcome.item(), outcome.filled(), outcome.amount(), unit,
                    outcome.orderId() > 0 ? "; order #" + outcome.orderId() + " waits for the rest" : "; nothing left to wait");
            case REDUCE -> !outcome.listed().isEmpty()
                    ? "Listing #%d lowered by %d; they went to your collection box".formatted(outcome.orderId(), outcome.quantity())
                    : outcome.amount() > 0
                    ? "Order #%d lowered by %d; %d %s returned".formatted(outcome.orderId(), outcome.quantity(), outcome.amount(), unit)
                    : "Order #%d lowered by %d; they went to your collection box".formatted(outcome.orderId(), outcome.quantity());
        };
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        if (outcome.action() != ExchangeService.Action.COLLECT) {
            ExchangeSync.broadcastExchange(player.server);
        }
        ExchangeSync.sendState(player);
        return 1;
    }

    /** The player's exchange history, newest first. */
    private static int history(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<ExchangeData.HistoryEntry> entries = ExchangeData.get(player.server).history(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("Exchange history: %d line(s), newest first".formatted(entries.size())), false);
        for (ExchangeData.HistoryEntry e : entries.reversed()) {
            String who = e.counterparty().map(id -> " with " + name(player.server, id)).orElse("");
            String order = e.orderId() > 0 ? " #" + e.orderId() : "";
            ctx.getSource().sendSuccess(() -> Component.literal("  %s %s%s: %d × ".formatted(e.kind().name().toLowerCase(Locale.ROOT),
                            e.side().name().toLowerCase(Locale.ROOT), order, e.units()))
                    .append(e.item().getHoverName())
                    .append(" at %d bits, %d bits%s".formatted(e.price(), e.amount(), who)), false);
        }
        return entries.size();
    }

    private static int adminInfo(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ExchangeData data = ExchangeData.get(server);
        String unit = "bits";
        long money = data.orders().stream().mapToLong(ExchangeData.Order::heldMoney).sum();
        long items = data.orders().stream().filter(o -> !o.buy()).mapToLong(ExchangeData.Order::remaining).sum();
        long listed = data.listings().stream().mapToLong(ExchangeData.Listing::remaining).sum();
        long boxed = data.boxes().values().stream().flatMap(b -> b.values().stream()).mapToLong(Long::longValue).sum()
                + data.allParcels().values().stream().flatMap(List::stream).mapToLong(ExchangeData.Parcel::count).sum();
        long boxes = java.util.stream.Stream.concat(data.boxes().keySet().stream(), data.allParcels().keySet().stream()).distinct().count();
        ctx.getSource().sendSuccess(() -> Component.literal(("Exchange is %s: %d open order(s) holding %d %s and %d item(s); %d listing(s) "
                + "holding %d item(s); %d item(s) in %d collection box(es). Traded so far: %d %s; tax destroyed: %d %s").formatted(
                ExchangeSettings.exchangeEnabled() ? "on" : "off", data.orders().size(), money, unit, items, data.listings().size(), listed,
                boxed, boxes, data.volume(), unit, data.taxCollected(), unit)), false);
        for (ExchangeData.Order o : data.orders()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d %s %d/%d × %s at %d, by %s".formatted(o.id(), o.buy() ? "buy" : "sell",
                    o.filled(), o.quantity(), o.item(), o.price(), name(server, o.owner()))), false);
        }
        for (ExchangeData.Listing l : data.listings()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d listing %d/%d × ".formatted(l.id(), l.sold(), l.quantity()))
                    .append(l.stack().getDisplayName())
                    .append(" at %d, by %s".formatted(l.price(), name(server, l.owner()))), false);
        }
        return data.orders().size() + data.listings().size();
    }

    private static int adminCancel(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ExchangeData data = ExchangeData.get(server);
        long id = LongArgumentType.getLong(ctx, "id");
        Optional<ExchangeData.Order> order = data.find(id);
        Optional<ExchangeData.Listing> listing = data.findListing(id);
        if (order.isEmpty() && listing.isPresent()) {
            ExchangeData.Listing l = listing.get();
            ExchangeService.close(server, data, l);
            ExchangeSync.broadcastExchange(server);
            CoreApi.audit().record(ctx.getSource(), "exchange cancel", "#" + id, "ok", new JsonPrimitive(l.remaining()), null);
            ctx.getSource().sendSuccess(() -> Component.literal("Closed listing #%d (%d × %s at %d by %s); its items went to the owner's box"
                    .formatted(id, l.remaining(), l.item(), l.price(), name(server, l.owner()))), true);
            return 1;
        }
        if (order.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No open exchange order or listing #" + id));
            return 0;
        }
        ExchangeData.Order o = order.get();
        ExchangeService.close(server, data, o);
        ExchangeSync.broadcastExchange(server);
        CoreApi.audit().record(ctx.getSource(), "exchange cancel", "#" + id, "ok", new JsonPrimitive(o.remaining()), null);
        ctx.getSource().sendSuccess(() -> Component.literal("Closed order #%d (%s %d × %s at %d by %s); what it held went back to its owner"
                .formatted(id, o.buy() ? "buy" : "sell", o.remaining(), o.item(), o.price(), name(server, o.owner()))), true);
        return 1;
    }

    /** A player's name, from the server's profile cache when they're offline; their UUID if it doesn't know them. */
    private static String name(MinecraftServer server, UUID id) {
        return ExchangeService.name(server, id).orElse(id.toString());
    }
}
