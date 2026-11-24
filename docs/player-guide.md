# Player guide

The **Exchange** is an add-on for Variable Economy that lets you trade with other players at prices you choose. Everything is paid through your bank account, so you don't need coins in your inventory. Money is counted in **bits**; the bank, coins and accounts are described in [core's player guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/player-guide.md).

## Opening the Exchange

Right-click an **Exchange** block to open its screen, or type `/exchange` to open it from anywhere, if your server allows it. If you see *"The exchange is only available at an Exchange on this server"*, find the block instead. Anyone can craft one (see [Crafting the Exchange block](#crafting-the-exchange-block)). If you walk more than about 8 blocks away from the block you opened, its screen closes.

The exchange is on unless the server turns it off. If it's off, nothing can be posted, bought or filled: the buttons are greyed out with *"The exchange is turned off on this server"* in their tooltips. You can still open it to empty your [Collection tab](#collection).

## The Exchange

The screen has four tabs: **Browse** to buy what other players offer, **Exchange** to post buy and sell orders, **My Orders** to manage what you've posted, and **Collection** for what you've bought. Your bank balance is at the top right. The search box works on every tab: type part of an item's name, `@create` for one mod's items, or `#ingots` for items with a matching tag. Clicking a line shows it in full; **< Back** returns to the list. Anything that spends money, holds items or changes an order asks you to confirm first, showing exactly what will happen.

The account you're using is your **active account**: your personal account, unless you picked another in the bank (see [Accounts](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/player-guide.md#accounts) in core's player guide).

### Browse

**Browse** lists everything other players have for sale, newest first: their sell orders and their **listings** (enchanted, damaged, renamed and other one-of-a-kind items, offered exactly as they are; their names are shown in purple). Each line shows how many are available, the price each, the seller and how long ago it was posted.

- **Search** to see only matching items.
- **Categories** (down the left, or a button on a narrow window) show one kind of item at a time: Building Blocks, Ores and Minerals, Farming and Food, Weapons and Armor, Tools and Equipment, Mob Drops, Redstone and Technology, Potions and Brewing, and Miscellaneous. Your server may have others. An item can be in more than one.
- **Price** min and max, and **At least**, hide offers outside a price range or with fewer available.
- **Sort** orders them by newest, cheapest, dearest or most available.

Click an offer to see it in full: the whole item as an advanced tooltip shows it (enchantments, durability, name, and its ID), with the seller, how many are left, the price each, and when it expires. Hover over the large icon for the same. Set how many you want and press **Buy N for X**: X is the price with the tax included. After you confirm, it's taken from your bank and the items go to your **Collection** tab. If the seller changed the price in the meantime, nothing is bought and you're told the price changed.

### Exchange: posting orders

**Buy** lists items that have orders (search to find any item at all), with the cheapest seller's price (**Ask**), the highest buyer's price (**Bid**) and how many you have. **Sell** lists what you hold that can be sold.

Click an item to see its **book**, other players' orders for it: sellers above the line (cheapest nearest it), buyers below (highest nearest), each with who posted it and how many units; your own are marked with a *. Beside it is the order form:

- **Quantity**, and **Most each** (buying) or **Least each** (selling). The price starts at the price a trade would happen at now.
- Under the button: what the order comes to, and how many units on the book would trade with it at once.
- **Buy N, holding X** takes X from your bank: the price of all N, the tax on it and any posting fee. It buys straight away from any seller asking your price or less, cheapest first, at *their* price; whatever you saved comes straight back. The rest waits on the book.
- **Sell N for X** takes N from your main inventory (no enchanted, damaged or renamed copies) and holds them. It sells straight away to any buyer bidding your price or more, highest first, at *their* price. The rest waits on the book.

When an order trades, the status line says how much traded at once, for how much, and how many units wait on the book.

On some servers orders don't trade by themselves: they wait until someone picks them. Either way you can **fill another player's order**: click it in the book (a sell order on the Buy tab, a buy order on the Sell tab), and the button becomes *Buy N from Steve* or *Sell N to Alex*. The trade happens at once, at that order's price. Click the order again, or change the price, to post your own order instead. Clicking any order copies its price.

**Listing.** An item with data of its own (enchanted, damaged, renamed, a potion, a levelled relic) can't go on the book. Picking one on the Sell tab lists it at a fixed price instead: set a **Price each** and how many (up to one stack), and press **List N for X**. The items are held until bought, and the listing shows in Browse. Containers such as shulker boxes and bundles can be listed only when empty.

### The tax, and where things go

The buyer pays the exchange tax on top of the price (5% by default, rounded up to a whole bit once per order, however many pieces it buys in); the seller gets the whole price. A **Buy** button always shows what you pay, tax included; a **Sell** or **List** button shows what you get. Some servers also charge a small fee for every order or listing you post, which you don't get back. Items you buy go to your **Collection** tab; money from your sales goes straight into your bank. Both happen even while you're offline, and you get a message when you next log in. Your own orders never trade with each other. You may have up to 10 open orders and listings by default.

### My Orders

**Active** lists your open buy orders, sell orders and listings, newest first, with how far each has filled, its price and total, and whether it's open or partly filled (with the days left). Click one to see it in full: what it holds, its progress, and every trade and change so far. From there you can:

- **Change price**: the order moves to the back of the queue at its new price, and trades at once if it now meets another order. A buy order then holds what its remaining units cost at the new price: you pay more for a higher price, and get the rest back for a lower one. Changing the price costs no fee. A buy order paid from another account can only be raised while that account is your active one.
- **Lower quantity**: take some units off; it keeps its place in the queue. What they held comes back: money to your bank, items to your Collection tab.
- **Cancel order**: what it still holds comes back the same way.

Orders and listings expire after a few in-game days (7 by default), and come back the same way.

**Completed** lists the orders and listings that filled or sold out, with what they came to. **History** lists everything that happened, most recent first: what you bought and sold (with whom, at what price, and the tax), and your orders that were cancelled, expired, changed or closed. The server keeps your last 100 lines by default.

### Collection

**Items received** lists what you bought and the items that came back from your sell orders and listings. Click a line to collect it, or **Collect all**. Only what fits in your free inventory space moves; the rest stays as long as you like. Nothing is ever dropped on the ground. The tab shows how many items wait, and is marked while anything does. If the server turns the exchange off, you can still open the Exchange to empty it.

**Currency received** lists what your sales paid. Sales are paid straight into your bank (the account the order was posted from), so there's nothing to collect: it's there to see.

### Commands

`/exchange orders` lists your orders, listings and collection box in chat; `/exchange book <item>` lists an item's orders with their numbers, and `/exchange fill <number> <amount>` fills one; `buy <item> <amount> <price>`, `sell <item> <amount> <price>`, `cancel <id>` and `collect [item]` do the same as the screen, where the server allows commands. `reprice <id> <price>` changes an order's or listing's price, `reduce <id> <amount>` lowers it to that many still wanted or offered, and `history` shows your history. `/exchange list <amount> <price>` lists the item in your selected hotbar slot, `listings` shows every listing with its number, and `buylisting <number> <amount>` buys from one at its price. Prices are per unit.

## What the exchange accepts

- **Any item**, whether the market buys it or not, except coins.
- **Only items you've unlocked.** On servers with ProgressiveStages, items locked for you are hidden from you on the exchange, and you can't post, buy or fill orders for them.
- **Only your main inventory** (hotbar and backpack rows) when you sell or list. Armour, offhand and modded slots aren't checked.

## Messages

| Message | Meaning |
| --- | --- |
| *Posted an order for N item* | Nothing matched yet; it waits on the book. See it under **My Orders**. |
| *All N item traded at once for X* | Done; bought items are in your Collection tab, sale money in your bank. |
| *The exchange is turned off on this server* | Ask an admin. Your Collection tab still works. |
| *The exchange is only available at an Exchange on this server* | The server keeps the exchange to its block. |
| *You already have as many open orders as you may* | Cancel one, or wait for one to fill. |
| *That order is no longer open* | It filled or was cancelled while you had the screen open. |
| *There aren't that many left* | The order or listing has fewer units left than you asked for. |
| *That item can't be traded on the exchange* | Coins can't be, and neither can items you haven't unlocked. Fresh items go on the book, not in a listing; full shulker boxes and bundles can't be listed. |
| *That's your own listing* / *That's your own order* | Change or cancel it under **My Orders** instead. |
| *Enter a price of at least 1* | Type a price each in the Price box. |
| *You don't have that many (enchanted, damaged or renamed copies don't count)* | You hold fewer plain copies than you tried to sell. |
| *Your bank account can't cover that* | Not enough money for this order, with its tax. |
| *That order is too large* | Orders are capped (6,400 units by default). Split it up. |
| *The price just changed. Check it and try again* | The seller changed the price after you looked. Nothing was bought. |
| *This order was paid from another account* | Switch your active account to that one to raise its price. |
| *The bank is unavailable right now* | The server's bank can't be reached. Tell an admin. |

## Crafting the Exchange block

The Exchange is 8 Bits around a barrel:

```
Bit  Bit     Bit
Bit  Barrel  Bit
Bit  Bit     Bit
```

The Bit is the smallest of core's coins. Your server may use a different recipe; check a recipe viewer if this one doesn't work. The block faces you when you place it. Break it by hand to pick it up again; it breaks faster with an axe.
