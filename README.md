# AkashiFM (nome provisório)

Rádio de internet para **Minecraft 1.7.10** (Forge), feita para servidor público e construída sobre a stack GTNH (GTNHLib, UniMixins). É uma reescrita inspirada no [OpenFM](https://github.com/PC-Logix/OpenFM), que é MIT: nenhum código dele foi reaproveitado, só a ideia.

## O que muda em relação ao OpenFM

| Problema no OpenFM 1.7.10 | Como o AkashiFM resolve |
|---|---|
| Servidor repassa qualquer pacote do cliente sem validar, e o handler carrega chunks pela thread de rede | Estado autoritativo no servidor; o cliente só manda intenções, validadas no tick principal (distância, dono, rate limit, limites) |
| Som continua tocando a 1000 blocos (stream órfão quando o chunk descarrega) | Quem decide quem ouve é o servidor (audiência por distância), com watchdog no cliente |
| Vazamento de buffers OpenAL (~635 MB/h por stream) | Engine única com pool de buffers e backpressure |
| Jogadores ouvem a mesma rádio fora de sincronia | Relay em Opus com relógio sincronizado (meta: menos de 50 ms entre clientes) |
| Clientes conectam em qualquer URL (vaza o IP dos jogadores, SSRF) | O relay baixa uma vez no servidor; `UrlPolicy` recusa endereços internos e aceita allowlist de domínios |
| Para de tocar na troca de música (OGG encadeado) | Decoder OGG que ressincroniza em stream lógico novo |
| Só 44,1 kHz | Resampler para 48 kHz: MP3, AAC/HE-AAC, OGG Vorbis e OGG Opus |
| Som sem posição | Áudio 3D posicional, oclusão por blocos e reverb por sala (EFX) |

## Estado atual: Fase 0

Feito:
- buildscript GTNH configurado;
- CI;
- pipeline do relay validado contra 9 rádios reais (MP3, AAC, HE-AAC, OGG Vorbis e OGG Opus);
- custo medido: cerca de **2% de um núcleo por estação**;
- testes da política anti-SSRF e do decoder OGG;
- OpenAL posicional, low-pass e reverb EFX validados dentro do cliente, em Java 8 (LWJGL2) e em Java 21 (lwjgl3ify).

Os detalhes estão em [`docs/FASE0-RESULTADOS.md`](docs/FASE0-RESULTADOS.md) e o plano completo em [`docs/PLANO.md`](docs/PLANO.md).

| Fase | Entrega |
|---|---|
| 0 | Base, CI e spikes ✅ (0a, 0b e 0c concluídos) |
| 1 | Núcleo seguro: blocos, estado, rede validada, GUI, modo direto |
| 2 | Áudio 3D: fontes posicionais, caixas de som, estéreo L/R |
| 3 | Relay e sincronia: Opus, audiência, relógio, jitter buffer |
| 4 | Oclusão e reverb |
| 5+ | Now playing, visual (GeckoLib), frequências e torres, admin completo |

## Compilar e testar

```bash
./gradlew build          # compila, roda os testes e gera o jar em build/libs
./gradlew test           # só os testes (não usam rede)
./gradlew runClient      # cliente de desenvolvimento
./gradlew runServer      # servidor de desenvolvimento
./gradlew spotlessApply  # formata o código no padrão GTNH (o CI confere)
```

Para rodar a sonda de rádios reais (precisa de internet), use a classe `com.akashiic.fm.tools.RelayProbe` dos testes:

```bash
java -cp <classpath de teste> com.akashiic.fm.tools.RelayProbe 20 https://stream.radioparadise.com/mp3-128
```

## Config (`config/akashifm.cfg`)

| Categoria | Opções |
|---|---|
| `relay` | `enabled`, `opusBitrateKbps` (64), `maxStations` (8), `maxListeners` (64), `latencyTargetMs` (1500) |
| `direct` | `enabled`. Padrão **desligado**, porque o modo direto expõe o IP dos jogadores à URL |
| `policy` | `allowedHosts` (vazio = qualquer host público), `allowHighPorts` |
| `limits` | `maxRadiosPerPlayer`, `maxSpeakersPerRadio`, `maxSpeakerDistance` |

## Licenças

O AkashiFM é MIT (ver [`LICENSE`](LICENSE)). Bibliotecas embutidas e relocadas no jar:
- [Concentus](https://github.com/jaredmdobson/concentus): Opus, BSD-3-Clause;
- [JLayer](https://github.com/umjammer/jlayer): MP3, LGPL-2.1;
- [JOrbis](http://www.jcraft.com/jorbis/): OGG/Vorbis, LGPL-2.0;
- [JAAD](https://github.com/sfuhrm/jaad): AAC, domínio público.

**Créditos:** [OpenFM](https://github.com/PC-Logix/OpenFM) (PC-Logix / Caitlyn, MIT) e Dragon's Radio Mod, pela ideia original.
