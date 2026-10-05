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

## Estado atual: 1.2.0

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
- **Fase 2:**
  - caixas entram, saem e trocam de canal sem cortar o som (vozes reconciliadas por identidade e alinhadas por amostra);
  - o recarregamento do sistema de som (F3+T, troca de dispositivo) retoma a reprodução sozinho;
  - tela de config dentro do jogo;
  - soak de 10 min sem nenhum vazamento de objeto OpenAL, sem underruns e com heap estável.
  
  Detalhes em [`docs/FASE2-RESULTADOS.md`](docs/FASE2-RESULTADOS.md).
- **Fase 3:**
  - relay: o servidor baixa cada estação uma vez e retransmite em Opus só para quem está no alcance (o IP dos jogadores não vaza);
  - sincronia medida de **0,6 a 6,5 ms** entre dois clientes (meta: menos de 50 ms);
  - banda ≈ 8,5 KB/s por ouvinte;
  - jogador fora do alcance não recebe nada.
  
  Detalhes em [`docs/FASE3-RESULTADOS.md`](docs/FASE3-RESULTADOS.md).
- **Fase 4:**
  - oclusão por blocos: paredes abafam conforme o material (lã muito, pedra bastante, vidro quase nada), com transição suave nas quinas e portas abertas/fechadas;
  - reverb conforme o lugar (sala de pedra, caverna, campo aberto), medido por raios a partir do jogador;
  - com EFX as paredes cortam os agudos; sem EFX, só o volume;
  - provado no áudio de saída gravado: a parede de lã baixa o som em 9 dB e os agudos em 34 dB (exatamente o low-pass calculado), o vidro quase nada, e na sala de pedra a cauda do reverb fica audível.
  
  Detalhes em [`docs/FASE4-RESULTADOS.md`](docs/FASE4-RESULTADOS.md).
- **Fase 5:**
  - "Tocando agora": o título da música na tela da rádio, na GUI, no WAILA e acima da barra de itens (a mesma mensagem dos discos da jukebox) quando você começa a ouvir ou a música muda, também no modo direto;
  - espectro na tela da rádio e cone das caixas pulsando com os graves, alinhados com o que está soando;
  - WAILA opcional.
  
  Detalhes em [`docs/FASE5-RESULTADOS.md`](docs/FASE5-RESULTADOS.md).
- **Fase 6a:**
  - **transmissor de FM:** transmite uma URL numa frequência (87,5 a 108,0 MHz), com nome de estação;
  - **antenas:** empilhadas em cima do transmissor, aumentam o alcance;
  - **rádio no modo FM:** sintoniza a frequência e toca o transmissor de sinal mais forte que cobre o lugar, sem carregar chunk (torres longe funcionam pelo índice do mundo). Fica ligada "sem sinal" e pega sozinha quando um transmissor aparece;
  - **energia opcional:** EU (IC2, cabos do GregTech) ou RF, exigida por padrão quando um desses mods está instalado; sem eles, nunca.
  
- **Fase 6b:**
  - **rádio portátil:** toca de qualquer slot do inventário (URL ou FM, sintonizando o transmissor mais forte onde o jogador está); quem está perto também ouve, com posição;
  - **fone:** no capacete (ou nos slots de cabeça/brinco do Baubles Expanded) o portátil toca só para quem usa, em estéreo e sem reverb;
  - **mesma estação, mesma reprodução:** portátil e rádios tocando a mesma estação ficam sincronizados.
  
  Detalhes em [`docs/FASE6-RESULTADOS.md`](docs/FASE6-RESULTADOS.md).
- **Fase 7a:**
  - **`/fm` para admins:** listar, inspecionar e parar rádios, transmissores e portáteis, recarregar o config, limpar o índice e bloquear jogadores, sem carregar chunk nenhum, e seguro pelo RCON e por pontes de chat;
  - **log de auditoria** em `logs/akashicfm-audit.log`: trocas de URL e frequência e toda ação de admin, em UTC;
  - **bloqueio:** o jogador bloqueado não controla nada, e os transmissores e o portátil dele ficam mudos.
  
  Guia em [`docs/ADMIN.md`](docs/ADMIN.md); detalhes em [`docs/FASE7-RESULTADOS.md`](docs/FASE7-RESULTADOS.md).
