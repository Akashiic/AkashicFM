# Guia do admin

Como instalar e operar o AkashicFM num servidor multiplayer: o que vai no servidor, o que cada opção do config faz, os comandos `/fm`, o log de auditoria e o bloqueio de jogadores.

## Instalação

**Base:** Minecraft 1.7.10 com Forge 10.13.4.1614; Java 8, ou Java 17+ com o lwjgl3ify (como no GTNH).

**No servidor e em todos os clientes**, o mesmo jar (o Forge recusa a conexão com versões diferentes):
- `akashicfm-<versão>.jar`;
- **GTNHLib** 0.5.23 ou mais novo (obrigatório): testado com os mods de produção do GTNH 2.7.4, 2.8.4 e 2.9. Com um mais antigo, o Forge mostra a tela de dependência faltando;
- **UniMixins** (obrigatório; já vem em qualquer pack GTNH).

**Opcionais** (o mod detecta sozinho, sem config):

| Mod | O que liga |
|---|---|
| IC2 / GregTech | Energia do transmissor em EU (cabos do GT) |
| Qualquer mod com a API de RF do CoFH | Energia do transmissor em RF |
| Baubles Expanded | Fone nos slots de cabeça e brinco |
| WAILA | Rádio, caixa e transmissor no WAILA |
| OpenComputers | Componentes `openfm_radio` e `akashicfm_transmitter` (ver abaixo) |

O jar já traz os codecs (Opus, MP3, OGG Vorbis/Opus, AAC/HE-AAC), relocados. Não precisa de mais nada.

**Rede:**
- **Nenhuma porta nova.** O áudio do relay vai pela própria conexão do Minecraft.
- O servidor precisa de **HTTP/HTTPS de saída** para os hosts das rádios.

**Custo:**

| Recurso | Custo |
|---|---|
| CPU | Cerca de 2% de um núcleo por estação diferente tocando (não por rádio nem por ouvinte) |
| Banda de upload | Cerca de 8,5 KB/s por jogador ouvindo, com o Opus a 64 kbps (padrão) |
| Banda de download | Uma vez cada estação |

## Relay ou modo direto

| | Relay (padrão) | Modo direto |
|---|---|---|
| Quem baixa o stream | O servidor, uma vez por estação | Cada cliente |
| IP dos jogadores | Não aparece para o host da rádio | Exposto ao host da rádio |
| Sincronia entre jogadores | Medida de 0,6 a 6,5 ms | Aproximada |
| Banda do servidor | Cerca de 8,5 KB/s por ouvinte | Nenhuma |
| Config | `relay.enabled=true` | `direct.enabled=true` |

Com os dois ligados, o relay tem preferência. O modo direto entra só quando uma estação não cabe nos limites do relay (`maxStations`).

**Quem não ouve não gasta banda.** Um jogador que silencia todas as rádios (tecla), desliga o áudio do mod ou zera o volume sai da audiência do relay, como quem está longe: o servidor para de mandar áudio para ele. Uma estação que fica sem ouvintes fecha em 10 s.

**Playlist:** só no relay. No modo direto ela não avança, porque o servidor não sabe quando o arquivo termina.

## Config (`config/akashicfm.cfg`)

As categorias abaixo valem no servidor. **Recarregáveis** com `/fm reload`, sem reiniciar, exceto onde a tabela diz o contrário.

### `relay`

| Chave | Padrão | O que faz |
|---|---|---|
| `enabled` | `true` | Liga o relay. Desligado com o servidor rodando, ele encerra as estações e avisa os ouvintes; as rádios passam para o modo direto (se permitido) ou param. |
| `opusBitrateKbps` | `64` | Bitrate do Opus por ouvinte (24 a 128). Vale para as estações que começarem depois do reload. |
| `maxStations` | `8` | Estações diferentes baixadas ao mesmo tempo. |
| `maxListeners` | `64` | Jogadores recebendo áudio pelo relay ao mesmo tempo, somando todas as estações. |
| `latencyTargetMs` | `1500` | Atraso fixo até a reprodução (300 a 5000). Abaixo de ~1000, sobra menos folga para engasgos do jogo dos clientes (cada fonte guarda até 1 s de áudio). **Só ao reiniciar o servidor:** os ouvintes conectados guardam a latência antiga. |

