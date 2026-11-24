# Variable Economy: Exchange

A player exchange for Minecraft servers, for NeoForge 1.21.1. Players post buy and sell orders at their own prices, fill each other's orders, and list one-of-a-kind items for sale; the server only takes a small tax. All money moves through each player's bank account, counted in **bits**.

This repository is the **exchange**, an optional add-on for [Variable Economy](https://github.com/The-Azotus/variable-economy-core). It adds:

- **Buy and sell orders.** *Sell 64 iron at 30 each*, *buy 64 iron at 28 each*. Orders for the same item match automatically, best price first, at the waiting order's price. Any player can also pick another player's order and fill all or part of it.
- **Browse.** Everything for sale in one feed, newest first: search it, narrow it by category, price and quantity, sort it, inspect an item in full, and buy.
- **The order book.** Each item's book shows every order, who posted it, its units and its time left.
- **My Orders.** Each open order's progress and trades; change its price or lower its quantity; see what completed, and a history of every trade.
- **Listings.** Enchanted, damaged, renamed and other one-of-a-kind items are listed at a fixed price and bought exactly as they are.
- **Escrow.** A sell order or listing holds its items, and a buy order holds its money and tax, until it fills, is cancelled or expires. Nothing can be posted that can't be paid for.
- **Collection.** Bought and returned items wait for each player until they collect them, even while they're offline. Nothing is ever dropped on the ground.
- **A buyer's tax.** Buyers pay a tax on top (5% by default), which is destroyed; sellers get the whole price. A posting fee can be set too.
- **Locked items.** With ProgressiveStages, items a player hasn't unlocked are hidden from them and can't be traded.
- **The Exchange block.** A craftable block opens the exchange. Players can also use `/exchange` if the server allows it.

## The four mods

Variable Economy is four mods. **Core** is required. The **market**, **contracts** and **exchange** are optional add-ons, and any combination of them works on top of core.

| Mod | Mod id | Adds | Repository |
| --- | --- | --- | --- |
| Core | `variable_economy` | The coins and bank, the Bank block and Bank Card, accounts, `/pay`, account history, the leaderboard, money-supply stats, `/bank` | [variable-economy-core](https://github.com/The-Azotus/variable-economy-core) |
| Market | `variable_economy_market` | A server-owned market whose prices move with supply and demand: the Market Terminal, prices and price tooltips, `/market` | [variable-economy-market](https://github.com/The-Azotus/variable-economy-market) |
| Contracts | `variable_economy_contracts` | Delivery contracts with deposits: the Contract Board, `/contracts`. Without the market, only fixed-payout contracts. | [variable-economy-contracts](https://github.com/The-Azotus/variable-economy-contracts) |
| Exchange | `variable_economy_exchange` | Player buy and sell orders: the Exchange block, `/exchange` | This one |

Each mod has its own block, screen, config file and commands. If a mod isn't installed, its features are simply absent.

## Requirements and installation

- [Variable Economy core](https://github.com/The-Azotus/variable-economy-core) **0.6.3** or a later 0.6.x (up to, but not including, 0.7).
- Minecraft 1.21.1 with NeoForge 21.1.228 or newer, and Java 21.
- Optional, through core: [ProgressiveStages](https://modrinth.com/mod/progressivestages) 3.x, for locked items. FTB Teams parties and named accounts can trade through a shared account.

Install core and the exchange on the **server and every client**, all of the same version. Clients need them for the screen. Put `variable_economy_exchange-<version>.jar` in the mods folder; the `-sources` and `-javadoc` jars are for developers.

The exchange is **on by default**. A server's config file written by an older version, when it was off by default, still says `enabled = false` under `[exchange]`; set it to `true` there.

What changed in each version is in the [changelog](CHANGELOG.md). Worlds from Dynamic-Economy, the mod's name before 0.6.0, can't be carried over.

## Using it

### The Exchange block

Craft an **Exchange** from 8 Bits around a barrel, and right-click it. Or type `/exchange`, if the server allows it. A screen opened from the block closes if you walk more than about 8 blocks away.

```
Bit  Bit     Bit
Bit  Barrel  Bit
Bit  Bit     Bit
```

### The screen

The screen has four tabs. The search box (`@mod` and `#tag` work too) and your balance show on every one; click a line to see it in full, and **< Back** to return. Buying, posting and changing an order ask you to confirm first.

| Tab | What it shows |
| --- | --- |
| **Browse** | Every sell order and listing, newest first, with how many, the price each, the seller and its age. Pick a category (Building Blocks, Ores and Minerals, Farming and Food and so on), a price range or a smallest quantity, and sort by newest, price or quantity. Click one to inspect the whole item and buy. |
| **Exchange** | **Buy**: items with orders (search finds any item). **Sell**: what you hold. Click one to see its book beside the order form: how many, the most or least each, what it comes to and how many units would trade at once. An item with data of its own is listed at a fixed price instead. |
| **My Orders** | **Active**: your open orders and listings with their progress. Click one to see its trades, change its price, lower its quantity or cancel it. **Completed** and **History**: what filled, and everything that happened. |
| **Collection** | **Items received**: click a line to collect it, or **Collect all**; only what fits moves. **Currency received**: what your sales paid into your bank. Marked while anything waits. |

### Trading

- **Post a buy order:** set a price each and a quantity, and press **Buy N, holding X**. X is what posting takes from your account: the price, the tax and any posting fee. Sellers asking your price or less sell to you at once, at their price, and what you saved comes straight back. The rest waits on the book.
- **Post a sell order:** press **Sell N for X**. Your items are held, and buyers bidding your price or more buy at once, at their price. You get the whole price.
- **Fill someone's order:** click it in the book. **Buy** becomes *Buy N from Steve* (or **Sell** becomes *Sell N to Alex*). The trade happens at once, at that order's price.
- **Buy from Browse:** set how many and press **Buy N for X**: X is the price with the tax included. A plain item fills that sell order; a listed one is bought exactly as it is.
- **Change an order:** a new price moves it to the back of the queue at that price, and it trades at once if it now meets another order. Lowering its quantity keeps its place. Either way, what it no longer needs comes back.

Bought items go to your Collection tab. Sales are paid into your bank, online or not. Orders and listings expire after a few in-game days (7 by default), and their money or items come back. Only plain items go on the book (not enchanted, damaged or renamed); those are listed instead. Coins are never traded.

## Commands

Player commands follow the exchange's `access_mode`: with `BLOCK_ONLY`, only operators can use them, and `/exchange` tells players to find an Exchange.

| Command | What it does |
| --- | --- |
| `/exchange` | Opens the Exchange screen. |
| `/exchange orders` | Your orders, listings and box in chat. |
| `/exchange book <item>` | An item's book, with each order's number. |
| `/exchange buy <item> <qty> <price>`, `sell <item> <qty> <price>` | Posts a buy or sell order. Prices are per unit. |
| `/exchange fill <id> <qty>` | Fills another player's order. |
| `/exchange list <qty> <price>` | Lists the item in your selected hotbar slot. |
| `/exchange listings` | Every listing, with its number. |
| `/exchange buylisting <id> <qty>` | Buys from a listing at its price. |
| `/exchange cancel <id>` | Cancels one of your orders or listings. |
| `/exchange reprice <id> <price>` | Changes the price of one of your orders or listings. |
| `/exchange reduce <id> <qty>` | Lowers one of your orders or listings to `qty`. |
| `/exchange history` | Your history, newest first. |
| `/exchange collect [item]` | Takes items out of your box. |

Admin commands need permission level 2:

| Command | What it does |
| --- | --- |
| `/exchange admin` | Every open order and listing, and the money the exchange has moved and taxed. |
| `/exchange admin cancel <id>` | Cancels any order or listing, returning what it holds. Logged in core's audit log. |

## Configuration

The settings are in `config/variable_economy_exchange-server.toml`, created on first start. Changes apply when the file is saved.

| Setting | Default | What it does |
| --- | --- | --- |
| `access_mode` | `COMMAND_AND_BLOCK` | `BLOCK_ONLY` keeps players to the Exchange block. |
| `[exchange]` `enabled` | `true` | Run the exchange. Turning it off closes every order and listing, returning what they hold; boxes stay open. |
| `[exchange]` `max_order_size` | `6400` | The most one order may ask for. |
| `[tax]` `buyer_percent` | `5` | The tax buyers pay on top of the price, destroyed. |
| `[posts]` `max_per_player` | `10` | Open orders and listings one player may have. |
| `[posts]` `duration_days` | `7` | In-game days before an order or listing expires; `0` never. |
| `[posts]` `listing_fee` | `0` | Bits charged per order or listing posted, never refunded. |
| `[matching]` `enabled` | `true` | Crossing orders trade at once. Off: every order waits until someone fills it. |
| `[history]` `max_per_player` | `100` | Lines of history kept for each player. |
| `[browse]` `categories` | the shipped categories | Browse's categories, each an item tag. |
| `[browse]` `creative_tab_fallback` | vanilla's tabs | The category an item in no category tag takes from its creative tab. |

Every setting, with its range, is in the [admin guide](docs/admin-guide.md#server-config).

## For pack makers

- **Commands from functions.** Functions and command blocks run at level 2. The player commands need a player as the source:

  ```mcfunction
  execute as @p run exchange sell minecraft:iron_ingot 64 30
  exchange admin cancel 12
  ```

- **Categories.** Browse's categories are item tags, `data/variable_economy_exchange/tags/item/category/<name>.json`, built on the shared `c:` tags. Change them by datapack, or add your own and name them in `[browse]` `categories`; see the [admin guide](docs/admin-guide.md#the-player-exchange).
- Balances, payments and charges are core's commands and KubeJS bindings: see [core's README](https://github.com/The-Azotus/variable-economy-core#for-pack-makers).

## For mod authors

The exchange's Java API is one event, `ExchangeTradeEvent`, in `variableeconomy.exchange.api.event`. Money, accounts and item locks are [core's API](https://github.com/The-Azotus/variable-economy-core#for-mod-authors). It is marked experimental until 1.0, and every change is listed in the [add-on guide's changelog](docs/api.md#changelog).

### Depending on the exchange

The released jars are on CurseForge (the exchange is project 1818114, core 1818087) and can be used through [CurseMaven](https://www.cursemaven.com/):

```groovy
repositories {
    exclusiveContent {
        forRepository { maven { url = 'https://cursemaven.com' } }
        filter { includeGroup 'curse.maven' }
    }
}

dependencies {
    compileOnly 'curse.maven:variable-economy-1818087:<core file id>'
    compileOnly 'curse.maven:variable-economy-exchange-1818114:<exchange file id>'
}
```

Then declare it in your `neoforge.mods.toml`, usually as optional:

```toml
[[dependencies.yourmod]]
    modId="variable_economy_exchange"
    type="optional"
    versionRange="[0.6,)"
    ordering="AFTER"
    side="BOTH"
```

As an optional dependency, check `ModList.get().isLoaded("variable_economy_exchange")` before touching the exchange, and register your listener from a class loaded only after that check. For sources and javadoc, clone this repository and run `gradlew publishToMavenLocal`; it publishes `variableeconomy:exchange:<version>`. The [add-on guide](docs/api.md#depending-on-the-exchange) gives the file IDs and the full setup.

### The event

`ExchangeTradeEvent` is posted on `NeoForge.EVENT_BUS`, on the server thread, after the trade is complete, and is never cancellable. It fires once per fill of an order and once per purchase from a listing.

| Getter | Gives |
| --- | --- |
| `getServer()` | The server. |
| `getBuyer()`, `getSeller()` | The two players' UUIDs; either may be offline. |
| `getItem()` | One unit, as a new `ItemStack` each call; a listing's keeps its components. |
| `getQuantity()`, `getUnitPrice()` | How many traded, and at what price each. |
| `getValue()`, `getTax()`, `getBuyerPaid()` | What the seller got, the tax the buyer paid on top, and the two together. |
| `isFromListing()` | Whether it was a purchase from a listing. |

```java
/** Registered only when the exchange is loaded. */
static void listen() {
    NeoForge.EVENT_BUS.addListener((ExchangeTradeEvent event) -> {
        // count event.getQuantity() traded by event.getSeller() towards a quest
    });
}
```

The [add-on guide](docs/api.md) covers the event in full.

## Guides

| You are | Read |
| --- | --- |
| A player | [Player guide](docs/player-guide.md) |
| A server admin | [Admin guide](docs/admin-guide.md) |
| A mod or modpack author | [Add-on guide](docs/api.md) |
| Reading the code | [Architecture](docs/architecture.md) |

## Building from source

Run `gradlew build` with Java 21. Gradle's toolchain finds an installed JDK or downloads one, and the Gradle wrapper is in the repository. The build compiles the mod, checks the module boundary (see [docs/architecture.md](docs/architecture.md)), and puts the jar, a sources jar and a javadoc jar in `build/libs/`.

The exchange builds against core, and `gradle.properties` says which core:

- `core_source=curseforge` (the default) uses the released core that `core_curseforge_project` and `core_curseforge_file` name, through CurseMaven.
- `core_source=local` uses `variableeconomy:core:<core_version>` from your local Maven instead: clone [variable-economy-core](https://github.com/The-Azotus/variable-economy-core) and run `gradlew publishToMavenLocal` there first. `-Pcore_source=local` sets it for one build.

Then:

- `gradlew runGameTestServer` runs the exchange's automated tests with core loaded, then checks the log. `-PwithoutMods=all`, or a comma-separated list of `ftbteams`, `progressivestages` and `kubejs`, leaves the optional mods out.
- `gradlew runClient` and `gradlew runServer` start a dev client or server with core and the exchange.
- `gradlew publishToMavenLocal` publishes `variableeconomy:exchange:<version>` to your local Maven.

Released versions are tagged in git, for example `v0.6.1`.

## Licence

MIT; see [LICENSE](LICENSE).
