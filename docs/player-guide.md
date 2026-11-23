# Player guide

The **Exchange** is an add-on for Variable Economy that lets you trade with other players at prices you choose. Everything is paid through your bank account, so you don't need coins in your inventory. Money is counted in **bits**; the bank, coins and accounts are described in [core's player guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/player-guide.md).

## Opening the Exchange

Right-click an **Exchange** block to open its screen, or type `/exchange` to open it from anywhere, if your server allows it. If you see *"The exchange is only available at an Exchange on this server"*, find the block instead. Anyone can craft one (see [Crafting the Exchange block](#crafting-the-exchange-block)). If you walk more than about 8 blocks away from the block you opened, its screen closes.

The exchange is on unless the server turns it off. If it's off, nothing can be posted, bought or filled: the buttons are greyed out with *"The exchange is turned off on this server"* in their tooltips. You can still open it to empty your [box](#your-box).

## The Exchange

The button in the toolbar switches the list between **Items**, **Listings**, **List an item**, **My orders** and your **Box**.

**Items** lists the items that have orders, then the items you carry, with the cheapest seller's price (**Ask**), the highest buyer's price (**Bid**) and how many you have. Type in the search box to find any item at all, listed on the market or not.

### Orders and the book

Click an item to see its **book**: each order on it, what sellers ask above the line (cheapest nearest the line), what buyers bid below it (highest nearest), with who posted it and how many units. Your own are marked with a *; hover over one to see how long it has left. The **Price each** box starts at the cheapest ask, or the highest bid if nobody is selling, or blank if the item has no orders. Then set a quantity and press:

- **Buy N for X**: X is what posting the order takes from the account you're using: the price of all N, the tax on it and any posting fee. The price and tax are held until the order fills. It buys straight away from any seller asking your price or less, cheapest first, and pays *their* price; whatever you saved comes straight back. The rest waits on the book for sellers.
- **Sell N for X**: takes N from your main inventory (no enchanted, damaged or renamed copies) and holds them. It sells straight away to any buyer bidding your price or more, highest first, at *their* price. The rest waits on the book for buyers.

On some servers orders don't trade by themselves: they wait until someone picks them. Either way you can **fill another player's order**: click it in the book, and **Buy** becomes *Buy N from Steve* for a sell order (or **Sell** becomes *Sell N to Alex* for a buy order). Set how many and press it; the trade happens at once, at that order's price. Click the order again, or change the price, to post your own order instead.

The account you're using is your **active account**: your personal account, unless you picked another in the bank (see [Accounts](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/player-guide.md#accounts) in core's player guide).

### The tax, and where things go

The buyer pays the exchange tax on top of the price (5% by default, rounded up to a whole bit once per order, however many pieces it buys in); the seller gets the whole price. A **Buy** button always shows what you pay, tax included; a **Sell** or **List** button shows what you get. The tooltips break it down. Some servers also charge a small fee for every order or listing you post, which you don't get back. Items you buy go to your **collection box**. You're paid and your box fills even while you're offline; you get a message when you next log in. Your own orders never trade with each other. You may have up to 10 open orders by default.

### Listings

**Listings** are for items the book can't take: enchanted, damaged, renamed and other one-of-a-kind items (potions, enchanted books, tipped arrows, levelled relics too). Only fresh copies go on the book, exactly as the market sells them. A seller puts up one stack's worth of one exact item at a fixed price each, and buyers take it at that price; nothing is matched or haggled. The list shows each listing's price and how many are left, with a * on your own. Hover over one, or click it, to see the item in full: enchantments, durability, name. Set how many you want and press **Buy N for X**: X, the price with the tax included, is taken now and the items go to your **collection box**, exactly as listed. The total under the panel is the same amount. The **Sort** button in the panel sorts the listings by item, cheapest, dearest, newest or ending soonest, and each listing shows how long it has left.

**List an item** shows what in your main inventory can be listed. Click one, set a **Price each** and how many (up to one stack; **All** fills in what you have, up to a stack), and press **List N for X**. The items are taken from your inventory and held until bought. Each sale is paid into your bank in full, as with sell orders; buyers pay the tax. Containers such as shulker boxes and bundles can be listed only when empty. Listings count toward your open orders.

### My orders

**My orders** lists your open orders and listings with their price and how much has filled or sold. Click one, then **Cancel order** to get back what it still holds: a buy order's money to your bank, the items of a sell order or listing to your box. Orders and listings expire after a few in-game days (7 by default); the panel shows the days left, and an expired one is returned the same way.

### Your box

**Box** lists what waits for you. Click a line to take that item out, or **Take all**. Only what fits in your free inventory space moves; the rest stays as long as you like. Nothing is ever dropped on the ground. If the server turns the exchange off, you can still open the Exchange to empty your box.

### Commands

`/exchange orders` lists your orders, listings and box in chat; `/exchange book <item>` lists an item's orders with their numbers, and `/exchange fill <number> <amount>` fills one; `buy <item> <amount> <price>`, `sell <item> <amount> <price>`, `cancel <id>` and `collect [item]` do the same as the screen, where the server allows commands. `/exchange list <amount> <price>` lists the item in your selected hotbar slot, `listings` shows every listing with its number, and `buylisting <number> <amount>` buys from one at its price. Prices are per unit.

## What the exchange accepts

- **Any item**, whether the market buys it or not, except coins.
- **Only items you've unlocked.** On servers with ProgressiveStages, items locked for you are hidden from you on the exchange, and you can't post, buy or fill orders for them.
- **Only your main inventory** (hotbar and backpack rows) when you sell or list. Armour, offhand and modded slots aren't checked.

## Messages

| Message | Meaning |
| --- | --- |
| *Posted an order for N item* | Nothing matched yet; it waits on the book. See it under **My orders**. |
| *All N item traded at once for X* | Done; bought items are in your box, sale money in your bank. |
| *The exchange is turned off on this server* | Ask an admin. Your box still works. |
| *The exchange is only available at an Exchange on this server* | The server keeps the exchange to its block. |
| *You already have as many open orders as you may* | Cancel one, or wait for one to fill. |
| *That order is no longer open* | It filled or was cancelled while you had the screen open. |
| *There aren't that many left* | The order or listing has fewer units left than you asked for. |
| *That item can't be traded on the exchange* | Coins can't be, and neither can items you haven't unlocked. Fresh items go on the book, not in a listing; full shulker boxes and bundles can't be listed. |
| *That's your own listing* / *That's your own order* | Cancel it under **My orders** instead. |
| *Enter a price of at least 1* | Type a price each in the Price box. |
| *You don't have that many (enchanted, damaged or renamed copies don't count)* | You hold fewer plain copies than you tried to sell. |
| *Your bank account can't cover that* | Not enough money for this order, with its tax. |
| *That order is too large* | Orders are capped (6,400 units by default). Split it up. |
| *The bank is unavailable right now* | The server's bank can't be reached. Tell an admin. |

## Crafting the Exchange block

The Exchange is 8 Bits around a barrel:

```
Bit  Bit     Bit
Bit  Barrel  Bit
Bit  Bit     Bit
```

The Bit is the smallest of core's coins. Your server may use a different recipe; check a recipe viewer if this one doesn't work. The block faces you when you place it. Break it by hand to pick it up again; it breaks faster with an axe.