### `direct`

| Chave | Padrão | O que faz |
|---|---|---|
| `enabled` | `false` | Permite o modo direto. |

### `policy`

| Chave | Padrão | O que faz |
|---|---|---|
| `allowedHosts` | vazio | Domínios permitidos (subdomínios incluídos). Vazio = qualquer host público. |
| `allowHighPorts` | `true` | Aceita portas acima de 1024 além de 80 e 443. |

Regras fixas, que nenhuma opção libera:
- endereços internos (localhost, redes privadas, link-local) são sempre recusados;
- portas baixas além de 80 e 443 também.

Ao recarregar uma allowlist mais restrita, as rádios que tocam uma URL que deixou de valer param sozinhas.

### `limits`

| Chave | Padrão | O que faz |
|---|---|---|
| `maxRadiosPerPlayer` | `16` | Rádios por jogador |
| `maxRadiosPerChunk` | `4` | Rádios por chunk |
| `maxSpeakersPerRadio` | `8` | Caixas por rádio |
| `maxSpeakerDistance` | `32` | Distância máxima caixa–rádio, em blocos |
| `maxRange` | `48` | Alcance máximo que um jogador escolhe para a rádio |
| `actionsPerSecond` | `10` | Ações por segundo de cada jogador (o excesso é descartado) |

### `protection`

| Chave | Padrão | O que faz |
|---|---|---|
| `protectPrivateBlocks` | `true` | Ninguém além do dono (e dos ops) quebra rádio e caixa privadas. Máquinas e FakePlayers são recusados sem erro. |
| `opsBypass` | `true` | Ops controlam qualquer rádio e ignoram os limites por jogador (o limite por chunk vale para todos). |

### `transmitter`

| Chave | Padrão | O que faz |
|---|---|---|
| `baseRange` | `64` | Alcance sem antenas |
| `rangePerAntenna` | `32` | Alcance extra por antena empilhada |
| `maxAntennas` | `16` | Antenas que contam |
| `maxRange` | `512` | Teto do alcance |
| `requireEnergy` | `true` | Exige energia. Só vale com IC2 ou um mod de RF instalado; sem eles, nunca exige. |
| `euPerTick` | `8` | Consumo enquanto transmite |
| `energyCapacity` | `8000` | EU guardados no transmissor. Com o reload, vale na hora (o que passar da capacidade nova se perde). |
| `maxInputPerTick` | `128` | Entrada máxima por tick, aceita de qualquer tensão (nada explode) |
| `rfPerEu` | `4` | Conversão de RF para EU |
| `maxPerPlayer` | `4` | Transmissores por jogador |

O alcance novo vale em até 1 s; o consumo, na hora.

### `portable`

| Chave | Padrão | O que faz |
|---|---|---|
| `enabled` | `true` | Liga o rádio portátil. Desligado, os portáteis ficam mudos. |
| `range` | `16` | Até onde os outros jogadores ouvem o portátil de alguém sem fone (4 a 64) |

### `opencomputers`

| Chave | Padrão | O que faz |
|---|---|---|
| `allowPrivate` | `false` | Computadores mexem também em rádios e transmissores privados, inclusive na tela, na redstone e no nome da estação. Desligado, valem as permissões de um jogador qualquer (ver "OpenComputers"). |

### Fora do reload

- **`recipes`:** `registerDefaultRecipes` só vale ao reiniciar o jogo.
- **`client`:** cada jogador tem o seu (volume, rádios simultâneas, oclusão, reverb etc.), pela tela de config do mod.

