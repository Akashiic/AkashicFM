# AkashicFM

Rádio de internet para **Minecraft 1.7.10** (Forge), feita para servidor público e construída sobre a stack GTNH (GTNHLib, UniMixins). É uma reescrita inspirada no [OpenFM](https://github.com/PC-Logix/OpenFM), que é MIT: nenhum código dele foi reaproveitado, só a ideia.

## O que muda em relação ao OpenFM

| Problema no OpenFM 1.7.10 | Como o AkashicFM resolve |
|---|---|
| Servidor repassa qualquer pacote do cliente sem validar, e o handler carrega chunks pela thread de rede | Estado autoritativo no servidor; o cliente só manda intenções, validadas no tick principal (distância, dono, rate limit, limites) |
| Som continua tocando a 1000 blocos (stream órfão quando o chunk descarrega) | Quem decide quem ouve é o servidor (audiência por distância), com watchdog no cliente |
| Vazamento de buffers OpenAL (~635 MB/h por stream) | Engine única com pool de buffers e backpressure |
| Jogadores ouvem a mesma rádio fora de sincronia | Relay em Opus com relógio sincronizado (meta: menos de 50 ms entre clientes) |
| Clientes conectam em qualquer URL (vaza o IP dos jogadores, SSRF) | O relay baixa uma vez no servidor; `UrlPolicy` recusa endereços internos e aceita allowlist de domínios |
| Para de tocar na troca de música (OGG encadeado) | Decoder OGG que ressincroniza em stream lógico novo |
| Só 44,1 kHz | Resampler para 48 kHz: MP3, AAC/HE-AAC, OGG Vorbis e OGG Opus |
| Som sem posição | Áudio 3D posicional, oclusão por blocos e reverb por sala (EFX) |

## Estado atual: Fase 1

Feito:
- **Fase 0:** buildscript GTNH, CI, pipeline de áudio validado contra 9 rádios reais (MP3, AAC, HE-AAC, OGG Vorbis e OGG Opus), cerca de 2% de um núcleo por estação, EFX validado em Java 8 e Java 21. Detalhes em [`docs/FASE0-RESULTADOS.md`](docs/FASE0-RESULTADOS.md).
- **Fase 1:**
  - rádio, caixa de som e sintonizador;
  - estado autoritativo no servidor, com rede validada e rate limit;
  - permissões (dono, pública/privada, ops) e proteção contra quebra, segura com máquinas do GT;
  - limites por jogador e por chunk;
  - GUI completa e tela da rádio com texto;
  - engine de áudio nova: posicional, estéreo L/R, caixas por canal, sem vazamento de buffers, silêncio imediato ao se afastar ou sair;
  - modo direto tocando;
  - verificação em jogo com servidor e dois clientes reais, em Java 8 e Java 21.
  
  Detalhes em [`docs/FASE1-RESULTADOS.md`](docs/FASE1-RESULTADOS.md).

O plano completo está em [`docs/PLANO.md`](docs/PLANO.md).

| Fase | Entrega |
|---|---|
| 0 | Base, CI e spikes ✅ |
| 1 | Núcleo seguro: blocos, estado, rede validada, GUI, engine de áudio, modo direto ✅ |
| 2 | Áudio 3D completo: recarregamento do som (F3+T), convivência com Hodgepodge/ArchaicFix, teste longo |
| 3 | Relay e sincronia: Opus, audiência, relógio, jitter buffer |
| 4 | Oclusão e reverb |
| 5+ | Now playing, visual, frequências e torres, admin completo |

> **Importante até a Fase 3:** o relay (transporte padrão) ainda não existe. Para as rádios tocarem, o servidor precisa de `direct.enabled=true`. No modo direto cada cliente baixa o stream sozinho, o que expõe o IP dos jogadores ao servidor do stream.

## Como usar

- **Rádio:** coloque, clique com o botão direito, cole a URL de um stream (http/https) e toque.
  - Na tela: volume, alcance, favoritas, texto e cor da tela, acesso (privada ou pública) e redstone (ignorar, tocar enquanto ligada, alternar no pulso).
- **Caixa de som:** com o **sintonizador**, clique na caixa e depois na rádio para ligar as duas.
  - Agachado + clique na caixa troca o canal: mono, esquerdo, direito ou estéreo.
- **Quebrar a rádio** devolve o item com as configurações (URL, favoritas, volume, alcance, tela).
- **No cliente:** volume geral das rádios, limite de rádios simultâneas e a opção de recusar streams diretos ficam no config. O slider "Jukebox/Discos" do Minecraft também controla as rádios.

## Compilar e testar

```bash
./gradlew build          # compila, roda os testes e gera o jar em build/libs
./gradlew test           # só os testes (não usam rede)
./gradlew runClient      # cliente de desenvolvimento
./gradlew runServer      # servidor de desenvolvimento
./gradlew spotlessApply  # formata o código no padrão GTNH (o CI confere)
```

Teste de ponta a ponta em jogo (servidor e dois clientes reais sob Xvfb; ver [`docs/FASE1-RESULTADOS.md`](docs/FASE1-RESULTADOS.md)):

```bash
./gradlew jar
tools/e2e/capture.sh runServer21 && tools/e2e/capture.sh runClient21   # uma vez
tools/e2e/run-all.sh
```

Para rodar a sonda de rádios reais (precisa de internet), use a classe `com.akashiic.fm.tools.RelayProbe` dos testes:

```bash
java -cp <classpath de teste> com.akashiic.fm.tools.RelayProbe 20 https://stream.radioparadise.com/mp3-128
```

## Config (`config/akashicfm.cfg`)

| Categoria | Opções |
|---|---|
| `relay` | `enabled`, `opusBitrateKbps` (64), `maxStations` (8), `maxListeners` (64), `latencyTargetMs` (1500) |
| `direct` | `enabled`. Padrão **desligado**, porque o modo direto expõe o IP dos jogadores à URL |
| `policy` | `allowedHosts` (vazio = qualquer host público; endereços internos sempre recusados), `allowHighPorts` |
| `limits` | `maxRadiosPerPlayer` (16), `maxSpeakersPerRadio` (8), `maxSpeakerDistance` (32), `maxRadiosPerChunk` (4), `maxRange` (48), `actionsPerSecond` (10) |
| `protection` | `protectPrivateBlocks` (outros jogadores e máquinas não quebram rádio privada), `opsBypass` |
| `recipes` | `registerDefaultRecipes` (desligue se o modpack define as próprias) |
| `client` | `enableAudio`, `maxSimultaneousRadios` (4), `allowDirectStreams`, `radioVolume` (100) |

Cliente e servidor precisam da mesma versão do mod (o FML recusa a conexão se forem diferentes).

## Licenças

O AkashicFM é MIT (ver [`LICENSE`](LICENSE)). Bibliotecas embutidas e relocadas no jar:
- [Concentus](https://github.com/jaredmdobson/concentus): Opus, BSD-3-Clause;
- [JLayer](https://github.com/umjammer/jlayer): MP3, LGPL-2.1;
- [JOrbis](http://www.jcraft.com/jorbis/): OGG/Vorbis, LGPL-2.0;
- [JAAD](https://github.com/sfuhrm/jaad): AAC, domínio público.

**Créditos:** [OpenFM](https://github.com/PC-Logix/OpenFM) (PC-Logix / Caitlyn, MIT) e Dragon's Radio Mod, pela ideia original.
