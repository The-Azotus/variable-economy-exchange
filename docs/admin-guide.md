# Admin guide

This guide is for server admins running the exchange, Variable Economy's player exchange add-on. It covers:

- installing the exchange and turning it on
- how the exchange works: orders, listings, matching, filling, changing an order, history, Browse categories and the collection box
- where its money goes: the buyer's tax, the posting fee and the money orders hold
- how players reach it, every config option and every command
- turning it off and removing it

For what players see, read the [player guide](player-guide.md). The bank, coins, accounts, the audit log, ProgressiveStages and the other parts every add-on shares are in [core's admin guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/admin-guide.md). For mods that listen for exchange trades, read the [add-on guide](api.md).

## Installing

| Mod | Jar | Mod id | Adds | Needs |
| --- | --- | --- | --- | --- |
| Exchange | `variable_economy_exchange-<version>.jar` | `variable_economy_exchange` | The player exchange, the Exchange block, `/exchange` | Core |

The exchange needs [Variable Economy core](https://github.com/The-Azotus/variable-economy-core) 0.6.3 or a later 0.6.x, so update them together. Install both on the server and on every client. It works with or without the other add-ons, the market and contracts. It has its own config file in `config/` and its own commands.

The exchange is **on by default**: set `exchange.enabled = false` in `config/variable_economy_exchange-server.toml` to turn it off (see [Server config](#server-config)). Before this version it was off by default; a config file written then still says `false`, so set it to `true` there to turn the exchange on.

## The player exchange

The exchange add-on's **Exchange** block (or `/exchange`) lets players trade with each other at their own prices. A player posts a **sell order** (*sell 64 iron at 30 each*) or a **buy order** (*buy 64 iron at 28 each*); orders for the same item match automatically, and any player can pick another's order and fill it. The [player guide](player-guide.md#the-exchange) describes it from the player's side.

**What can be traded.** Any item, listed in the market's catalog or not, so players can trade what the market doesn't buy. Only fresh copies (default components, exactly as the market hands them out) go on the order book, and bought ones always come out fresh. Items with components of their own (enchanted, damaged, renamed, potions, enchanted books, levelled relics and so on) are traded through **listings** instead (below). Core's `ignored_components` setting does not apply here: data it ignores for market sales (such as `relics:data`) still makes an item a listing, so the buyer gets it exactly as it was. Coins never, however they're changed. Items locked for a player ([ProgressiveStages](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/admin-guide.md#progressivestages-hiding-locked-items), or an add-on's lock) are hidden from them and refused, as on the market.

**Listings.** A player lists one stack's worth of one exact item at a fixed price each; other players buy all or part of it at that price, from the Listings view or `/exchange buylisting`. There's no matching and no buy side. The items are taken from the seller's main inventory when listed; a buyer's units go to their collection box exactly as listed, components and all; the buyer pays the tax on top and the seller gets the whole price, as with orders. Listings and orders share `posts.max_per_player`, and are cancelled, expired and closed the same way, their items going back to the owner's box. Players can sort the listings they browse by item, price, age or time left. Shulker boxes, bundles and other containers using vanilla's container data can be listed only when empty, so nothing is passed along unseen (modded backpacks that keep their contents elsewhere aren't caught by this). An item that takes more than 8 KB to send (a long written book, say) is refused.

**Matching.** With `matching.enabled` on (the default), orders fill best price first, then oldest first, and partly if need be. A trade happens at the price of the order that was already waiting: a buy order bidding 35 against a waiting sell order at 30 pays 30, and the 5 a unit it saved (with its share of the tax) goes straight back to the buyer. A player's own orders never trade with each other. With matching off, posting never trades by itself: every order waits on the book.

**Filling.** The book lists each order with who posted it, its units and its time left. A player picks another player's order and fills all or part of it at its price: buying from a sell order (paying the value and the tax; the units go to their collection box), or selling into a buy order from their main inventory (paid from what the order holds). In the screen, picking an order in the book turns Buy or Sell into *Buy 10 from Steve* or *Sell 10 to Alex*; by command, `/exchange fill <order id> <qty>`, with ids from `/exchange book <item>`. Filling works with matching on or off.

**Changing an order.** A player can change the price of their open order or listing (the screen's My Orders tab, or `/exchange reprice <id> <price>`), free of any posting fee. An order keeps its id but moves to the back of the queue at its new price, behind orders already there, and matches again at once, so a raised bid can trade straight away. A buy order then holds exactly what its remaining units cost at the new price and the tax on them: more is taken from its account (which must be the player's active account, so money never moves from one account into another's order) or the rest goes back. A buyer or filler who saw the old price is refused, and nothing is bought. A player can also lower an order's or listing's quantity (`/exchange reduce <id> <qty>`), keeping its place in the queue; what the units taken off held goes back as on cancelling.

**Browse.** The screen's Browse tab shows every sell order and listing a player may see, newest first, with search, a category, a price range, a smallest quantity and four sorts. Buying a plain item there fills that sell order at its price; buying a listed item buys from the listing; both use the same trades as the rest of the exchange. Browse sends at most 512 KB of offers at once, newest first, and says when some were left out.

**Categories.** `browse.categories` lists Browse's categories in order. Each is an item tag: a plain name such as `building_blocks` is the tag `variable_economy_exchange:category/building_blocks`, and a full id such as `c:ores` is that tag. The mod ships eight tags (Building Blocks, Ores and Minerals, Farming and Food, Weapons and Armor, Tools and Equipment, Mob Drops, Redstone and Technology, Potions and Brewing), built on vanilla's tags and the shared `c:` tags most mods fill, so modded ores, ingots, foods, tools and armour are sorted without any setup. Change what's in one with a datapack (`data/variable_economy_exchange/tags/item/category/<name>.json`), or add your own categories as tags. An item in none of the category tags takes the category its creative tab stands for, as `browse.creative_tab_fallback` says (vanilla's tabs by default; add a mod's own tabs there). `miscellaneous` holds every item in no other category. An item may be in several. A category's name on screen is the translation `screen.variable_economy_exchange.category.<name>` for a plain name, or a full id's `tag.item.<namespace>.<path>` (as mods name their tags), or else its last word.

**History.** Each player's history keeps their trades (bought and sold, with the other player, the price and the tax) and their orders' ends and changes (completed, cancelled, expired, closed, repriced, lowered), shown on the screen's My Orders and Collection tabs and by `/exchange history`. It keeps the newest `history.max_per_player` lines (100) for each player, saved with the exchange. It's a record only: nothing is paid or returned from it.

**What an order holds.** Posting a sell order takes its items out of the main inventory; posting a buy order takes `price × quantity` and the tax on it from the player's active account. That's held until the order fills, is cancelled or expires, after `posts.duration_days` (7; 0 never). A seller is paid the whole price into the account their order came from; bought items go to the buyer's **collection box**. With `posts.listing_fee` set, each order or listing also costs that many bits when posted, never refunded (a buy order pays it with its escrow in one charge; a sell order or listing pays it before its items are taken). Cancelling, expiry, `/exchange admin cancel` or turning the exchange off returns exactly what an order still holds: a buy order's money to the account it came from (or the owner's personal account, if that one has ended), a sell order's items to the owner's box. All of this works for offline players, who are told at their next login. If the bank can't be reached at that moment, the payment is kept and paid once it can (it's tried again every 10 seconds).

**The collection box** (the screen's Collection tab) keeps any number of items with the world for as long as the player likes. Taking items out only fills free main-inventory space; whatever doesn't fit stays. It stays open while the exchange is off, so nothing is ever stranded.

**Restarts.** Orders, listings, holdings, boxes and history are saved together in one file with the world (`data/variable_economy_exchange.dat`), so a restart can't split a trade. A listing or boxed item whose mod has been removed is kept in the file exactly as saved, and comes back if the mod does. The bank is saved in its own file, so only a crash between the two saves could leave them out of step, as with every other payment.

## Where the money goes

Every payment, both ways, goes through the player's bank account; the exchange never pays out or takes coins. Coins only move in and out of the bank in core's bank screen.

**The exchange creates no money**: every bit a seller gets was paid by a buyer. The tax, `tax.buyer_percent` (5% by default), is paid by the buyer on top of the price and destroyed, a money sink, as is the posting fee. A buy order's tax is worked out on everything it buys and rounded up to a whole bit once, so a purchase filled in many small pieces pays the same tax as one filled all at once; a purchase from a listing, or a fill of a sell order, is rounded on its own.

**Escrow.** The money a buy order holds is taken from the account when the order is posted, and paid out again as the order fills (to sellers) or closes (back to the buyer). In core's `/bank admin stats`, taking it counts as money destroyed (the exchange's escrow) and paying it out as money created, so over time the exchange's net is the tax and fees it destroyed. Each of these movements is also a line in the account's history.

**The leaderboard.** Money held by buy orders (tax included) counts on the leaderboard for the account it came from, and so for that account's owner. An account can't be deleted while an open buy order holds money for it.

`/exchange admin` shows every open order, what the orders hold, what waits in boxes, the value traded and the tax destroyed. `/exchange admin cancel` is written to core's audit log, shown by `/bank admin audit`.

## Letting players in

The **Exchange** block opens the exchange's screen, in four tabs: Browse, Exchange (order books and posting), My Orders and Collection. `/exchange` opens the same screen from anywhere. A screen opened from the block closes if the player walks more than about 8 blocks away or the block is broken.

Choose whether players may use the commands with `access_mode` in `config/variable_economy_exchange-server.toml`. Each Variable Economy mod has its own, in its own config file, so you can, say, keep `/bank` and make the exchange block-only.

| `access_mode` | Players | Operators and console |
| --- | --- | --- |
| `COMMAND_AND_BLOCK` (default) | The Exchange block and every player command | Everything |
| `BLOCK_ONLY` | The Exchange block only. The bare `/exchange` tells them to find the block; the other player commands (`/exchange orders`, `book`, `buy` and the rest) are hidden. | Everything |

"Operators" here means permission level 2. The setting takes effect as soon as you save the file, with no restart. A player who opened the screen by command is closed out if you switch to `BLOCK_ONLY` while they have it open.

### The block

The Exchange is 8 Bits (core's smallest coin) around a barrel:

```
Bit  Bit     Bit
Bit  Barrel  Bit
Bit  Bit     Bit
```

| Block | ID | Item in the middle | Recipe |
| --- | --- | --- | --- |
| Exchange | `variable_economy_exchange:exchange` | Barrel | `variable_economy_exchange:exchange_from_bits` |

To change or remove the recipe, override it in a datapack: the recipe `variable_economy_exchange:exchange_from_bits` is the file `data/variable_economy_exchange/recipe/exchange_from_bits.json`. To hand the block out yourself, use `/give`, such as `/give @p variable_economy_exchange:exchange`. It faces whoever placed it. It breaks by hand and always drops itself, faster with an axe.

## Server config

The exchange's server config file, `config/variable_economy_exchange-server.toml`, is created on first start. Changes apply when you save the file. A key such as `posts.duration_days` is `duration_days` in the file's `[posts]` section.

| Option | Default | Range | What it does |
| --- | --- | --- | --- |
| `access_mode` | `COMMAND_AND_BLOCK` | `COMMAND_AND_BLOCK`, `BLOCK_ONLY` | How players reach the exchange: by command and block, or by the Exchange block only; see [Letting players in](#letting-players-in). |
| `exchange.enabled` | true | true, false | Run the [player exchange](#the-player-exchange). Turning it off closes every open order and listing, returning what it holds. The Exchange screen still opens, so players can empty their collection boxes, but nothing can be posted, bought or filled. |
| `exchange.max_order_size` | 6400 | 1 – 1,000,000 | Largest quantity one order, or one fill, may ask for. |
| `tax.buyer_percent` | 5 | 0 – 100 | Percent the buyer pays on top of each purchase, destroyed. Rounded up to a whole bit once per buy order over all it buys, or once per purchase from a listing or fill of a sell order. A buy order holds its tax when posted; the seller gets the whole price. |
| `posts.max_per_player` | 10 | 1 – 1000 | How many open orders and listings, together, one player may have at once. |
| `posts.duration_days` | 7 | 0 – 3650 | In-game days until an unfilled order or listing expires and returns what it holds. Sleeping counts; setting the time backwards doesn't. 0: posts never expire. |
| `posts.listing_fee` | 0 | 0 – 1,000,000,000,000 | Bits charged for each order or listing posted, never refunded and destroyed. 0: no fee. |
| `matching.enabled` | true | true, false | Crossing buy and sell orders trade as soon as one is posted or its price changes. Off: every order waits until another player fills it. |
| `history.max_per_player` | 100 | 0 – 1000 | Lines of [history](#the-player-exchange) kept for each player, newest first. 0: none. |
| `browse.categories` | the eight shipped, then `miscellaneous` | item tags | Browse's [categories](#the-player-exchange), in order: plain names are the mod's own tags, full ids are those tags, `miscellaneous` is every item in no other. |
| `browse.creative_tab_fallback` | vanilla's tabs | `category=creative_tab_id` | The category an item in no category tag takes from a creative tab it's on. |

## Commands

The exchange's commands sit under `/exchange`.

| Command | Permission | What it does |
| --- | --- | --- |
| `/exchange` | everyone* | Opens the Exchange's screen. |
| `/exchange orders`, `/exchange book <item>`, `buy <item> <qty> <price>`, `sell <item> <qty> <price>`, `cancel <id>`, `collect [item]` | everyone* | Your [exchange](#the-player-exchange) orders, listings and box in chat, an item's book (each order with its id), and the Exchange screen's buttons. `price` is per unit. |
| `/exchange fill <id> <qty>` | everyone* | Fill `qty` units of another player's order at its price: buy from a sell order, or sell into a buy order. |
| `/exchange reprice <id> <price>`, `reduce <id> <qty>` | everyone* | Change the price of your order or listing; lower it to `qty` still wanted or offered. |
| `/exchange history` | everyone* | Your history, newest first. |
| `/exchange list <qty> <price>`, `listings`, `buylisting <id> <qty>` | everyone* | List the item in the selected hotbar slot; show every listing; buy from one at its listed price. |
| `/exchange admin` | 2 | Every open order and listing with its owner, what they hold, what waits in boxes, the value traded and the tax destroyed. |
| `/exchange admin cancel <id>` | 2 | Closes any order or listing, returning what it holds to its owner. Logged in core's audit log. |

\* Operators only when the exchange's `access_mode` is `BLOCK_ONLY`: players are told to use the Exchange block instead (see [Letting players in](#letting-players-in)).

## Turning it off and removing it

**Turning it off.** `enabled = false` under `[exchange]` closes every open order and listing at once: each buy order's money goes back to the account it came from, and each sell order's or listing's items to its owner's collection box. The Exchange screen still opens, so players can empty their boxes, but nothing can be posted, bought or filled. Turn it back on at any time.

**Removing the add-on** from a world keeps what it holds in its own saved file: buy orders' money, sell orders' and listings' items, and collection boxes. While it's gone that money doesn't count on the leaderboard or toward an account's holdings, so the account it belongs to can be deleted. Put the add-on back and everything returns; anything paid out to an account that has since been deleted goes to the personal account of the player it belonged to. To end the exchange for good, turn it off first so it settles everything, let players empty their collection boxes, then remove it.

## Troubleshooting

| You see | Meaning |
| --- | --- |
| The Exchange's buttons say the exchange is turned off | `exchange.enabled` is false. Set it to true. A config file written before this version, when off was the default, still says false. |
| A player sees "The exchange is only available at an Exchange on this server" | `access_mode` is `BLOCK_ONLY`. |
| A player can't put an item on the book, though they hold it | It has components of its own (enchanted, damaged, renamed, or data another mod adds, such as a relic's level): it goes in a listing instead. |
| A player can't list a shulker box or bundle | It isn't empty. |
| An item doesn't show on the exchange for one player | It's locked for them (ProgressiveStages, or another mod's lock). |
| A modded item shows under Miscellaneous | It's in none of the category tags and none of `browse.creative_tab_fallback`'s tabs. Add it (or a tag it's in) to a category's tag by datapack, or its mod's creative tab to the fallback list. |
| "Couldn't load tag variable_economy_exchange:category/..." in the log | A datapack's category tag names an item or tag that doesn't exist. Mark a tag that may be missing `"required": false`. |