**GTNHLib anterior à 0.9.62** (GTNH 2.7 e 2.8): ele não tem o recarregamento próprio. O `/fm reload` relê o arquivo por outro caminho, com o mesmo resultado; a latência do relay continua só ao reiniciar.

## Comandos (`/fm`, op nível 2)

| Comando | O que faz |
|---|---|
| `/fm list radios [página]` | Rádios tocando, com chunk carregado: posição, URL ou frequência, transporte, dono |
| `/fm list transmitters [página]` | Todos os transmissores do índice, carregados ou não: frequência, nome, alcance, no ar ou não, dono bloqueado |
| `/fm list portables [página]` | Portáteis tocando agora: portador, URL, transporte, fone e quantos ouvem |
| `/fm info [x y z]` | Detalhes da rádio ou do transmissor. Sem coordenadas, o bloco que você olha (até 8 blocos). |
| `/fm stop [x y z]` | Para a rádio ou tira o transmissor do ar |
| `/fm stopall` | Para todas as rádios e transmissores carregados e desliga os portáteis de quem está online |
| `/fm reload` | Relê o config do servidor (ver acima) |
| `/fm purge` | Tira do índice as entradas sem bloco, só em chunk carregado |
| `/fm purge player <nome>` | Esquece as entradas de um jogador que não dá para confirmar: sem bloco, ou em chunk descarregado. As de bloco carregado ficam. Um bloco que ainda existir volta ao índice quando o chunk dele carregar. Serve para destravar o limite de alguém depois de um rollback ou de chunks apagados. |
| `/fm block <jogador>` | Bloqueia: ver abaixo |
| `/fm unblock <jogador>` | Desbloqueia |
| `/fm blocked` | Lista os bloqueados |

**Garantias:**
- **Nenhum comando carrega chunk.** O que está descarregado aparece pelo índice e só muda quando o chunk carregar.
- **Coordenadas aceitam `~`** (relativas a você); valores negativos acertam o bloco certo.
- **Funciona pelo console, pelo RCON e por pontes de chat.** No 1.7.10, o RCON (sem o `fixRconThreading` do Hodgepodge) e as pontes de chat que chamam o comando da própria thread rodam o comando fora da thread principal. O `/fm` passa a execução para a thread principal e devolve a resposta a quem mandou. Se o servidor estiver travado por mais de 10 s, o comando roda no próximo tick e o resultado fica no log de auditoria.

## Log de auditoria

Fica em `logs/akashicfm-audit.log`, com uma linha por evento e horário em UTC. Também vai para o log do servidor, com o prefixo `[audit]`.

```
2026-10-02T17:00:00Z Fulano (0b7c…-uuid) radio.url: dim 0 (10, 64, -3) -> https://stream.exemplo.com/live
2026-10-02T17:01:12Z Fulano (0b7c…-uuid) transmitter.frequency: dim 0 (40, 70, 12) -> 98.7
2026-10-02T17:05:40Z Admin (5f1d…-uuid) admin.block: Fulano (0b7c…-uuid)
2026-10-02T17:06:02Z console admin.stopall: 3 rádios, 1 transmissores, 2 portáteis
```

**O que entra:**

| Origem | Eventos |
|---|---|
| Jogadores | Trocas de URL (`radio.url`, `transmitter.url`, `portable.url`) e de frequência do transmissor (`transmitter.frequency`) |
| `/fm` | Toda ação que muda algo: `admin.stop`, `admin.stopall`, `admin.reload`, `admin.purge`, `admin.block`, `admin.unblock` |

**Formato:**
- Texto vindo de jogador é limpo: quebra de linha e caractere de controle viram espaço, e cada campo tem no máximo 512 caracteres. Ninguém forja uma linha falsa.
- O arquivo gira em 10 MB e guarda o anterior como `akashicfm-audit.log.1`.
- Sem permissão de escrita em `logs/`, o mod avisa uma vez e segue registrando só no log do servidor.

## Bloqueio de jogador

`/fm block <jogador>` vale para quem está online e para quem já entrou alguma vez (cache de perfis do servidor). Fica salvo no mundo, em `data/akashicfm_moderation.dat`.

