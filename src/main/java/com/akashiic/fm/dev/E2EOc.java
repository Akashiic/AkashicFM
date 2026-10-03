package com.akashiic.fm.dev;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.akashiic.fm.common.RadioAccess;
import com.akashiic.fm.content.TileRadio;

import li.cil.oc.api.Driver;
import li.cil.oc.api.Network;
import li.cil.oc.api.driver.SidedBlock;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.network.Node;

/**
 * Lado servidor do E2E do OpenComputers (só carrega com o OC instalado, no ambiente de dev): acha o driver do bloco
 * como um Adaptador acharia e chama o componente pelo próprio OC ({@link Component#invoke}), que confere a anotação
 * {@code @Callback} e converte os argumentos como faz para um programa Lua.
 */
final class E2EOc {

    /** Um computador mínimo: só o que o invoke usa. */
    private static final Context CONTEXT = new Context() {

        @Override
        public Node node() {
            return null;
        }

        @Override
        public boolean canInteract(String player) {
            return true;
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public boolean isPaused() {
            return false;
        }

        @Override
        public boolean start() {
            return false;
        }

        @Override
        public boolean pause(double seconds) {
            return false;
        }

        @Override
        public boolean stop() {
            return false;
        }

        @Override
        public void consumeCallBudget(double callCost) {}

        @Override
        public boolean signal(String name, Object... args) {
            return false;
        }
    };

    private E2EOc() {}

    /** {@code x y z método [args...]} → "componente [resultado]" (ou o que faltou). */
    static String call(World world, String[] a) {
        if (a.length < 4) return "uso";
        int x = Integer.parseInt(a[0]), y = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
        List<Object> args = new ArrayList<>();
        for (int i = 4; i < a.length; i++) args.add(parse(a[i]));
        SidedBlock driver = Driver.driverFor(world, x, y, z, ForgeDirection.UNKNOWN);
        if (driver == null || !driver.worksWith(world, x, y, z, ForgeDirection.UNKNOWN)) return "semdriver";
        ManagedEnvironment env = driver.createEnvironment(world, x, y, z, ForgeDirection.UNKNOWN);
        if (env == null || !(env.node() instanceof Component)) return "semcomponente";
        Network.joinNewNetwork(env.node()); // endereço, como numa rede de verdade
        Component c = (Component) env.node();
        try {
            if (!c.methods()
                .contains(a[3])) return c.name() + " semmetodo";
            return c.name() + " " + Arrays.deepToString(c.invoke(a[3], CONTEXT, args.toArray()));
        } catch (Throwable e) { // erro de acesso do OC também: vira resposta, não derruba o chat
            return c.name() + " erro " + e;
        } finally {
            env.node()
                .remove();
        }
    }

    /**
     * A mesma chamada com o bloco privado de um jogador (dono e acesso voltam ao que eram): o computador tem que ser
     * recusado.
     */
    static String callPrivate(World world, String[] a, UUID owner) {
        int x = Integer.parseInt(a[0]), y = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileRadio)) return "semradio";
        TileRadio r = (TileRadio) te;
        UUID oldOwner = r.state.owner;
        RadioAccess oldAccess = r.state.access;
        int oldVolume = r.state.volume;
        r.state.owner = owner;
        r.state.access = RadioAccess.PRIVATE;
        try {
            String result = call(world, a);
            return result + " volume=" + r.state.volume + " antes=" + oldVolume;
        } finally {
            r.state.owner = oldOwner;
            r.state.access = oldAccess;
        }
    }

    private static Object parse(String s) {
        if ("true".equals(s) || "false".equals(s)) return Boolean.valueOf(s);
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return s;
        }
    }
}
