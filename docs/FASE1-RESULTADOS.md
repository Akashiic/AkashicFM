# Fase 1: resultados

A Fase 1 entrega o núcleo seguro do mod e a reprodução em **modo direto**, com áudio posicional. Tudo foi verificado em jogo, com servidor e dois clientes reais.

## O que entrou

### Servidor: estado autoritativo e rede validada
- **Estado da rádio** (`RadioState`) só muda no servidor. Toda leitura de NBT, do disco ou da rede, passa por saneamento: limites rígidos (`RadioLimits`), texto sem códigos `§` nem formatação invisível, listas cortadas, enums fora da faixa voltam ao padrão.
- **Ações do cliente** (`C2SRadioAction`) são só intenções:
  - a thread de rede só enfileira, com rate limit por jogador (token bucket);
  - o tick principal valida, nesta ordem: jogador conectado, chunk **já carregado** (`blockExists`, nunca carrega chunk por causa de um pacote), TE certo, distância de uso (8 blocos), permissão da ação e, por último, conteúdo.
- **Política de URL** (`UrlPolicy`):
  - roda ao salvar a URL, ao tocar (de novo, porque a URL pode vir do NBT de um item) e na manutenção periódica (a allowlist pode ter mudado);
  - IP literal interno é recusado já no `check`, sem DNS: `127.0.0.1`, `2130706433`, `[::1]`, `[::ffff:10.0.0.1]`, `localhost.`;
  - o motivo da recusa é traduzível (`akashicfm.policy.<código>`).
- **Permissões** (`Permissions`): dono, rádio pública ou privada, ops com bypass configurável. FakePlayers (máquinas do GT) nunca causam NPE e não quebram blocos privados.
- **Estado para os clientes** vai pelo pacote de descrição do TE (S35), só para quem está vendo o chunk, com `epoch`/`session` para descartar estado velho.
- **Persistência:**
  - TE da rádio com `shouldRefresh` corrigido;
  - índice global de rádios por dono (`RadioIndex`) para o limite por jogador;
  - limite por chunk;
  - o item quebrado leva as configurações.
- **Caixas de som e sintonizador:**
  - ligação validada no servidor (distância, dono, capacidade, dimensão);
  - canal MIX/L/R/estéreo trocado agachado;
  - manutenção a cada 5 s remove caixas que sumiram, foram religadas ou ficaram longe demais, sem carregar chunk.

### Cliente: engine de áudio
- **`AudioEngine`:**
  - todo OpenAL do mod roda na thread principal, por frame, sob um lock;
  - um contador de geração invalida ids quando o contexto AL é recriado;
  - mixin em `LibraryLWJGLOpenAL.init/cleanup`, com fallback por reflection que acompanha a identidade do SoundSystem e do contexto AL;
  - watchdog de 2 s: som órfão não existe.
- **`Playback`/`Voice`:**
  - pool fixo de 10 buffers por fonte (memória do OpenAL não cresce);
  - todas as vozes começam juntas (`alSourcePlayv`);
  - prebuffer e recuperação de underrun;
  - ganho suavizado.
- **`DirectFeed`:**
  - HTTP/ICY → detecção de formato → decoder (MP3, AAC/HE-AAC, OGG Vorbis/Opus) → 48 kHz → ring de 8 s com backpressure;
  - reconexão com backoff, que só zera depois de 5 s de áudio saudável (um stream que cai a cada segundo não vira uma conexão por segundo para sempre);
  - arquivo que falha no meio não recomeça do início.
- **`RadioAudioController`:**
  - entram as rádios carregadas no mundo atual, dentro do alcance (com histerese de 4 blocos), contando só caixas cujo chunk o cliente tem;
  - as N mais próximas tocam (config), com teto global de 96 fontes AL;
  - com volume Master, Discos ou o da rádio em zero, nada baixa;
  - a rádio soa como par estéreo L/R e cada caixa no seu canal;
  - atenuação própria (sem rolloff do OpenAL): volume cheio até 2 blocos e zero exato no alcance.
- **GUI** (`GuiRadio`):
  - URL, tocar/parar, volume e alcance (enviados ao soltar o slider);
  - favoritas, texto e cor da tela, acesso, redstone, desvincular caixas;
  - status da reprodução e avisos do servidor;
  - controles habilitados conforme as permissões que o servidor informa.
- **Tela da rádio** (TESR): texto com brilho próprio e rolagem tipo letreiro.
- **Idiomas e arte:** `pt_BR` e `en_US`, 78+ chaves, com teste de paridade. Texturas originais geradas por `tools/gen_textures.py`.

## Achados da revisão linha a linha (corrigidos)
- **`getCompoundTag` do 1.7.10 derruba o jogo** se a tag existir com outro tipo. Um sintonizador ou uma rádio com NBT adulterado (creative, `/give`) derrubaria o servidor. Todos os acessos agora conferem o tipo.
- **`getString` do 1.7.10 devolve o `toString()`** de uma tag de outro tipo. A leitura de strings agora é estrita.
- **Allowlist contornável:** `applyPlay` não revalidava a URL, que podia vir do item ou ser anterior a uma mudança de config.
- **`TextSanitizer`** tirava só o `§` (`§kX` virava `kX`). Agora tira o código inteiro e também a formatação invisível (U+202E, zero-width).
- **`DirectFeed`:**
  - stream ao vivo que cai sem áudio zerava o backoff (reconexão a cada 1 s para sempre);
  - queda por exceção depois de horas tocando contava como falha (o stream morreria de vez depois de 5 quedas).
