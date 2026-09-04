package com.uup.blockentity;

import com.uup.core.network.NetworkController;
import com.uup.setup.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class PipeBlockEntity extends BlockEntity {

    private final NetworkController standaloneNetwork = new NetworkController();

    public PipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PIPE.get(), pos, state);
    }

    public void markNetworkDirty() {
        standaloneNetwork.markNetworkDirty();
    }

    public void serverTick(ServerLevel level) {
        if (standaloneNetwork.isMasterPipe(worldPosition) || !standaloneNetwork.hasController()) {
            standaloneNetwork.tick(level, worldPosition);
        }
    }
}
