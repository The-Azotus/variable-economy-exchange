# Changelog

What changed in each release of the exchange, for players and server admins. Mod and add-on authors: the API's changes are in the [add-on guide's changelog](docs/api.md#changelog). Changes to core, and to Variable Economy as a whole, are in [core's changelog](https://github.com/The-Azotus/variable-economy-core/blob/main/CHANGELOG.md).

## Unreleased

**Install with core 0.6.3 or a later 0.6.x.** The network protocol is unchanged.

- **The exchange is on by default.** On a server whose config file was written by an older version, `enabled` under `[exchange]` still says `false`: set it to `true` to open the exchange.
- **Fixed: raising the tax while buy orders wait.** The extra tax was taken from the money a buy order held for its later units, so its held money could go below zero and the exchange's stats counted more tax than was paid. A waiting order now pays only the tax it can spare, and always keeps what its remaining units cost.
- **Fixed: collecting after death.** A request to take items out of the box that reached the server after the player died, with the screen still open, could lose the items. A dead player's requests are now ignored, and `/exchange collect` refuses too.
- **Every Buy button shows what you pay**: posting a buy order shows the price, the tax and any posting fee; buying from a listing shows the price with the tax, and the panel's total says "tax included".
- **Sell and List are greyed out** when you can't pay the posting fee, as Buy already was.
- **All** after picking another player's buy order fills in at most what that order still wants.
- **The Sort button** in Listings stays inside the panel, and no longer covers the total on a narrow window.
- **The list's scrollbar can be dragged**: click it to jump there, or hold and drag.
- **Commands:** failures answer in the same sentences as the screen; `/exchange orders` counts orders and listings together against the limit; "1 day", not "1 days".
- **Posters' and sellers' names show** for players the server's name cache has forgotten (it keeps only the 1,000 seen most recently), using the names core remembers, instead of "?".
- A poster's name longer than 16 characters (possible behind some proxies) no longer stops an item's book being sent.

## 0.6.1 (2026-11-17)

- The exchange now lives in its own repository; nothing changes in game.
- The mod's jar no longer contains its test code.

## 0.6.0 (2026-11-14)

The mod was called Dynamic-Economy until this release. It is now **Variable Economy**, and it is four mods instead of one; the exchange is one of the optional add-ons. [Core's changelog](https://github.com/The-Azotus/variable-economy-core/blob/main/CHANGELOG.md) describes the whole release, including the rename, the bank and accounts.

**Before you update:** worlds from Dynamic-Economy can't be carried over. Start a new world.

### Its own mod

- The exchange is an optional add-on, `variable_economy_exchange`, on top of core (`variable_economy`). Install the same mods on the server and on every client, all of the same version.
- It has its own block and screen, the **Exchange**, in place of a tab of the one Market Terminal.
- It has its own config file (`config/variable_economy_exchange-server.toml`), its own `access_mode` and its own commands, `/exchange`. Its admin commands moved with them, to `/exchange admin`.

### Exchange

- **The buyer pays the tax**, on top of the price; the seller gets the whole price. A buy order holds its tax along with the price.
- **Posts expire** after 7 days by default, returning items to the collection box and money to the account.
- An optional **posting fee**, off by default.
- **Automatic matching** can be turned off. Either way, players can **fill a posted order** they pick: buy from a sell order, or sell into a buy order, all or part of it, in the screen or with `/exchange fill`.
- An item's book lists **each order** with who posted it, its price, the units left and time left.
- **Listings** sort by item, price, age or time left, and show their time left.
