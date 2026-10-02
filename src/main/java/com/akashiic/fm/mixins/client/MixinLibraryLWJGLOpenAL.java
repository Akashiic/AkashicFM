package com.akashiic.fm.mixins.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashiic.fm.client.audio.AudioEngine;

import paulscode.sound.libraries.LibraryLWJGLOpenAL;

/**
 * Avisa a engine de áudio quando o contexto OpenAL nasce e antes de ele morrer (F3+T, troca de pacote de
 * recursos, troca de dispositivo pelo ArchaicFix, saída do jogo). A biblioteca do Hodgepodge estende esta
 * classe sem sobrescrever {@code init}/{@code cleanup}, então os ganchos valem para ela também.
 * <p>
 * {@code cleanup} no HEAD roda antes de {@code AL.destroy()}: as fontes e buffers do mod são apagados com o
 * contexto ainda válido. {@code init} no RETURN só dispara se a inicialização deu certo.
 */
@Mixin(value = LibraryLWJGLOpenAL.class, remap = false)
public abstract class MixinLibraryLWJGLOpenAL {

    @Inject(method = "init", at = @At("RETURN"))
    private void akashicfm$contextCreated(CallbackInfo ci) {
        AudioEngine.INSTANCE.onContextCreated();
    }

    @Inject(method = "cleanup", at = @At("HEAD"))
    private void akashicfm$contextDestroying(CallbackInfo ci) {
        AudioEngine.INSTANCE.onContextDestroying();
    }
}
