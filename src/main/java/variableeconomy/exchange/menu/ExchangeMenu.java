package variableeconomy.exchange.menu;

import variableeconomy.exchange.ExchangeSettings;
import variableeconomy.exchange.ExchangeModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import variableeconomy.exchange.block.ExchangeBlocks;

/**
 * The exchange screen's server-side half. It has no slots: everything travels in the exchange's own packets. It marks
 * which players have the exchange open, so requests from anyone else are ignored and updates go only to viewers.
 * Opened from an Exchange block it stays valid only while the player is near that block; opened by command, only while
 * the access mode allows that player to use commands.
 */
public final class ExchangeMenu extends AbstractContainerMenu {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ExchangeModule.MOD_ID);
    public static final DeferredHolder<MenuType<?>, MenuType<ExchangeMenu>> TYPE =
            MENUS.register("exchange", () -> IMenuTypeExtension.create((id, inventory, data) -> new ExchangeMenu(id, ContainerLevelAccess.NULL)));

    /** The block this was opened from, or {@link ContainerLevelAccess#NULL} if opened by command (or on the client). */
    private final ContainerLevelAccess block;

    private ExchangeMenu(int containerId, ContainerLevelAccess block) {
        super(TYPE.get(), containerId);
        this.block = block;
    }

    /** Opens the exchange by command. */
    public static void open(ServerPlayer player) {
        open(player, ContainerLevelAccess.NULL);
    }

    /** Opens the exchange from the block at {@code pos}. */
    public static void openAt(ServerPlayer player, BlockPos pos) {
        open(player, ContainerLevelAccess.create(player.level(), pos));
    }

    private static void open(ServerPlayer player, ContainerLevelAccess block) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ExchangeMenu(id, block),
                Component.translatable("screen.variable_economy_exchange.exchange")));
    }

    /** A server-side menu for the block at {@code pos}, without opening a screen. For tests. */
    public static ExchangeMenu forBlock(ServerPlayer player, BlockPos pos) {
        return new ExchangeMenu(0, ContainerLevelAccess.create(player.level(), pos));
    }

    /** A server-side menu as {@code /exchange} opens it, without opening a screen. For tests. */
    public static ExchangeMenu forCommand() {
        return new ExchangeMenu(0, ContainerLevelAccess.NULL);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (block != ContainerLevelAccess.NULL) {
            // Vanilla's container rule: the block must still be there and the player within 8 blocks of it.
            return stillValid(block, player, ExchangeBlocks.EXCHANGE.get());
        }
        return ExchangeSettings.commandsAllowed(player);
    }
}
