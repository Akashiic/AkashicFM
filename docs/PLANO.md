# Plano: rework do OpenFM para o ecossistema GTNH: **AkashicFM**

## Contexto

O OpenFM 1.7.10 (MIT, parado desde 2018) tem a ideia certa: rádio de internet dentro do jogo. A implementação, porém, não serve para servidor público. A análise anterior encontrou:
- servidor que repassa qualquer pacote do cliente sem validar, com carregamento de chunk pela thread de rede;
- tranca inútil;
- NPE com FakePlayer, que quebra os mineradores do GregTech;
- streams órfãos (o bug de "ouvir a 1000 blocos");
- vazamento de buffers OpenAL (~635 MB/h por stream);
- "stop" que falha durante a conexão;
- dessincronia entre jogadores.

**Objetivo:** um mod novo (modid próprio), modular, construído sobre a stack GTNH, com:
- estado autoritativo no servidor;
- transporte híbrido de áudio: relay pelo servidor em Opus, com sincronia real, ou direto pelo cliente;
- **áudio 3D com oclusão** como recurso premium do MVP.

O OpenFM entra só como referência e crédito (MIT). Nenhum código legado é reaproveitado.

**Decisões do usuário:**
- transporte híbrido (relay + direto);
- mod novo, sem compatibilidade de mundo;
- MVP = núcleo seguro + áudio 3D + oclusão (inclui reverb e caixas estéreo L/R);
- repositório dedicado: [Akashiic/AkashicFM](https://github.com/Akashiic/AkashicFM).

## Ambiente alvo

- **Jogo e Forge:** MC 1.7.10, Forge 10.13.4.1614, buildscript GTNH (`GTNewHorizons/ExampleMod1.7.10`, RetroFuturaGradle).
- **Java:** `enableModernJavaSyntax=jabel` (sintaxe moderna, bytecode Java 8). Precisa rodar em Java 8 e em Java 17 a 25 via lwjgl3ify.
- **Dependências obrigatórias:**
  - **GTNHLib:** `@Config` com GUI automática, `@EventBusSubscriber`, comandos Brigadier e Teams nas fases futuras.
  - **UniMixins:** `usesMixins=true`, `mixinsPackage=...mixins`.
- **Compat opcional** (detectada em runtime):
  - **Hodgepodge:** a mixin `LOGARITHMIC_VOLUME_CONTROL` eleva o volume da categoria ao quadrado em `SoundManager.setSoundCategoryVolume`. Nosso ganho precisa aplicar a mesma curva.
  - **ArchaicFix:** recria o sound system quando o dispositivo de áudio muda. Precisamos recriar nossas fontes.
  - **GeckoLib-Unofficial 1.7.10** (Goodbird-git), na Fase 5.
  - OC, CC, WAILA, ServerUtilities, GT e Baubles nas fases futuras.
- **Bibliotecas embutidas** (`usesShadowedDependencies=true`, relocadas):
  - **Concentus:** Opus em Java puro, `io.github.jaredmdobson:concentus`. Usado também pelo Plasmo Voice.
  - **JLayer** (MP3) e **JOrbis** (Vorbis).
  - **JAAD** (AAC) só como decoder direto e opcional.
  - **Nenhum SPI do Java Sound** é registrado globalmente. Era isso que fazia o JAAD sequestrar o áudio de outros mods.

## Arquitetura (pacotes = módulos)

```
com.akashiic.fm/
  api/        IRadioEmitter, IAudioSource, eventos (RadioStateChanged) — API pública estável
  common/     Config (GTNHLib @Config), Registry, Permissions, Limits
  content/    BlockRadio, BlockSpeaker, TileRadio, TileSpeaker, ItemTuner
  network/    Channel, pacotes C2S (intenções) e S2C (estado/áudio), MainThreadQueue, RateLimiter
  server/
    state/    RadioState (autoritativo, com epoch), RadioIndex (WorldSavedData por dimensão)
    audience/ AudienceTracker: quem ouve qual estação (distância até o emissor + histerese)
    relay/    StationHub, IcyHttpClient, Decoders, Resampler48k, OpusEncoder, FramePacketizer
    policy/   UrlPolicy (allow/deny, só http/https, bloqueio de IP privado/loopback, redirects)
  client/
    audio/    AudioEngine (thread única), SourcePool, StationStream (jitter buffer), ClockSync, BufferPool
    spatial/  OcclusionTracer (DDA voxel), EfxManager (low-pass + reverb, detectado em runtime), RoomProbe
    lifecycle/ desconexão, troca de dimensão, reload do sound system, pausa
    gui/      GuiRadio, GuiSpeakerLink, config GUI (GTNHLib)
    render/   TESR da tela, com estado por TE (corrige o estado global do OpenFM)
  mixins/     hooks de SoundManager (load/unload/reload), early/late via UniMixins
  compat/     hodgepodge, archaicfix (e depois: oc, cc, waila, gt, geckolib, serverutilities)
```

### 1. Estado autoritativo e sincronização de estado
- **`RadioState`:** id, dimensão, posição, `owner` (UUID), modo de acesso (PRIVATE/PUBLIC; Teams depois), fonte (URL), transporte (RELAY ou DIRECT), playing, volume 0 a 100, alcance, `epoch` incremental e `startedAtServerMs`. Persistido no NBT do TE com limites de tamanho; o `RadioIndex` (WorldSavedData) serve para listagem pelos admins.
- **Pacotes C2S são só intenções** (`C2SRadioAction{pos, action, payload}`). O handler só enfileira; a `MainThreadQueue` drena no `ServerTickEvent`, porque o 1.7.10 não tem `addScheduledTask`. A validação no tick checa:
  - `world.blockExists` (nunca carregar chunk);
  - se o TE é nosso;
  - distância de até 8 blocos;
  - permissão (dono, modo de acesso ou op);
  - token bucket por jogador;
  - limites (URL até 512 caracteres);
  - `UrlPolicy`.
- **O servidor nunca repassa pacote de cliente.** Ele envia o próprio `S2CRadioState(state, epoch)` só para quem está vendo o chunk (`PlayerManager.isPlayerWatchingChunk`). Também envia ao receber `ChunkWatchEvent.Watch` e manda remoção no `UnWatch`.
- **No cliente:** aplica o estado só se o `epoch` for mais novo. Heartbeat a cada 10 s como rede de segurança, no lugar do spam a cada 2 s do OpenFM.

### 2. Audiência: elimina o bug dos 1000 blocos na raiz
- **Quem decide quem ouve é o servidor.** O `AudienceTracker` roda a cada 10 ticks e calcula, para cada estação tocando, os jogadores na dimensão a até `alcance + histerese` do emissor mais próximo (a rádio ou uma caixa linkada).
- **Mensagens:** jogador entrando recebe `S2CListenStart(radioId, emissores, codec, pts)`; saindo recebe `S2CListenStop`. Os frames do relay só vão para a audiência.
- **Caixas:** o link é validado no servidor (mesma dimensão, `maxSpeakerDistance` configurável com padrão de 32, dono compatível, cada caixa ligada a uma única rádio). O link fica no TE da caixa e no NBT do Tuner, não num `HashMap` estático.
- **Watchdog no cliente:** sem frames ou heartbeat por N segundos, chunk do emissor descarregado, troca de dimensão ou desconexão, o som para e os recursos são liberados. Não dá mais para ficar som órfão.

### 3. Relay (servidor) e sincronia real
- **`StationHub`:** uma busca por URL normalizada, com refcount pela audiência. A busca para quando ninguém está ouvindo e retoma sob demanda.
- **Pipeline por estação** (thread do executor):
  - `IcyHttpClient` próprio sobre Socket/SSLSocket. Aceita `ICY 200 OK`, segue até 3 redirects revalidando a política, tem timeouts e lê `icy-metaint` para separar o StreamTitle (usado na Fase 5).
  - Detecção de formato (MP3, AAC-ADTS, OGG Vorbis ou Opus) e decoder para PCM.
  - `Resampler48k`.
  - Opus de 20 ms via Concentus, 48 a 96 kbps configurável.
  - PTS = relógio do servidor + `latencyTarget`.
- **Pacotes de áudio:** `S2CAudio(stationId, seq, pts, frames[5])`, uns 100 ms e cerca de 1 KB cada. Os frames entram numa fila lock-free direto para a thread de áudio do cliente, sem passar pela thread principal.
- **Política (anti-SSRF no servidor):**
  - resolve o DNS e bloqueia IPs privados, loopback e link-local;
  - limita bitrate e conexões simultâneas;
  - tetos `maxStations` e `maxRelayListeners`;
  - quando passa do teto, cai para DIRECT (se permitido) ou recusa.
- **Modo DIRECT:** só se o config do servidor permitir e a URL passar na mesma `UrlPolicy`. O cliente também bloqueia IP privado e tem a opção "nunca conectar em URLs externas". A sincronia nesse modo é aproximada, e isso fica documentado.

### 4. Engine de áudio do cliente (resolve leak, órfãos e "stop")
- **Uma `AudioEngine` em thread única** é dona de todas as chamadas AL das nossas fontes. A thread principal só envia comandos (posições e ganhos por tick). Usa o contexto AL do próprio Minecraft com a API LWJGL2, que o lwjgl3ify redireciona.
- **`SourcePool`:** limite de 24 fontes, prioridade por audibilidade. Fonte mono por emissor; o modo estéreo L/R usa 2 fontes mono deslocadas no eixo da face da caixa.
- **Parâmetros por fonte:**
  - `AL_POSITION` = centro do bloco;
  - `AL_ROLLOFF_FACTOR=0`: a atenuação é nossa, então não mexe no modelo global nem no paulscode, e o OpenAL fica só com pan/HRTF;
  - `AL_GAIN = master × records(curva Hodgepodge) × volume × curvaDistância × oclusão`, com suavização.
- **Streaming:**
  - PCM compartilhado entre os emissores da mesma estação;
  - fila de 8 buffers de 50 ms por fonte;
  - desenfileira **todos** os buffers processados e recicla pelo `BufferPool` (o vazamento acaba);
  - backpressure: o decoder espera quando a fila enche;
  - `alSourcePlayv` para começar todos os emissores juntos.
- **Sincronia:**
  - `ClockSync` estilo NTP: ping a cada 5 s, mediana de 8 amostras;
  - início agendado no PTS;
  - drift corrigido com `AL_PITCH` entre 0,999 e 1,001;
  - meta de diferença entre clientes abaixo de 50 ms.
- **Ciclo de vida:**
  - `ClientDisconnectionFromServerEvent` e troca de dimensão param tudo;
  - mixin em `SoundManager` (load/unload/reload, que cobre também F3+T e a recuperação de dispositivo do ArchaicFix) recria as fontes;
  - `mc.isGamePaused()` pausa;
  - um "stop" durante a conexão cancela de verdade (flag volátil + fechamento do socket).

### 5. Áudio 3D, oclusão e reverb (premium do MVP)
- **Oclusão:**
  - `OcclusionTracer`: DDA voxel do olho do jogador até o emissor, até 64 blocos, a cada 4 ticks por emissor audível, com orçamento por tick.
  - Absorção por material vem de um mapa no config (ar 0, vidro 0,1, folhas 0,2, madeira 0,5, pedra 0,8, lã 1,0), com override por bloco.
  - São 5 raios (centro + 4 deslocados ±0,4) e vale o menor valor, o que dá uma difração simples e evita corte seco em quinas.
  - A absorção vira ganho e um low-pass EFX (`AL_FILTER_LOWPASS` / GAINHF).
- **`EfxManager`:** detecta `ALC_EXT_EFX` em runtime. Sem EFX, a oclusão fica só no ganho.
- **Reverb:** o `RoomProbe` lança 16 raios a partir do jogador a cada 1 s. O caminho livre médio e o grau de fechamento escolhem um preset de reverb EFX num aux slot compartilhado; o send é ponderado pela oclusão.
- **HRTF:** usa a do OpenAL Soft quando o driver/config ativa. Ligar por código (`ALC_SOFT_HRTF`) fica como experimental e opt-in, só com lwjgl3ify.

### 6. Conteúdo do MVP
- **BlockRadio:** GUI com URL, play/stop, slider de volume, alcance, modo de acesso, transporte mostrado e lista de caixas.
- **BlockSpeaker:** tem orientação, que define o estéreo L/R.
- **ItemTuner:** link validado no servidor.
- **TESR:** tela com rolagem por TE.
- **Receitas:** configuráveis.
- **Idiomas:** `pt_BR` e `en_US`.
- **Quebra de bloco:** checa FakePlayer, sem NPE com o GT. A proteção de rádio trancada contra máquinas é configurável.
- **Segurança mínima no MVP** (o pacote completo de admin vem depois): tudo do item 1, mais limite de rádios por jogador e por chunk e os comandos `/fm stopall` e `/fm reload` (op, Brigadier do GTNHLib).

## Fases

| Fase | Entrega | MVP |
|---|---|---|
| 0 | Repo, buildscript GTNH, CI (GitHub Actions), `runClient`/`runServer`. **Spikes:** (a) fonte AL posicional + EFX em Java 8/LWJGL2 e Java 21/lwjgl3ify; (b) throughput do Concentus na JVM do servidor; (c) `IcyHttpClient` contra streams reais em MP3, AAC e OGG | ✅ |
| 1 | Núcleo seguro: conteúdo, `RadioState`, rede, permissões, persistência, GUI, modo DIRECT tocando pela engine nova (ainda sem 3D) | ✅ |
| 2 | Áudio 3D: SourcePool, ganho posicional, caixas, estéreo L/R, mixins de ciclo de vida, compat Hodgepodge/ArchaicFix | ✅ |
| 3 | Relay + sincronia: StationHub, Opus, AudienceTracker, ClockSync, jitter buffer, UrlPolicy anti-SSRF | ✅ |
| 4 | Oclusão + reverb (EFX), orçamento de raycast, opções no config | ✅ |
| 5 | Now playing (ICY → tela, HUD, WAILA), visualizador de espectro, modelos animados com GeckoLib | |
| 6 | Frequências e torres (transmissor, registro de frequências, alcance pela antena, EU do GT, rádio portátil/fone com Baubles) | |
| 7 | Admin completo (`/fm`, permissões e claims do ServerUtilities, logs, mute por jogador), API OC/CC com fila e rate limit, playlists | |

**Riscos:**
- ~~EFX/HRTF sob lwjgl3ify não estão documentados~~. **Resolvido no spike 0a:** EFX (low-pass + reverb) funciona em Java 8/LWJGL2 e em Java 21/lwjgl3ify, e o HRTF é uma opção do próprio lwjgl3ify. Fica o fallback só com ganho para o macOS com Java 8.
- CPU e banda do relay (há tetos e fallback DIRECT; se o Concentus pesar demais, a alternativa é passar os frames comprimidos sem transcodificar).
- Retransmitir rádios de terceiros pelo servidor é redistribuição de conteúdo. O admin decide a lista de domínios permitidos.

## Arquivos críticos a criar (repo novo)
- **Build:** `gradle.properties` (modId, `usesMixins`, `mixinsPackage`, `usesShadowedDependencies`), `dependencies.gradle` (GTNHLib, UniMixins, Concentus, JLayer, JOrbis), `repositories.gradle`.
- **Rede e estado:** `src/main/java/com/akashiic/fm/network/{Channel,MainThreadQueue,RateLimiter}.java` e `server/state/RadioState.java`.
- **Servidor:** `server/audience/AudienceTracker.java`, `server/relay/{StationHub,IcyHttpClient,OpusEncoder}.java`, `server/policy/UrlPolicy.java`.
- **Cliente:** `client/audio/{AudioEngine,SourcePool,StationStream,ClockSync,BufferPool}.java`, `client/spatial/{OcclusionTracer,EfxManager,RoomProbe}.java`.
- **Mixins e conteúdo:** `mixins/early/MixinSoundManager.java` e `content/{TileRadio,TileSpeaker,BlockRadio,BlockSpeaker,ItemTuner}.java`.
- **Crédito:** README e `mcmod.info` creditando o OpenFM (MIT).

## Verificação
- **Testes unitários** (JUnit no buildscript):
  - `UrlPolicy` (IPs privados, redirects);
  - `RateLimiter`;
  - ida e volta dos codecs de pacote;
  - ordem por `epoch`;
  - curva de ganho;
  - `OcclusionTracer` numa grade sintética;
  - parser de ICY metadata;
  - `Resampler48k`;
  - ida e volta do Opus.
- **Integração:** `./gradlew runServer` + 2× `runClient --username=A/B`, com este checklist:
  1. Os dois clientes ouvem a mesma estação com diferença abaixo de 50 ms (log do PTS na hora de tocar).
  2. `/tp` de 1000 blocos: o som para em até 1 s (o bug original).
  3. Morrer e renascer perto da rádio não duplica o som. Desconectar deixa o menu em silêncio.
  4. F3+T ou troca de dispositivo de áudio: o som volta.
  5. Parede de lã abafa mais que parede de vidro, e a transição numa quina é suave.
  6. Harness de cliente malicioso (coordenada longe, rádio de outro jogador, URL gigante, flood) é rejeitado e nenhum chunk carrega (contagem de chunks carregados igual antes e depois).
  7. 1 h AFK perto da rádio: memória estável e contagem de buffers AL constante.
  8. Upload do servidor ≈ bitrate × ouvintes, e jogador fora do alcance não recebe nada.
- **Smoke test no pack GTNH real:** cliente Java 8 e cliente Java 21 (lwjgl3ify), com Hodgepodge, ArchaicFix e Angelica presentes.
- **CI:** build + testes em todo push.
