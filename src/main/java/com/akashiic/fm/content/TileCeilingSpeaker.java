package com.akashiic.fm.content;

/**
 * O alto-falante de teto/parede: uma caixa de som como a de chão (rádio ligada, canal, dono), só que o som sai do
 * centro da placa e a orientação do estéreo vem de como ela foi presa ({@link CeilingMount}).
 */
public class TileCeilingSpeaker extends TileSpeaker {

    @Override
    public double[] emitterPoint() {
        double[] e = CeilingMount.emitter(worldMeta());
        return new double[] { xCoord + e[0], yCoord + e[1], zCoord + e[2] };
    }

    @Override
    public int stereoFacing() {
        return CeilingMount.stereoFacing(worldMeta());
    }

    /** O bloco que segura a placa. */
    public int[] supportPos() {
        int[] d = CeilingMount.supportOffset(worldMeta());
        return new int[] { xCoord + d[0], yCoord + d[1], zCoord + d[2] };
    }
}
