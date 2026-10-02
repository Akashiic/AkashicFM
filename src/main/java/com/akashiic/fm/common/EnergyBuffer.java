package com.akashiic.fm.common;

/**
 * Energia guardada no transmissor, em EU. Entra EU (IC2/GregTech) ou RF (CoFH, convertido a {@code rfPerEu} RF por
 * EU), com limite por tick e por capacidade; sai o consumo a cada tick transmitindo. Lógica pura, testada em
 * EnergyBufferTest. Só a thread principal do servidor usa.
 */
public final class EnergyBuffer {

    private double capacity;
    private double maxInputPerTick;
    private double stored;
    private double inputThisTick;

    public EnergyBuffer(double capacity, double maxInputPerTick) {
        configure(capacity, maxInputPerTick);
    }

    /**
     * Capacidade e entrada por tick (do config, que o {@code /fm reload} pode mudar com o servidor rodando). Com a
     * capacidade menor, o que passar dela se perde.
     */
    public void configure(double capacity, double maxInputPerTick) {
        this.capacity = Math.max(0, capacity);
        this.maxInputPerTick = Math.max(0, maxInputPerTick);
        if (stored > this.capacity) stored = this.capacity;
    }

    /** Começo de um tick: zera o que entrou. */
    public void startTick() {
        inputThisTick = 0;
    }

    /** EU que ainda cabe neste tick. */
    public double demanded() {
        return Math.max(0, Math.min(capacity - stored, maxInputPerTick - inputThisTick));
    }

    /** Oferece EU; devolve o que sobrou (não aceito). */
    public double offerEu(double amount) {
        if (!(amount > 0)) return 0;
        double take = Math.min(amount, demanded());
        stored += take;
        inputThisTick += take;
        return amount - take;
    }

    /** Oferece RF; devolve quanto RF foi aceito (inteiro). {@code simulate} não muda nada. */
    public int offerRf(int maxReceive, int rfPerEu, boolean simulate) {
        if (maxReceive <= 0 || rfPerEu <= 0) return 0;
        int rf = (int) Math.min(maxReceive, Math.floor(demanded() * rfPerEu + 1e-9));
        if (rf > 0 && !simulate) {
            double eu = rf / (double) rfPerEu;
            stored = Math.min(capacity, stored + eu);
            inputThisTick += eu;
        }
        return Math.max(0, rf);
    }

    /** Gasta {@code eu} se houver; devolve false (sem gastar nada) se não houver o suficiente. */
    public boolean consume(double eu) {
        if (!(eu > 0)) return true;
        if (stored + 1e-9 < eu) return false;
        stored = Math.max(0, stored - eu);
        return true;
    }

    /**
     * Um tick do transmissor; devolve se tem energia. {@code consuming} gasta {@code perTick}; parado, tem energia
     * se daria para transmitir agora. Quem ficou sem energia só volta com {@code reserve} guardado (até a
     * capacidade): com a entrada um pouco abaixo do consumo, o transmissor não liga e desliga a cada tick (cada
     * troca derruba e religa as rádios sintonizadas).
     */
    public boolean tickPower(boolean wasPowered, boolean consuming, double perTick, double reserve) {
        if (!wasPowered && !hasReserve(reserve)) return false;
        if (consuming) return consume(perTick);
        return stored + 1e-9 >= perTick;
    }

    /** Tem a reserva para voltar a transmitir (limitada à capacidade, senão nunca voltaria). */
    public boolean hasReserve(double reserve) {
        return stored + 1e-9 >= Math.min(capacity, reserve);
    }

    public double stored() {
        return stored;
    }

    public double capacity() {
        return capacity;
    }

    /** RF guardado, para a interface do CoFH. */
    public int storedRf(int rfPerEu) {
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(stored * Math.max(0, rfPerEu)));
    }

    public int capacityRf(int rfPerEu) {
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(capacity * Math.max(0, rfPerEu)));
    }

    /** Valor lido do disco (saneado). */
    public void setStored(double eu) {
        stored = eu > 0 ? Math.min(capacity, eu) : 0;
    }
}
