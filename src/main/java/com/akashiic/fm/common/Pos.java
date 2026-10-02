package com.akashiic.fm.common;

import net.minecraft.nbt.NBTTagCompound;

/** Posição de bloco imutável (o 1.7.10 não tem BlockPos). */
public final class Pos {

    public final int x;
    public final int y;
    public final int z;

    public Pos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double centerX() {
        return x + 0.5;
    }

    public double centerY() {
        return y + 0.5;
    }

    public double centerZ() {
        return z + 0.5;
    }

    /** Quadrado da distância entre o centro deste bloco e um ponto. */
    public double distanceSqTo(double px, double py, double pz) {
        double dx = centerX() - px;
        double dy = centerY() - py;
        double dz = centerZ() - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Quadrado da distância entre os centros de dois blocos. */
    public double distanceSqTo(Pos o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        return tag;
    }

    public static Pos fromNbt(NBTTagCompound tag) {
        return new Pos(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Pos)) return false;
        Pos p = (Pos) o;
        return x == p.x && y == p.y && z == p.z;
    }

    @Override
    public int hashCode() {
        int h = x * 31 + y;
        return h * 31 + z;
    }

    @Override
    public String toString() {
        return x + ", " + y + ", " + z;
    }
}
