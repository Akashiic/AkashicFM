package com.akashiic.fm.compat.oc;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.akashiic.fm.content.TileTransmitter;

import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.prefab.DriverSidedTileEntity;

/** O transmissor visto por um Adaptador do OpenComputers. */
public final class TransmitterDriver extends DriverSidedTileEntity {

    @Override
    public Class<?> getTileEntityClass() {
        return TileTransmitter.class;
    }

    @Override
    public ManagedEnvironment createEnvironment(World world, int x, int y, int z, ForgeDirection side) {
        TileEntity te = world.getTileEntity(x, y, z);
        return te instanceof TileTransmitter ? new TransmitterEnvironment((TileTransmitter) te) : null;
    }
}
