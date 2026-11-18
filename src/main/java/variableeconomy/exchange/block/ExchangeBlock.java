package variableeconomy.exchange.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import variableeconomy.exchange.menu.ExchangeMenu;
import variableeconomy.exchange.network.ExchangeSync;

/**
 * A block that opens the player exchange. It works in every access mode; in block-only mode it's the only way for
 * players without operator permission to reach it. The screen closes when the player walks away or the block is
 * broken. A solid block whose front faces whoever placed it.
 */
public final class ExchangeBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<ExchangeBlock> CODEC = simpleCodec(ExchangeBlock::new);

    public ExchangeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            ExchangeMenu.openAt(serverPlayer, pos);
            ExchangeSync.sendFullState(serverPlayer);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
