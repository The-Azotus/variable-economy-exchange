# Variable Economy: Exchange

A player exchange for Minecraft, an add-on for [Variable Economy](https://github.com/The-Azotus/variable-economy-core). Players trade with each other at their own prices: buy and sell orders that match automatically, plus fixed-price listings for enchanted, damaged or renamed items. Bought items wait in a collection box, and a small tax on each sale takes money out of the economy.

- **Buy and sell orders.** A player posts a sell order (*sell 64 iron at 30 each*) or a buy order (*buy 64 iron at 28 each*). Orders for the same item match automatically, best price first, and any player can pick another's order and fill it.
- **Listings.** Enchanted, damaged, renamed and other one-of-a-kind items are sold one exact stack at a fixed price each, so the buyer gets exactly what was listed.
- **Any item.** Players can trade what the server's market doesn't buy, or trade on a server with no market at all. Coins never.
- **Bank payments.** All money moves through each player's bank account in Variable Economy core; nothing pays out or takes coins.
- **A collection box.** Bought items, and the items of a cancelled or expired sell order, wait in the box for as long as the player likes, so nothing is ever dropped.
- **A money sink.** The buyer pays a small tax (5% by default) on top of the price, and it's destroyed. An optional posting fee works the same way.
- **The Exchange block.** A craftable block opens the exchange's screen. Players can also open it with `/exchange` if the server allows it.

The exchange is **off by default**: a server turns it on in its config (see [Quick start](#quick-start)).

| You are | Read |
| --- | --- |
| A player | [Player guide](docs/player-guide.md) |
| A server admin | [Admin guide](docs/admin-guide.md) |
| A mod author listening for exchange trades | [Add-on guide](docs/api.md) |
| Reading or changing this mod's code | [Architecture](docs/architecture.md) |

## Variable Economy

Variable Economy is four mods. **Core** is required; the **market**, **contracts** and **exchange** are add-ons, and any combination of them works on top of core:

| Mod | Repository | Adds |
| --- | --- | --- |
| Core (`variable_economy`) | [variable-economy-core](https://github.com/The-Azotus/variable-economy-core) | The coins and bank, the Bank block and Bank Card, accounts, `/pay`, account history, the leaderboard, money-supply stats, `/bank` |
| Market (`variable_economy_market`) | [variable-economy-market](https://github.com/The-Azotus/variable-economy-market) | The market, the Market Terminal, prices and price tooltips, `/market` |
| Contracts (`variable_economy_contracts`) | [variable-economy-contracts](https://github.com/The-Azotus/variable-economy-contracts) | The Contract Board, `/contracts` |
| Exchange (`variable_economy_exchange`) | this one | The player exchange, the Exchange block, `/exchange` |

The exchange works alongside any of the other add-ons, and doesn't need the market. Core's repository has the overview of the whole family and its own guides.

## Requirements

- [Variable Economy core](https://github.com/The-Azotus/variable-economy-core) 0.6.1 or a later 0.6.x (up to, but not including, 0.7)
- Minecraft 1.21.1 with NeoForge 21.1.228 or newer
- Java 21

Install the exchange and core on the **server and every client**, of the same minor version. Clients need them for the block and the screen.

With ProgressiveStages installed (an optional mod core supports), items a player hasn't unlocked are locked for them: hidden on the exchange, and not tradeable.

What changed in each version is in the [changelog](CHANGELOG.md).

## Quick start

**Players:** right-click an **Exchange** block, or type `/exchange` if your server allows it. Click an item to see its order book, set a price and a quantity, then press **Buy** or **Sell**. Everything is paid through your bank account; core's **Bank** block (or a Bank Card) turns coins into money and back. See the [player guide](docs/player-guide.md).

**Admins:**

1. Install core and the exchange, then start the server once, so the config file is written.
2. Turn the exchange on: set `enabled = true` under `[exchange]` in `config/variable_economy_exchange-server.toml`. Changes apply when you save the file.
3. Adjust the tax (`tax.buyer_percent`), how long posts last (`posts.duration_days`) and the other settings in the same file.
4. To make the Exchange block the only way in, set `access_mode = "BLOCK_ONLY"` in the same file.

The [admin guide](docs/admin-guide.md) covers every command and setting.

## For add-on authors

The exchange posts an event, `ExchangeTradeEvent`, after every trade between two players. Everything else (money, accounts, item locks, the leaderboard) is in core's API. The [add-on guide](docs/api.md) covers depending on the exchange and its event, and links to [core's add-on guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/api.md) for the rest.

## Building from source

The build needs Java 21; Gradle's toolchain finds it, or downloads it. The Gradle wrapper is in the repository.

The exchange builds against core. `gradle.properties` says which core and where from:

- `core_source=curseforge` (the default) uses the released core that `core_curseforge_project` and `core_curseforge_file` name, through [CurseMaven](https://www.cursemaven.com/): core 0.6.1 is `curse.maven:variable-economy-1818087:9710072`.
- `core_source=local` uses `variableeconomy:core:<core_version>` from your local Maven instead, for building against a core that isn't released: clone [variable-economy-core](https://github.com/The-Azotus/variable-economy-core), check out what you want, and run `gradlew publishToMavenLocal` there first. `-Pcore_source=local` sets it for one build.

Then:

| Command | Does |
| --- | --- |
| `gradlew build` | Compiles, checks the module boundary, and builds the jar, the sources jar and the javadoc jar (in `build/libs/`). |
| `gradlew runGameTestServer` | Runs the exchange's automated tests with core loaded, then checks the log. `-PwithoutMods=all` runs them without the optional mods. |
| `gradlew runClient`, `gradlew runServer` | A dev client or server with core and the exchange. |
| `gradlew publishToMavenLocal` | Publishes `variableeconomy:exchange:<version>`, for add-ons that build against it. |

Released versions are tagged in git as `v<version>`. To find your way around the code, start with [docs/architecture.md](docs/architecture.md).

## Licence

MIT; see [LICENSE](LICENSE).