- **Fase 7b:**
  - **playlist:** as favoritas em sequência, sem cortar o fim de cada faixa, também para quem está ouvindo junto;
  - **teclas de silenciar:** todas as rádios (o servidor para de mandar áudio para você) ou só a que você olha;
  - **áudio robusto a engasgos:** cada fonte guarda ~1 s de áudio, então um engasgo de 700 ms do jogo não corta o som.
- **Fase 7c:**
  - **OpenComputers (opcional):** o componente `openfm_radio`, com os nomes e as respostas do OpenFM (scripts antigos funcionam por um Adaptador encostado na rádio), e o novo `akashicfm_transmitter`;
  - **seguro:** o computador tem as permissões de um jogador qualquer, passa pela mesma política de URL, faz no máximo 4 mudanças por segundo por bloco e fica no log de auditoria.
- **1.0.0:**
  - **jars de produção testados** num servidor Forge dedicado com um cliente Forge de verdade, nos mods do GTNH 2.7.4, 2.8.4 (estável) e 2.9 (`tools/prod`);
  - **compatível com o GTNH estável:** GTNHLib 0.5.23 ou mais novo, com o `/fm reload` funcionando também nos GTNHLib sem recarregamento próprio;
  - **saída do `/fm` traduzida** (inglês e português).

  Detalhes em [`docs/FASE7-RESULTADOS.md`](docs/FASE7-RESULTADOS.md#100-revisão-final-e-teste-com-os-jars-de-produção).
- **Fase 8 (1.1.0): iPod** (opcional, desligado por padrão):
  - **toca de qualquer slot**, como o rádio portátil (com fone, só para você): links do SoundCloud (faixas, sets, perfis) direto, do YouTube (vídeos e playlists) e do Spotify (faixa, álbum, playlist) pela mesma música achada no SoundCloud, ou uma busca;
  - **fila com controles completos:** tocar/pausar, parar, anterior e próxima, misturar, repetir (tudo ou uma), remover, limpar e volume; a próxima faixa já vem preparada;
  - **feito para o Pterodactyl:** o servidor baixa e atualiza o yt-dlp oficial sozinho (com o SHA-256 conferido), sem shell nem Python; o áudio vai pelo relay;
  - **seguro:** só links do SoundCloud, do YouTube e do Spotify, sem endereço interno; faixas com DRM são puladas, nunca contornadas; cada link vai para o log de auditoria.

  Guia em [`docs/ADMIN.md`](docs/ADMIN.md#ipod-opcional); detalhes em [`docs/FASE8-RESULTADOS.md`](docs/FASE8-RESULTADOS.md).
- **Fase 9 (1.2.0):**
  - **busca com lista de resultados e abas no iPod:** Fila, SoundCloud, YouTube e Spotify; digite o nome da música, escolha na lista (um clique põe na fila, "Tocar agora" toca logo depois da atual). A busca do Spotify é opcional, com uma chave da API no servidor; sem chave, a aba aceita links;
  - **iPod Player:** um bloco que fica no chão e toca uma fila como o iPod, com dono, acesso, alcance, volume, tela, redstone e caixas ligadas pelo sintonizador, como a rádio; sem ninguém por perto, para de baixar e volta quando alguém chega;
  - **alto-falante de teto e de parede:** uma placa fina presa embaixo ou na lateral de um bloco, ligada a uma rádio ou a um iPod Player; cai se o apoio sumir, e ninguém derruba o de outro jogador quebrando o apoio.

  Guia em [`docs/ADMIN.md`](docs/ADMIN.md#ipod-player-bloco); detalhes em [`docs/FASE9-RESULTADOS.md`](docs/FASE9-RESULTADOS.md).

O plano completo está em [`docs/PLANO.md`](docs/PLANO.md).

| Fase | Entrega |
|---|---|
| 0 | Base, CI e spikes ✅ |
| 1 | Núcleo seguro: blocos, estado, rede validada, GUI, engine de áudio, modo direto ✅ |
| 2 | Áudio 3D completo: caixas sem cortes, recarregamento do som (F3+T), convivência com o Hodgepodge, soak ✅ |
| 3 | Relay e sincronia: Opus, audiência, relógio, jitter buffer ✅ |
| 4 | Oclusão e reverb (EFX), com orçamento de raios e fallback só no ganho ✅ |
| 5 | Tocando agora (tela, GUI, aviso, WAILA), espectro e cone animado ✅ |
| 6a | Frequências, transmissor, antenas e energia opcional (EU/RF) ✅ |
| 6b | Rádio portátil e fone (com Baubles opcional) ✅ |
| 7a | Admin (`/fm`), log de auditoria e bloqueio de jogador ✅ |
| 7b | Playlist, teclas de silenciar e áudio robusto a engasgos ✅ |
| 7c | OpenComputers (opcional) ✅ |
| 1.0.0 | Revisão final, teste com os jars de produção e release ✅ |
| 8 (1.1.0) | iPod: SoundCloud direto, YouTube e Spotify pelo espelho, fila e controles ✅ |
| 9 (1.2.0) | Busca com resultados e abas no iPod, iPod Player (bloco) e alto-falante de teto/parede ✅ |

> **Transporte:** por padrão as rádios tocam pelo relay do servidor. O modo direto (`direct.enabled`) é opcional, para servidores sem banda; nele cada cliente baixa o stream sozinho e o IP dos jogadores fica exposto ao servidor do stream.

## Instalação

- **Base:** Minecraft 1.7.10 com Forge 10.13.4.1614; Java 8, ou Java 17+ com o lwjgl3ify (como no GTNH).
- **O mesmo jar no servidor e em todos os clientes**, com **GTNHLib 0.5.23 ou mais novo** e **UniMixins**, que já vêm no pack: testado com os mods de produção do GTNH 2.7.4, 2.8.4 e 2.9.
- **Opcionais**, detectados sozinhos: IC2/GregTech ou RF (energia do transmissor), Baubles Expanded (fone), WAILA e OpenComputers.
- Guia completo do servidor (config, relay, comandos, auditoria) em [`docs/ADMIN.md`](docs/ADMIN.md).

## Como usar

- **Rádio:** coloque, clique com o botão direito, cole a URL de um stream (http/https) e toque.
  - Na tela: volume, alcance, favoritas, texto e cor da tela, acesso (privada ou pública) e redstone (ignorar, tocar enquanto ligada, alternar no pulso).
- **Caixa de som:** com o **sintonizador**, clique na caixa e depois na rádio para ligar as duas.
  - Agachado + clique na caixa troca o canal: mono, esquerdo, direito ou estéreo.
- **Quebrar a rádio** devolve o item com as configurações (URL, favoritas, volume, alcance, tela, modo e frequência).
- **Transmissor de FM:** clique com o botão direito, cole a URL, escolha a frequência e o nome da estação e aperte "No ar".
  - **Antenas:** empilhe em cima do transmissor; cada uma soma alcance (padrão: 64 blocos + 32 por antena, até 16 antenas e 512 blocos).
  - **Energia:** com IC2 ou um mod de RF instalado, ele precisa de energia (8 EU/t por padrão, ou o equivalente em RF) para ficar no ar. Sem energia sai do ar e só volta com uma pequena reserva.
  - **Limite:** 4 transmissores por jogador (config).
- **Rádio portátil:** botão direito abre a tela (URL ou FM, volume, ligar/desligar); agachado + botão direito liga e desliga. Toca de qualquer slot do inventário; se houver mais de um ligado, toca o primeiro. Sem fone, quem está a até 16 blocos (config) também ouve, de onde você está.
- **iPod:** botão direito abre a tela; agachado + botão direito toca e pausa. Cole um link do SoundCloud, do YouTube ou do Spotify (faixa, álbum, playlist, set) ou digite uma busca: as faixas entram na fila e começam a tocar.
  - **Abas:** Fila, SoundCloud, YouTube e Spotify. Numa aba de serviço, digite o nome da música e escolha na lista (até 10 resultados, com artista e duração): um clique põe na fila (e começa, se estava parado), "Tocar agora" toca logo depois da atual. Um link colado em qualquer aba entra na fila direto. A busca do Spotify só existe se o admin pôs a chave da API no servidor; sem ela, a aba aceita links.
  - Na tela: a faixa atual com o progresso, « (anterior; com mais de 5 s tocados, volta ao começo), tocar/pausar, parar, » (próxima), misturar as próximas, repetir (não, tudo, esta faixa), remover, limpar e volume. Clique duplo numa faixa toca ela.
  - Toca de qualquer slot, como o portátil (o primeiro aparelho ligado do inventário decide); quem está perto ouve, e com fone só você.
  - Precisa do iPod ligado no servidor (ver o [guia do admin](docs/ADMIN.md#ipod-opcional)).
- **iPod Player (bloco):** coloque no chão e clique com o botão direito: a mesma tela do iPod (abas, busca, fila), para todos que podem mexer no bloco. O botão **Ajustes**, no canto, tem volume, alcance, acesso (privado ou público), redstone (tocar enquanto ligada, alternar no pulso), texto e cor da tela e as caixas ligadas.
  - Toca pelo relay para quem está no alcance, com caixas de chão e alto-falantes de teto ligados pelo sintonizador, como a rádio. Sem ninguém por perto, para de baixar; volta quando alguém chega.
  - Quebrar devolve o item com a fila (desligado). Conta nos limites de rádios.
- **Alto-falante de teto/parede:** clique embaixo de um bloco (teto) ou na lateral dele (parede); em cima de bloco não vai. Ligue numa rádio ou num iPod Player com o sintonizador, como a caixa de chão (o canal também troca agachado). Precisa de uma face sólida atrás e cai se o apoio sumir; com a proteção ligada, ninguém derruba o alto-falante de outro jogador quebrando o apoio.
- **Fone:** use no slot de capacete (ou, com o Baubles Expanded, nos slots de cabeça ou de brinco). Com ele o portátil toca só para você, em estéreo e sem o reverb da sala.
- **Rádio no modo FM:** na tela da rádio, o botão "URL/FM" troca o modo. No FM, « ‹ › » giram a frequência (±1,0 e ±0,1 MHz; as setas ← → do teclado também) e a rádio toca o transmissor mais forte daquela frequência que alcança o lugar. A tela mostra "Sinal 73% · estação"; sem cobertura, "sem sinal".
- **Playlist:** na tela da rádio, o botão "Playlist" (verde quando ligada) toca as favoritas em sequência.
  - Quando uma termina (arquivo) ou falha, passa para a próxima, em loop, sem cortar o fim da faixa.
  - Se todas falharem em seguida, a rádio para e mostra o motivo.
  - Só no relay: no modo direto o servidor não sabe quando o arquivo acaba.
- **Silenciar:** duas teclas em Controles → AkashicFM, sem tecla padrão (escolha as suas):
  - **"Silenciar todas as rádios":** liga e desliga o som do mod, e o servidor para de mandar áudio para você;
  - **"Silenciar a rádio que estou olhando":** a rádio, a caixa (silencia a rádio dela), o transmissor (silencia a estação dele) ou o portátil de outro jogador, até 32 blocos. Vale só para você e até sair do servidor. A tela e o WAILA mostram "silenciada para você".
- **OpenComputers (opcional):** com um Adaptador encostado, o computador controla a rádio pelo componente `openfm_radio` (os mesmos métodos do OpenFM, então os scripts antigos funcionam) e o transmissor pelo `akashicfm_transmitter`. Com as permissões de um jogador qualquer, a mesma política de URL e um limite por segundo. Detalhes no [guia do admin](docs/ADMIN.md#opencomputers-opcional).
- **No cliente:** volume geral das rádios, limite de rádios simultâneas, a opção de recusar streams diretos, oclusão, reverb, aviso "tocando agora" e visualizador ficam no config. O slider "Jukebox/Discos" do Minecraft também controla as rádios.

## Admin (`/fm`, op)

| Comando | O que faz |
|---|---|
| `/fm list [radios\|transmitters\|portables] [página]` | Rádios e iPod Players tocando (carregados; o bloco mostra a faixa e a fila), transmissores (índice, carregados ou não) ou portáteis e iPods tocando |
| `/fm info [x y z]` | Detalhes da rádio ou do transmissor (sem coordenadas: o bloco que você olha) |
| `/fm stop [x y z]` | Para a rádio ou tira o transmissor do ar |
| `/fm stopall` | Para todas as rádios e transmissores carregados e desliga os portáteis e iPods de quem está online |
| `/fm reload` | Relê do disco o config do servidor (menos a latência do relay, que só muda ao reiniciar) |
| `/fm purge` / `/fm purge player <nome>` | Tira do índice o que não tem mais bloco (só em chunk carregado) / as entradas de um jogador que não dá para confirmar (as de bloco existente voltam quando o chunk carregar) |
| `/fm block <jogador>` / `unblock` / `blocked` | Bloqueia um jogador: não controla rádio, transmissor, portátil nem iPod, e os dele ficam mudos |

Nenhum comando carrega chunk. Toda ação que muda algo (trocas de URL e frequência, links do iPod, comandos de admin) vai para `logs/akashicfm-audit.log`, uma linha por evento em UTC. Detalhes em [`docs/ADMIN.md`](docs/ADMIN.md).

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
tools/e2e/run-acoustic.sh   # grava o áudio de saída e mede abafado e reverb
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
| `protection` | `protectPrivateBlocks` (outros jogadores e máquinas não quebram rádio privada nem o apoio do alto-falante de teto de outro), `opsBypass` |
| `transmitter` | `baseRange` (64), `rangePerAntenna` (32), `maxAntennas` (16), `maxRange` (512), `requireEnergy` (ligado; só vale com IC2 ou RF instalado), `euPerTick` (8), `energyCapacity` (8000), `maxInputPerTick` (128), `rfPerEu` (4), `maxPerPlayer` (4) |
| `portable` | `enabled` (ligado), `range` (16: até onde os outros ouvem o portátil sem fone) |
| `ipod` | `enabled` (**desligado**), `autoInstallTools` (ligado), `ytDlpPath`, `youtubeDirect` (desligado), `spotify` (ligado), `maxResolves` (2), `maxQueue` (50), `maxTrackMinutes` (20), `pauseTimeoutMinutes` (10), `spotifyClientId` e `spotifyClientSecret` (vazios: a busca do Spotify fica desligada) |
| `opencomputers` | `allowPrivate` (desligado: computadores só controlam bloco público ou sem dono) |
| `recipes` | `registerDefaultRecipes` (desligue se o modpack define as próprias) |
| `client` | `enableAudio`, `maxSimultaneousRadios` (4), `allowDirectStreams`, `radioVolume` (100), `enableOcclusion`, `enableReverb`, `acousticOverrides` (`modid:nome=absorção[,amortecimento]`), `showNowPlaying`, `radioVisualizer` |

Cliente e servidor precisam da mesma versão do mod (o FML recusa a conexão se forem diferentes).

## Licenças

O AkashicFM é MIT (ver [`LICENSE`](LICENSE)). Bibliotecas embutidas e relocadas no jar:
- [Concentus](https://github.com/jaredmdobson/concentus): Opus, BSD-3-Clause;
- [JLayer](https://github.com/umjammer/jlayer): MP3, LGPL-2.1;
- [JOrbis](http://www.jcraft.com/jorbis/): OGG/Vorbis, LGPL-2.0;
- [JAAD](https://github.com/sfuhrm/jaad): AAC, domínio público.

O iPod usa o [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense), que não vem no jar: o servidor baixa o binário oficial do release do projeto.

**Créditos:** [OpenFM](https://github.com/PC-Logix/OpenFM) (PC-Logix / Caitlyn, MIT) e Dragon's Radio Mod, pela ideia original.
