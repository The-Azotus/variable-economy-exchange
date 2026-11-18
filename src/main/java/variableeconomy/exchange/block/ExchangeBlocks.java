package variableeconomy.exchange.block;

import variableeconomy.exchange.ExchangeModule;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Exchange block and its item. */
public final class ExchangeBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ExchangeModule.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ExchangeModule.MOD_ID);

    public static final DeferredBlock<ExchangeBlock> EXCHANGE = BLOCKS.register("exchange",
            () -> new ExchangeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5f)
                    .sound(SoundType.WOOD)));
    public static final DeferredItem<BlockItem> EXCHANGE_ITEM = ITEMS.registerSimpleBlockItem(EXCHANGE);

    private ExchangeBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                event.accept(EXCHANGE_ITEM);
            }
        });
    }
}