- **Controlador:** a distância considerava caixas em chunk não carregado, o que abriria um stream mudo.
- **GUI:** o clique numa favorita mandava só o índice. Agora manda também a URL vista, e o servidor confere, porque a lista pode ter mudado no meio.

## Verificação

- **Build:** `./gradlew build` (spotless, checkstyle e testes) verde, com **118 testes JUnit**:
  - `RadioState` (ida e volta, saneamento, NBT adulterado);
  - `GainModel`;
  - `PcmRing` (wrap, bloqueio, fechamento, cancelamento);
  - `RateLimiter` (relógio injetado);
  - `TextSanitizer`, `Facing`;
  - codecs dos pacotes (string gigante de cliente malicioso);
  - lógica do servidor (tocar, transporte, favoritas, redstone);
  - `UrlPolicy` (literais internos, formas IPv4 do Java);
  - paridade das traduções.
- **E2E em jogo** (`tools/e2e/`, só no ambiente de desenvolvimento): servidor dedicado + clientes reais sob Xvfb, OpenAL Soft (backend null), rádio real (Radio Paradise) pelo proxy.

| Cenário (`tools/e2e/run-all.sh`, mundo limpo a cada rodada) | Java 21 + lwjgl3ify + Hodgepodge | Java 8 (LWJGL2) |
|---|---|---|
| `main+peer` (op): ir ao spawn, colocar rádio, cliente malicioso, tocar em modo direto, 5 s contínuos sem underrun, tela com texto, GUI com permissões, sintonizador + caixa + canais até estéreo + desvincular, esperar o 2º jogador, teleporte de 1000 blocos, voltar, parar, tocar de novo, desconectar, reconectar, ouvir de novo | **28/28** | **28/28** |
| `peer` (Player2, não-op): ouve a mesma rádio, recebe controle sem admin, é recusado ao mudar o acesso, vê a parada e silencia | **7/7** | **7/7** |
| `listen`: rádio salva tocando, servidor reiniciado (mundo do servidor Java 8 aberto no Java 21), cliente entra e ouve | **4/4** | |

Números medidos:
- **Teleporte de 1000 blocos:** silêncio em **0 a 2 ticks** (o bug original do OpenFM era som tocando a 1000 blocos).
- **Cliente malicioso:**
  - pacotes para coordenadas a 100.000 blocos, y = -5, y = 300 e a 29.999.000;
  - URL interna de 15.000 caracteres (cortada para 512 na leitura e recusada com `akashicfm.policy.internal`);
  - 200 ações num tick: 182 barradas pelo rate limit;
  - o servidor confirma que os chunks das coordenadas maliciosas **não** foram carregados (`chunkExists = false`).
- **Áudio:**
  - 5 s de relógio entregam 5,12 s de PCM ao OpenAL, com 0 underruns;
  - fontes AL em `AL_PLAYING` = vozes criadas (nenhuma fonte órfã);
  - ao desconectar: 0 reproduções e 0 threads `AkashicFM-Direct-*`.
- **Contexto AL:** o gancho do mixin dispara na criação (gerações 1 → 3 com o recarregamento de recursos da inicialização).

**Notas do ambiente:**
- **Pacotes:** o `runClient` (Java 8) roda sem Hodgepodge, e o `runServer21` roda com ele. O Hodgepodge altera o formato de pacotes vanilla, então um cliente Java 8 de dev não conecta num servidor Java 21 de dev. Por isso foram testados os pares Java 8 + Java 8 e Java 21 + Java 21. No GTNH real os dois lados têm Hodgepodge.
- **Reconexão sem Hodgepodge (Java 8 de dev):** reconectar com o mesmo nome 2 s depois de sair falhou dentro do FML (`ChannelRegistrationHandler`: `NetHandlerLoginClient` → `NetHandlerPlayClient`), antes de qualquer código do mod rodar. Com 5 s de espera conecta. É uma corrida do login do FML 1.7.10, que o Hodgepodge corrige (`MixinNetworkDispatcher`, `AwaitPreviousSession`); com ele (Java 21) reconecta sempre.
- **Lição do harness:**
  - o mundo de teste precisa ser limpo a cada rodada, porque rádios e posições salvas mudam o resultado;
  - o 2º jogador nasce com a dispersão de spawn (até ~10 blocos), por isso o principal (op) o traz para dentro do alcance de uso;
  - nas duas situações o mod se comportou certo: recusou ações a mais de 8 blocos com `too_far`.

## Como rodar o E2E

```bash
./gradlew jar
tools/e2e/capture.sh runServer21      # uma vez: guarda a linha de comando do servidor de dev
tools/e2e/capture.sh runClient21      # idem para o cliente
tools/e2e/run-all.sh                  # servidor + main+peer + peer; sai com 1 se algo falhar
tools/e2e/run-all.sh runServer runClient   # o mesmo em Java 8 (capture as duas tasks antes)
```

Os logs ficam em `build/e2e/logs/`. O driver só liga com `AKASHICFM_E2E=<cenário>` **e** no ambiente deobfuscado de desenvolvimento: num jar de produção ele não roda, mesmo que alguém defina a variável.

## Limitações conhecidas (próximas fases)
- O **relay** (padrão do config) é da Fase 3. Até lá, uma rádio só toca com `direct.enabled=true` no servidor, e o modo direto expõe o IP de cada jogador ao servidor do stream.
- Sincronia entre jogadores no modo direto é aproximada (cada cliente conecta sozinho). A sincronia de verdade vem com o relay.
- Oclusão e reverb são da Fase 4.
