package com.akashiic.fm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.Pos;

/** Auditoria, bloqueio e quem conta como transmissor (sem servidor). */
class AdminLogicTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void linhaDeAuditoriaEmUtc() {
        String line = AuditLog.format(0L, "Fulano", A, "radio.url", "dim 0 (1, 2, 3) -> http://a.com/");
        assertEquals(
            "1970-01-01T00:00:00Z Fulano (00000000-0000-0000-0000-00000000000a) radio.url: dim 0 (1, 2, 3) -> http://a.com/",
            line);
        assertEquals(
            "1970-01-01T00:00:00Z console admin.reload: ok",
            AuditLog.format(0L, null, null, "admin.reload", "ok"));
    }

    @Test
    void ninguemForjaLinhaNoLog() {
        String line = AuditLog
            .format(0L, "Mau\nFulano", null, "x", "ok\r\n2026-01-01T00:00:00Z Admin admin.block: alguém");
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        String sep = AuditLog.format(0L, "a", null, "x", "b" + (char) 0x2028 + "c" + (char) 0x2029);
        assertFalse(sep.indexOf(0x2028) >= 0 || sep.indexOf(0x2029) >= 0);
        StringBuilder big = new StringBuilder();
        while (big.length() < 2000) big.append("abcdefghij");
        assertEquals(
            512,
            AuditLog.clean(big.toString())
                .length());
    }

    @Test
    void bloqueadosPersistem() {
        Moderation m = new Moderation(Moderation.NAME);
        assertTrue(m.block(A, "Fulano"));
        assertFalse(m.block(A, "Fulano")); // já estava
        UUID b = UUID.randomUUID();
        assertTrue(m.block(b, "Outro§c"));
        NBTTagCompound tag = new NBTTagCompound();
        m.writeToNBT(tag);
        NBTTagCompound noId = new NBTTagCompound(); // entrada sem UUID (NBT editado à mão): ignorada
        noId.setString("name", "Fantasma");
        tag.getTagList("blocked", 10)
            .appendTag(noId);
        Moderation r = new Moderation(Moderation.NAME);
        r.readFromNBT(tag);
        assertEquals(
            2,
            r.blocked()
                .size());
        assertNull(r.byName("Fantasma"));
        assertEquals(A, r.byName("fulano")); // sem diferenciar maiúsculas
        assertFalse(
            r.blocked()
                .get(b)
                .contains("§")); // nome saneado
        assertTrue(r.unblock(A));
        assertFalse(r.unblock(A));
        assertNull(r.byName("Fulano"));
    }

    @Test
    void transmissorDeDonoBloqueadoNaoConta() {
        TransmitterIndex.Entry e = new TransmitterIndex.Entry(
            0,
            new Pos(0, 64, 0),
            987,
            64,
            "http://a.com/",
            "",
            A,
            true,
            false);
        assertTrue(FrequencyService.eligible(e, false, () -> false));
        assertFalse(FrequencyService.eligible(e, true, () -> true));
        TransmitterIndex.Entry inactive = new TransmitterIndex.Entry(
            0,
            new Pos(0, 64, 0),
            987,
            64,
            "http://a.com/",
            "",
            A,
            false,
            false);
        assertFalse(FrequencyService.eligible(inactive, false, () -> true));
        // Com energia exigida, só com o chunk carregado (e só pergunta quando precisa).
        TransmitterIndex.Entry powered = new TransmitterIndex.Entry(
            0,
            new Pos(0, 64, 0),
            987,
            64,
            "http://a.com/",
            "",
            A,
            true,
            true);
        assertFalse(FrequencyService.eligible(powered, false, () -> false));
        assertTrue(FrequencyService.eligible(powered, false, () -> true));
        assertFalse(
            FrequencyService
                .eligible(powered, true, () -> { throw new AssertionError("não devia consultar o chunk"); }));
    }

    @Test
    void coordenadaNegativaNoBlocoCerto() {
        // O parser do vanilla devolve -24 + 0,5 para "-24": (int) daria -23, o bloco vizinho.
        assertEquals(-24, FmCommand.blockCoord(-23.5));
        assertEquals(23, FmCommand.blockCoord(23.5));
        assertEquals(-1, FmCommand.blockCoord(-0.5));
        assertEquals(0, FmCommand.blockCoord(0.5));
    }
}
