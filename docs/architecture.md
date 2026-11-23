# Architecture

A map of the exchange's code for contributors and reviewers. For what the mod does, read the [admin guide](admin-guide.md) and the [player guide](player-guide.md) first: the code follows them closely, and their terms (order, book, fill, listing, collection box) are the ones used here. If you're writing a separate mod that uses this one, read the [add-on guide](api.md) instead.

The exchange (`variable_economy_exchange`) is one of four Variable Economy mods. It requires core (`variable_economy`, [variable-economy-core](https://github.com/The-Azotus/variable-economy-core)), which holds the bank, accounts, coins, the item gate and the audit log, and uses it only through core's API. It works with or without the other add-ons, the market and contracts, and knows nothing of them.

## Building and testing

Java 21 and NeoForge 21.1 for Minecraft 1.21.1. Gradle's toolchain finds Java 21 or downloads it, and the Gradle wrapper is in the repository.

The build is in `buildSrc`'s convention plugin, `variable-economy.mod` (`buildSrc/src/main/groovy/variable-economy.mod.gradle`): NeoForge, Parchment, the repositories, the optional mods, `mods.toml`, the GameTests, `checkModuleBoundaries`, the javadoc and the publication. Each Variable Economy mod's repository holds the same file. `gradle.properties` says which mod this is (`module`, `mod_id`, `mod_name`, `mod_version`) and which core it builds against. `src/main/templates/META-INF/neoforge.mods.toml` is filled in from it, requiring core from `core_version` up to the next minor version, loaded first.

**Core.** The exchange builds against core, which `gradle.properties` names:

- `core_source=curseforge` (the default) uses the released core that `core_curseforge_project` and `core_curseforge_file` name, through [CurseMaven](https://www.cursemaven.com/): for example, core 0.6.1 is `curse.maven:variable-economy-1818087:9710072`. This mod requires core 0.6.2 or later, so until that core's file is set here, build with `-Pcore_source=local`.
- `core_source=local` uses `variableeconomy:core:<core_version>` from your local Maven instead, for building against a core that isn't released: clone [variable-economy-core](https://github.com/The-Azotus/variable-economy-core), check out what you want, and run `gradlew publishToMavenLocal` there first. `-Pcore_source=local` sets it for one build.

The main code compiles against core (`compileOnly`); the GameTests get it as an implementation dependency, and the dev runs load it.

**Tasks.**

- `gradlew build` compiles, runs `checkModuleBoundaries` (see [Modules and their boundaries](#modules-and-their-boundaries)), and builds the jar, the sources jar and the javadoc jar in `build/libs/`. The javadoc covers only `variableeconomy.exchange.api`, with doclint on, so a broken link or bad HTML in its javadoc fails the build.
- `gradlew runGameTestServer` runs the exchange's GameTests (38 today) with core loaded, then `checkGameTestLog`. That fails if the run loaded other mods of ours than core and the exchange, or logged an error, or a warning from our own code, before its tests began; its report is `build/reports/gameTestLog.txt`. The run loads core's optional mods (FTB Teams, ProgressiveStages and KubeJS); `-PwithoutMods=all` (or a comma-separated list of `ftbteams`, `progressivestages`, `kubejs`) leaves them out, with a test world of its own.
- `gradlew runClient` and `runServer` start a dev client or server with core and the exchange, in `run/`.
- `gradlew publishToMavenLocal` publishes `variableeconomy:exchange:<mod_version>` with its sources and javadoc jars. The POM names no dependencies: an add-on declares core itself.

The tests live in their own source set, `src/gametest`, and never ship in the jar. For a Gradle composite build that runs several Variable Economy mods together, the build also offers the mod with its GameTests as one jar (the capability `variableeconomy:exchange-gametest`); it's never published.

The test world persists between runs (`run-gametest/`, and `run-gametest-without-<mods>/` for runs without the optional mods), so tests undo what they change (config values) and compare money relatively. Each run's `config/` starts from `src/gametest/config/`: a ProgressiveStages stage locking the dragon egg, which test players never have, so that mod doesn't write its own example stages and the tests can check a real lock.

**Writing a test.** Put it in `src/gametest/java/variableeconomy/exchange/gametest/`. Each class is marked `@GameTestHolder(ExchangeModule.MOD_ID)` and `@PrefixGameTestTemplate(false)` and imports `CoreTestSupport.*` and `ExchangeTestSupport.*`; each test is a static `@GameTest(template = EMPTY)` method that ends with `helper.succeed()`. The mod ships the `empty` structure under its own namespace, since NeoForge looks a test's template up in its holder's namespace. From the support classes:

- `new CoreFixture(helper)` gives a fresh player with an empty inventory and an empty bank account.
- `withExchange(body)` runs `body` with the exchange on, a 5% buyer's tax, 10 open orders each, no expiry, no posting fee and orders of up to 6,400, then puts the config back.
- Tests use exchanges of their own (`new ExchangeData()`), never the world's; `post` and `posted` post an order to one.
- `exchangeEventsDuring(body)` records the exchange's events while `body` runs.
- `assertScreenFollowsTheBlock`, `assertRecipeAndFacing`, `assertSolidAndDropsItself` and `assertOwnNamespace` check the block and the lang file.
- A test that writes files makes its own folder with `Files.createTempDirectory` and removes it with `deleteTree` in a `finally`.

`CoreTestSupport` is the exchange's own copy of what every module's tests share, since core's jar holds no test code. The tests may reach into core's internals (the fixture uses core's bank and account services), which core's jar holds.

## Packages

All code is under `variableeconomy.exchange`.

### In every module

Each Variable Economy mod's package has the same shape:

- `ExchangeModule`: the mod's `@Mod` class, with `MOD_ID` and `LOGGER`. Its constructor registers the config, the net-worth contributor, the menu, the block, the packets, the commands, and its server events and tick work. `ExchangeClient` is its client-only `@Mod` class: the screen.
- `ExchangeConfig`: the mod's server config file, `variable_economy_exchange-server.toml`. Its `install` fills `ExchangeSettings`, which the rest of the module reads. `ExchangeSettings` also decides whether a player may use the module's commands under its `access_mode`.
- One block and one screen: `block` (the block and its registration), `menu` (the menu the screen runs in), `network` (`ExchangePayloads` with every packet, `ExchangeNetwork` registering them, `ExchangeSync` with what each player is sent and when, and the request handlers) and `client` (the screen, the client's copy of the module's state, and the module's own copy of small helpers such as `PriceFormat`). No screen code is shared between modules.
- `service` for the rules, `data` for what's saved, `command` for its commands, and its own event poster and result enum.

### The exchange's packages

| Package | What's in it |
| --- | --- |
| `api.event` | `ExchangeTradeEvent`. See [The add-on API](#the-add-on-api). |
| `service` | `ExchangeService` (orders, listings, matching, filling one picked order, the buyer's tax, the posting fee, the collection box, expiry; registers a net-worth contributor for the money its buy orders hold), `ExchangeResult`, `ExchangeEvents`. |
| `data` | `ExchangeData`: orders, listings, collection boxes, notices for offline players and the money counts, saved with the world in one file. It also keeps the exchange's own day count for expiry. |
| `block`, `menu`, `network`, `client` | The Exchange block (`ExchangeBlock`, registered in `ExchangeBlocks`), `ExchangeMenu`, `ExchangePayloads`/`ExchangeNetwork`/`ExchangeSync`, and `ExchangeScreen` with `ExchangeView` and `ClientExchangeState`. |
| `command` | `/exchange` (opens the screen), its player commands and `/exchange admin` (`ExchangeCommands`). |

The module registers its commands under its own root, `/exchange`, from its own `RegisterCommandsEvent` listener, with its admin commands under `/exchange admin`.

Its ids are in its own namespace, `variable_economy_exchange`: the block, item, menu, packets, recipe, loot table, money reasons, saved data and lang keys (`screen.variable_economy_exchange.…`). It has its own `assets/variable_economy_exchange/lang/en_us.json`, and a key core also shows (such as `balance`) is in it under the exchange's namespace.

A refused action returns `ExchangeResult`. Each value needs a line in the lang file, `screen.variable_economy_exchange.result.<value in lower case>`, or the screen shows the raw key.

Every package except `api.event` is marked `@ApiStatus.Internal` in its `package-info.java`: add-ons mustn't use it, and it may change in any release. A new package gets a `package-info.java` with the same annotation.

## Modules and their boundaries

Variable Economy's mods talk to each other only through their API packages, since each is a separate mod that may be installed without the others. Seen from the exchange:

- The main code uses only its own package, `variableeconomy.exchange`, and core's API, `variableeconomy.core.api` (with `.event`).
- The `api` package uses only itself and `variableeconomy.core.api`.
- The GameTests (`variableeconomy.exchange.gametest`) may use core and the exchange, internals included: tests reach into what they test.

Gradle's dependencies keep the other add-ons out of reach. The task `checkModuleBoundaries` (in `variable-economy.mod`) checks the rest: it scans every source file for references to `variableeconomy.…` (imports and fully qualified names, leaving out comments and strings) and fails the build on any that isn't allowed, such as a use of core outside `core.api`, or a file outside the exchange's package. `gradlew check`, and so `gradlew build`, runs it.

When the exchange needs something from core that isn't in `core.api`, it belongs in core's API, added in core's repository, rather than reaching into core's internals.

## Rules every change follows

These keep money safe. `ExchangeService` repeats the ones it relies on at its top.

- **Server thread only.** Every trade, payout and bank call runs on the server main thread; the bank isn't thread-safe. The packet handlers in `ExchangeNetwork` run on the main thread (the registrar's default), and every player action in `ExchangeService` (post, fill, list, buy from a listing, cancel, collect) calls `requireServerThread`, which throws `IllegalStateException` from another thread. Expiry and closing run from the server tick and the admin commands, on the same thread.
- **Check everything, then change.** A request makes every check before its first change, and after the first change no step may fail.
  - Posting (`ExchangeService.post`): check the exchange is on, the item is tradeable and not locked for the player, the quantity and price, the total with its tax (`checkedTotal`, `withTax`) and `max_order_size`, and the player's open orders. Then a buy order makes one `Money.charge` for its escrow, tax and fee, which is itself the funds check and changes nothing unless it succeeds; a sell order counts its items with `PlayerItems.count`, charges the fee, then removes exactly `qty`. Everything after (matching, paying sellers, boxing items) always succeeds: every payment is one fill of one order, which fits a `long` because that order's whole total and tax did.
  - Matching never trades with a seller whose account couldn't take the money, so no sale goes unpaid: a waiting seller is passed over, and an incoming one keeps the rest of its items on the book.
  - Filling (`fill`) and buying from a listing (`buyListing`) work the same way: every check, then the charge or the removal of items, then the trade.
  - A new check goes before the first change.
- **Money only through `Money`.** Every balance change goes through core's `Money` API (`CoreApi.money()`): a buy order's escrow and a purchase are a `charge`, payments to sellers and refunds a `pay`. Each call names a `Reason` in the exchange's namespace. Payments are never coins handed out. A payment the bank can't take yet is owed by core and paid later.
- **No profit from a round trip.** The exchange creates no money: every bit paid to a seller was charged to a buyer, and the tax and fee only remove some. Nothing in it pays out against market prices, and a player's own orders never trade with each other.
- **Totals in `long`.** Balances and every amount are `long`, worked out with exact arithmetic; a total or balance that would overflow is refused as `TOO_LARGE` before anything changes (`checkedTotal`, `withTax`, and a check that the seller's account can take the payment).
- **Main inventory only, and never drop items.** The exchange takes and gives items through `core.api.PlayerItems`: only the hotbar and the 27 storage slots count. Bought items go to the collection box, never straight into the inventory; taking them out checks `room` before `insert`, and only what fits moves.
- **Respect the item gate.** No player can post, buy or fill an item they haven't unlocked: `ExchangeService` asks `CoreApi.itemGate().isLocked`, and answers a locked item exactly as one that can't be traded (`NOT_LISTED`), so the reply doesn't reveal it. `ExchangeSync` resends a player's view when `ItemGateChangedEvent` says their locks changed.
- **Coins are never exchange items.** Selling one would create money from nothing. `ExchangeService.tradeable` refuses them through `CoreApi.isCoin` (which also covers Create: Numismatics' coins, by ID).
- **Events after the change.** An event is posted only once its whole change is complete, through `ExchangeEvents`. See [The add-on API](#the-add-on-api).

## How an order flows

1. The player presses Buy or Sell in the exchange screen. `ExchangeView` sends an `ExchangePayloads.ExchangeAction` (a listing is its own packet, `ListItem`, with the item the player saw).
2. `ExchangeNetwork`'s handler runs on the server thread and calls `ExchangeSync.handleExchangeAction`, which checks the player still has the exchange open and calls `ExchangeService.post` (or `cancel`, `collect`, `fill`, `buyListing`). The account is the player's active one (`CoreApi.accounts().active(player)`).
3. `ExchangeService.post` makes its checks, takes the money or items, and, with matching on, trades the new order against the other side of the book while their prices cross (`match`). `ExchangeService.trade` makes every fill, whether matching found it or a player picked the order (`fill`), so the tax, payments, box and notices work the same way. A buy order that bought below its bid gets back what it no longer needs to hold. What's left of the order goes on the book, and the fills' events are posted.
4. `ExchangeSync` replies with an `ExchangeOutcome` and sends the changed exchange, and the book they're looking at, to everyone with the screen open. An offline seller or buyer gets a notice at their next login.
5. At the end of the tick, core posts one `BalanceChangedEvent` per account that changed; `ExchangeModule` listens for it, so an open Exchange screen shows the new balance.

Expiry and turning the exchange off run from the server tick (`ExchangeService.tick`): expiry is checked once per in-game day, and closing an order returns exactly what it holds through the same code as cancelling.

## The add-on API

The exchange's API is `variableeconomy.exchange.api.event`, with one event, `ExchangeTradeEvent`. [api.md](api.md) is its guide for add-on authors. It has no services of its own: an add-on uses core's API for money, accounts and locks.

**Events.** `ExchangeEvents` puts the event on `NeoForge.EVENT_BUS`, logging and ignoring a listener that throws. It's posted where the change happens, not in the API, so actions from the screen and commands post it too:

| Event | Posted in |
| --- | --- |
| `ExchangeTradeEvent` | `ExchangeService.match` collects one per fill, and `post` posts them once the incoming order is on the book; `fill` and `buyListing` post after the units are in the buyer's box |

The rule: **an event is posted only after its whole change is complete**, as the last step. A listener may call back into the mod, so it must never see half a transaction. When one action makes several changes (an order filling against several others), collect the events and post them with `ExchangeEvents.postAll` after the last change. A new event follows the same pattern, is never cancellable, and is posted only for actions that succeeded.

**Changing the API.** The `api` package is `@ApiStatus.Experimental` until 1.0; it may change in a 0.x minor release, but never silently. For any change to a public type or method in it, or to when an event fires:

1. Update the javadoc. `gradlew javadoc` must pass (doclint is on).
2. Update [api.md](api.md): the section that describes it, and a line in its changelog under the version that ships it.
3. Raise `mod_version`: a minor version (0.6 to 0.7) for a change that can break add-ons, at least a patch version otherwise. Releases are tagged `v<version>`.

Keep internal types out of the API's signatures, and keep every check before the first change, as the service does.

## Design notes

- **Fresh items on the book, everything else in listings.** Only copies with default components trade on the order book, so a buy order always gets exactly what the market would hand out. Anything with components of its own is listed instead, and reaches the buyer exactly as it was. Core's `ignored_components` doesn't apply, for that reason.
- **The buyer pays the tax.** The seller always gets the whole price, and a buy order holds its tax from the start, so a fill never needs money the order doesn't hold. The tax is rounded once per buy order over everything it buys, so splitting an order gains nothing.
- **The collection box.** Bought items never go straight into an inventory, so a trade never depends on the buyer's free space or being online, and nothing is ever dropped.
- **One saved file.** Orders, listings, holdings and boxes are saved together, so a restart can't split a trade.
- **Turning it off settles everything.** Closing the exchange returns what every order holds, and the box stays open, so no money or items are stranded.
