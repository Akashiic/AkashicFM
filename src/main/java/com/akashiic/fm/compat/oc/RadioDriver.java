package com.akashiic.fm.compat.oc;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.akashiic.fm.content.TileIPodPlayer;
import com.akashiic.fm.content.TileRadio;

import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.prefab.DriverSidedTileEntity;

/** A rádio vista por um Adaptador do OpenComputers. */
public final class RadioDriver extends DriverSidedTileEntity {

    @Override
    public Class<?> getTileEntityClass() {
        return TileRadio.class;
    }

    /** O bloco do iPod é uma rádio por dentro, mas toca uma fila: os comandos de rádio (URL, modo) não valem nele. */
    @Override
    public boolean worksWith(World world, int x, int y, int z, ForgeDirection side) {
        return super.worksWith(world, x, y, z, side) && !(world.getTileEntity(x, y, z) instanceof TileIPodPlayer);
    }

    @Override
    public ManagedEnvironment createEnvironment(World world, int x, int y, int z, ForgeDirection side) {
        TileEntity te = world.getTileEntity(x, y, z);
        return te instanceof TileRadio && !(te instanceof TileIPodPlayer) ? new RadioEnvironment((TileRadio) te) : null;
    }
}