O jogador bloqueado:
- **não controla** rádio, transmissor nem portátil: a tela abre só para olhar e as ações recebem o aviso "Um admin bloqueou você de usar rádios neste servidor.";
- **tem as rádios e os transmissores carregados parados** na hora;
- **tem os transmissores tirados do ar para as rádios sintonizadas**, inclusive os de chunk descarregado (ficam marcados como "dono bloqueado" no `/fm list transmitters`);
- **fica com o portátil mudo**, para ele e para quem está perto.

`/fm unblock` devolve o controle. O que foi parado continua parado até o dono ligar de novo.

## OpenComputers (opcional)

Com o OpenComputers instalado, o computador alcança a rádio e o transmissor por um **Adaptador** encostado no bloco.

**Componente `openfm_radio`:** o nome e os métodos do OpenFM, para os scripts antigos funcionarem sem mudança.
- **Do OpenFM:**
  - tocar e parar: `start()`/`play()`, `stop()`, `isPlaying()`;
  - URL: `setURL(url)`;
  - volume: `setVol(0–10)`, `getVol()` (de 0 a 1), `volUp()`, `volDown()`;
  - tela: `setScreenColor(0xRRGGBB)`, `getScreenColor()`, `setScreenText(texto)`;
  - caixas: `getAttachedSpeakerCount()`, `getAttachedSpeakers()`;
  - redstone: `setListenRedstone(bool)`, `getListenRedstone()`;
  - `greet()`.
- **Novos:** `getURL()`, `getScreenText()`, `setMode("url"|"fm")`, `getMode()`, `setFrequency(98.7)`, `getFrequency()`, `getNowPlaying()`, `getSignal()` (sinal e estação sintonizada), `setPlaylist(bool)`, `getPlaylist()`.

**Componente `akashicfm_transmitter`:**
- no ar: `start()`, `stop()`, `isBroadcasting()` (ligado e, de fato, no ar);
- estação: `setURL(url)`/`getURL()`, `setFrequency(98.7)`/`getFrequency()`, `setName(texto)`/`getName()`;
- cobertura e energia: `getRange()` (alcance e antenas), `getEnergy()` (EU e capacidade, ou `false` sem energia exigida).

Erros voltam como `false, "motivo"`, como no OpenFM.

**Segurança** (o OpenFM não tinha nenhuma: qualquer computador com cabo mandava em qualquer rádio):

| Regra | Como funciona |
|---|---|
| Permissões | As de um jogador qualquer: controla (tocar, URL, volume, frequência, playlist) bloco **público ou sem dono**; tela, redstone e nome da estação só em bloco **sem dono**. `opencomputers.allowPrivate` libera tudo. |
| Dono bloqueado | Com `/fm block`, sempre recusado |
| URL | Passa pela mesma política das rádios (`policy`) |
| Ritmo | No máximo 4 mudanças por segundo por bloco |
| Auditoria | Trocas de URL e de frequência vão para o log, com o endereço do computador como autor (`oc:<endereço>`) |

As chamadas rodam na thread principal do servidor, uma por tick por computador, como todo componente do OC.

## Solução de problemas

| Sintoma | Onde olhar |
|---|---|
| "URL recusada: … não está na lista de sites permitidos" | `policy.allowedHosts` |
| "URL recusada: … é um endereço privado ou local" | A URL aponta para um endereço interno (sempre recusado) |
| "O servidor já está retransmitindo o máximo de estações" | `relay.maxStations`: estações diferentes ao mesmo tempo |
| Transmissor "fora do ar" com URL e antenas | Energia (`/fm info` mostra `energia=atual/capacidade`) ou dono bloqueado |
| Jogador não consegue colocar mais rádios | `limits.maxRadiosPerPlayer`; depois de rollback, `/fm purge player <nome>` |
| Mudou o config e nada aconteceu | `/fm reload` (a latência do relay só muda ao reiniciar) |
