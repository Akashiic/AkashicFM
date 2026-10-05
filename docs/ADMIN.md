# Guia do admin

Como instalar e operar o AkashicFM num servidor multiplayer: o que vai no servidor, o que cada opção do config faz, os comandos `/fm`, o log de auditoria, o bloqueio de jogadores, o iPod (opcional), o iPod Player e o alto-falante de teto/parede.

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
- O iPod (desligado por padrão) precisa também do GitHub (para baixar o yt-dlp), do SoundCloud e do CDN dele, e do YouTube e do Spotify (só os dados das músicas). A busca do Spotify, com a chave opcional, usa `accounts.spotify.com` e `api.spotify.com`.

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
| `enabled` | `true` | Liga o relay. Desligado com o servidor rodando, ele encerra as estações e avisa os ouvintes; as rádios passam para o modo direto (se permitido) ou param, e continuam paradas quando o relay volta (o iPod e o iPod Player voltam sozinhos). |
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

O **iPod Player** conta como rádio nesses limites (por jogador, por chunk, caixas por bloco e distância das caixas). Os alto-falantes de teto e de parede contam como caixas.

### `protection`

| Chave | Padrão | O que faz |
|---|---|---|
| `protectPrivateBlocks` | `true` | Ninguém além do dono (e dos ops) quebra rádio, iPod Player e caixa privados, nem o bloco que segura o alto-falante de teto/parede de outro jogador. Máquinas e FakePlayers são recusados sem erro. |
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

### `ipod`

| Chave | Padrão | O que faz |
|---|---|---|
| `enabled` | `false` | Liga o iPod. Desligado, os iPods ficam mudos e o yt-dlp não é baixado. |
| `autoInstallTools` | `true` | Baixa o yt-dlp oficial para `akashicfm/tools/`, confere o SHA-256 publicado no release e procura atualização uma vez por dia. |
| `ytDlpPath` | vazio | Caminho de um yt-dlp já instalado (vazio = o baixado pelo mod). |
| `youtubeDirect` | `false` | Tenta o YouTube direto antes do espelho. Só funciona fora de datacenter (ver "iPod"); baixa o Deno (~100 MB). |
| `spotify` | `true` | Aceita links do Spotify (faixa, álbum, playlist de até 100 faixas). |
| `maxResolves` | `2` | Processos do yt-dlp ao mesmo tempo (1 a 8). Cada um usa ~100 MB de memória, fora do `-Xmx`, por alguns segundos. |
| `maxQueue` | `50` | Faixas na fila de um iPod (1 a 200). Uma playlist maior é cortada. |
| `maxTrackMinutes` | `20` | Duração máxima de uma faixa (mixes maiores são recusados). |
| `pauseTimeoutMinutes` | `10` | Minutos de pausa até o iPod parar sozinho. |
| `spotifyClientId` | vazio | Liga a busca por nome na aba Spotify: o Client ID de um app do Spotify (ver "Busca do Spotify"). Vazio = a aba aceita só links. |
| `spotifyClientSecret` | vazio | O Client Secret do mesmo app. Fica em **texto puro** no config: não compartilhe o arquivo. Nunca vai para o log nem para os jogadores. |

O link do iPod (item e bloco) passa por uma lista própria (SoundCloud, YouTube e Spotify); a `policy.allowedHosts` vale para as rádios, não para ele. O áudio vem do CDN do SoundCloud, com as mesmas regras fixas da `policy` (nunca endereço interno; só as portas 80 e 443).

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
| `/fm list portables [página]` | Portáteis tocando agora (rádios e iPods): portador, URL (no iPod, a faixa), transporte, fone e quantos ouvem |
| `/fm info [x y z]` | Detalhes da rádio ou do transmissor. Sem coordenadas, o bloco que você olha (até 8 blocos). |
| `/fm stop [x y z]` | Para a rádio ou tira o transmissor do ar |
| `/fm stopall` | Para todas as rádios e transmissores carregados e desliga os portáteis e os iPods de quem está online |
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
2026-10-02T17:07:30Z Fulano (0b7c…-uuid) ipod.add: https://soundcloud.com/artista/sets/lista
```

**O que entra:**

| Origem | Eventos |
|---|---|
| Jogadores | Trocas de URL (`radio.url`, `transmitter.url`, `portable.url`), de frequência do transmissor (`transmitter.frequency`) e cada link ou busca adicionado ao iPod (`ipod.add`) |
| `/fm` | Toda ação que muda algo: `admin.stop`, `admin.stopall`, `admin.reload`, `admin.purge`, `admin.block`, `admin.unblock` |

**Formato:**
- Texto vindo de jogador é limpo: quebra de linha e caractere de controle viram espaço, e cada campo tem no máximo 512 caracteres. Ninguém forja uma linha falsa.
- O arquivo gira em 10 MB e guarda o anterior como `akashicfm-audit.log.1`.
- Sem permissão de escrita em `logs/`, o mod avisa uma vez e segue registrando só no log do servidor.

## Bloqueio de jogador

`/fm block <jogador>` vale para quem está online e para quem já entrou alguma vez (cache de perfis do servidor). Fica salvo no mundo, em `data/akashicfm_moderation.dat`.

O jogador bloqueado:
- **não controla** rádio, transmissor, portátil nem iPod: a tela abre só para olhar e as ações recebem o aviso "Um admin bloqueou você de usar rádios neste servidor.";
- **tem as rádios e os transmissores carregados parados** na hora;
- **tem os transmissores tirados do ar para as rádios sintonizadas**, inclusive os de chunk descarregado (ficam marcados como "dono bloqueado" no `/fm list transmitters`);
- **fica com o portátil e o iPod mudos**, para ele e para quem está perto (o iPod nem procura as músicas).

`/fm unblock` devolve o controle. O que foi parado continua parado até o dono ligar de novo.

## iPod (opcional)

O iPod toca uma fila de músicas de qualquer slot do inventário, como o rádio portátil (quem está perto ouve; com fone, só o dono):
- **SoundCloud** (faixas, sets, perfis) toca direto;
- **YouTube** (vídeos e playlists) e **Spotify** (faixa, álbum, playlist) tocam a mesma música achada no SoundCloud, conferida pela duração, pelo título e pelo artista, sem outras versões (remix, cover, ao vivo, acelerada);
- texto livre vira uma busca no SoundCloud;
- nas abas **SoundCloud**, **YouTube** e **Spotify** da tela, o jogador busca pelo nome e escolhe numa lista (ver "Busca e abas").

O servidor resolve cada faixa com o **yt-dlp** (um programa à parte, que o mod baixa e atualiza) e retransmite pelo relay: precisa de `relay.enabled=true`, e cada iPod tocando ocupa uma estação de `relay.maxStations`. O [iPod Player](#ipod-player-bloco), o bloco, usa o mesmo serviço e as mesmas opções.

**Termos de uso:** SoundCloud, YouTube e Spotify não permitem esse uso nos termos deles. O recurso vem desligado, e quem liga responde por isso. Faixas com DRM (comuns em gravadoras) são puladas, nunca contornadas.

### Ligar num servidor Pterodactyl (sem shell)

1. Abra `config/akashicfm.cfg` no **Gerenciador de arquivos** do painel.
2. Na seção `ipod`, troque `B:enabled=false` por `B:enabled=true` e salve.
3. Rode `/fm reload` no console (ou reinicie o servidor). O mod baixa o yt-dlp para `akashicfm/tools/` sozinho, e o log mostra `iPod: yt-dlp pronto`.
4. **Folga de memória:** o yt-dlp roda fora do Java. Deixe pelo menos **300 MB** entre o `-Xmx` e o limite de memória do servidor no painel (com `maxResolves=2`). Sem essa folga, o painel pode fechar o servidor por falta de memória.
5. **Sem saída para o GitHub** (alguns hosts bloqueiam): baixe o `yt-dlp_linux` do [release oficial](https://github.com/yt-dlp/yt-dlp/releases/latest), envie pelo gerenciador de arquivos, aponte `ytDlpPath` para ele e deixe `autoInstallTools=false`.

O mod também guarda os temporários do yt-dlp em `akashicfm/tools/tmp` (no Pterodactyl, o `/tmp` costuma ser pequeno).

### Por que o espelho no SoundCloud

Em hospedagem de datacenter (o caso de quase todo Pterodactyl), o YouTube recusa o download do áudio, mas entrega título e duração. Por isso o YouTube toca pelo espelho. Num servidor caseiro, `youtubeDirect=true` tenta o YouTube primeiro; cada resolução direta usa até ~450 MB de memória fora do Java.

O Spotify não entrega áudio nenhum: o mod lê título, artistas e duração da página pública do player embutido (sem chave) e procura a música no SoundCloud.

### O que esperar

- **Primeira faixa:** de 5 a 15 s até tocar (o yt-dlp resolve; com espelho, também busca). As seguintes são preparadas no último minuto da anterior.
- **Músicas de gravadora** no Spotify e no YouTube muitas vezes só existem com DRM no SoundCloud: o iPod pula ("Protegida no SoundCloud") ou não acha ("Não achada no SoundCloud"). Música independente, remixes e uploads de fãs funcionam bem.
- **Falhas seguidas:** uma faixa que falha é pulada com o motivo na tela; cinco seguidas (ou a fila inteira, se for menor) param o iPod.
- **Pausa:** o servidor para de baixar e retoma do ponto exato; depois de `pauseTimeoutMinutes`, o iPod para.
- **Auditoria:** cada link ou busca vai para o log (`ipod.add`), e cada busca das abas também (`ipod.search`).

### Busca e abas

A tela do iPod (item e bloco) tem as abas **Fila**, **SoundCloud**, **YouTube** e **Spotify**. Numa aba de serviço, o jogador digita o nome da música e vê até 10 resultados, com artista e duração: um clique põe na fila (e começa, se estava parado), e "Tocar agora" toca o escolhido logo depois da atual. Um link colado em qualquer aba entra na fila direto.

| Aba | Como busca | Como toca |
|---|---|---|
| SoundCloud | O yt-dlp (`scsearch`), sem as prévias de 30 s das faixas pagas | Direto |
| YouTube | O yt-dlp (`ytsearch`); num IP de datacenter, a busca funciona, só o áudio é recusado | Pelo espelho no SoundCloud |
| Spotify | A API oficial, **só com a chave** (abaixo); sem ela, a aba aceita links | Pelo espelho no SoundCloud |

Faixas mais longas que `maxTrackMinutes` ficam fora da lista.

**Segurança e custo:**
- o cliente manda só o texto e, depois, o número do resultado escolhido: os links e os títulos ficam no servidor por 10 minutos, então ninguém põe na fila um link que a busca não devolveu;
- uma busca por vez por jogador, com 2 s entre buscas, e no máximo 2 buscas ao mesmo tempo no servidor, que dividem os processos do yt-dlp (`maxResolves`) com as faixas tocando;
- a busca do YouTube é montada no servidor, depois de conferir que o texto não é um link.

### Busca do Spotify (opcional)

Sem chave, a aba Spotify aceita links (faixa, álbum, playlist) e explica que a busca por nome precisa de uma chave no servidor. Para ligar a busca:

1. Entre no [painel de desenvolvedor do Spotify](https://developer.spotify.com/dashboard) com uma conta do Spotify. **Desde fevereiro de 2026, o dono do app precisa ter Premium** (sem Premium, o Spotify recusa a chave), e cada busca devolve no máximo 10 resultados.
2. Crie um app (**Create app**): nome e descrição quaisquer, marque **Web API** e ponha uma Redirect URI qualquer (o mod não a usa; por exemplo `http://127.0.0.1:8888/callback`).
3. Nas configurações do app, copie o **Client ID** e o **Client Secret**.
4. Em `config/akashicfm.cfg`, na seção `ipod`, preencha `S:spotifyClientId=` e `S:spotifyClientSecret=`, salve e rode `/fm reload`. A aba Spotify de quem abrir a tela passa a buscar.

**Proteja o segredo:**
- ele fica em **texto puro** no config. Num servidor com shell, deixe o arquivo só para o usuário do servidor (`chmod 600 config/akashicfm.cfg`; o Forge regrava o arquivo no lugar, então a permissão fica);
- não mande o `akashicfm.cfg` em pedidos de suporte nem o inclua num modpack;
- se vazar, gere outro no painel do Spotify (**Rotate client secret**), troque no config e rode `/fm reload`.

O mod nunca escreve o segredo nem o token no log, nas mensagens ou nos pacotes. Ele pede o token só a `accounts.spotify.com` e busca só em `api.spotify.com`, sem seguir redirecionamentos, e guarda o token até expirar (trocar a chave descarta o antigo). Se o Spotify limitar as buscas (HTTP 429), as próximas esperam o tempo que ele pedir, e a tela avisa que o servidor está ocupado. Com `spotify=false`, a aba recusa links e busca.

## iPod Player (bloco)

O iPod Player é um bloco que toca uma fila como o iPod: a mesma tela (abas, busca, fila e controles), as mesmas regras de links e o mesmo yt-dlp. Precisa de `ipod.enabled=true` e de `relay.enabled=true`; com o iPod desligado no servidor, o bloco fica mudo e mostra o motivo. Receita: o iPod numa base de tábuas, com uma jukebox e redstone.

Por dentro é uma rádio, então vale tudo o que vale para as rádios:
- **dono e acesso:** privado, em que só o dono e os ops mexem, ou público, em que todos mexem na fila, no transporte, no volume e no alcance. Acesso, redstone, texto e cor da tela e caixas são sempre só do dono e dos ops. As ações da tela valem a até 8 blocos do iPod Player;
- **limites:** conta como rádio em `limits` (por jogador e por chunk) e liga até `maxSpeakersPerRadio` caixas pelo sintonizador, de chão ou de teto/parede;
- **relay:** cada iPod Player tocando ocupa uma estação de `relay.maxStations`; ouve quem está no alcance do bloco ou de uma das caixas;
- **proteção:** com `protectPrivateBlocks`, só o dono e os ops quebram um iPod Player privado; máquinas são recusadas;
- **redstone:** tocar enquanto ligada (desligar a redstone para a fila) ou alternar no pulso; uma fila vazia nunca liga;
- **comandos:** `/fm list radios` e `/fm info` mostram "iPod: faixa (N na fila)"; `/fm stop`, `/fm stopall` e o bloqueio de jogador param o bloco como uma rádio, e ele fica parado até alguém ligar de novo;
- **quebrar** devolve o item com a fila e as configurações, desligado.

**Ninguém por perto:** se ninguém está no alcance (com 4 blocos de folga) do bloco nem das caixas, ele para de baixar e fecha a estação depois de 30 s, e a tela mostra "Ninguém por perto". Quando alguém chega, a faixa atual recomeça. Um bloco num chunk carregado sem ninguém por perto (um chunk loader, a volta do servidor) não abre estação nem chama o yt-dlp.

**OpenComputers:** o componente `openfm_radio` não aparece no iPod Player (o Adaptador ignora o bloco). Os scripts controlam só as rádios.

## Alto-falante de teto e de parede

Uma caixa de som fina que vai **embaixo de um bloco** (teto) ou **na lateral** (parede); em cima de bloco não vai. Receita: uma caixa de som entre duas lajes de madeira dá dois alto-falantes.

- **Liga como a caixa de chão:** com o sintonizador, numa rádio ou num iPod Player; agachado + clique troca o canal (mono, esquerdo, direito ou estéreo). Conta como caixa em `maxSpeakersPerRadio` e `maxSpeakerDistance`.
- **Estéreo:** na parede, a frente é o lado para onde a placa olha; no teto, a direção de quem colocou.
- **Apoio:** precisa de uma face sólida atrás (em cima, no teto). Se o apoio some (quebrado, empurrado por pistão, explodido), o alto-falante cai como item e se desliga da rádio.
- **Proteção do apoio:** com `protectPrivateBlocks`, quebrar o bloco que segura o alto-falante de outro jogador é cancelado ("Este bloco segura o alto-falante de outro jogador."). O dono e os ops podem; máquinas (mineradores do GregTech e afins) são recusadas sem erro. Explosões e pistões não passam pelo evento de quebra do Forge: neles o alto-falante cai, como item.
- **Custo:** a conferência roda em toda quebra de bloco, mas só lê um vizinho que já seja um alto-falante de teto/parede, e nunca carrega chunk.

## OpenComputers (opcional)

Com o OpenComputers instalado, o computador alcança a rádio e o transmissor por um **Adaptador** encostado no bloco. O iPod Player fica de fora: a fila dele é só pela tela.

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
| "O iPod está desligado neste servidor" | `ipod.enabled` |
| "O iPod precisa do relay ligado no servidor" | `relay.enabled` |
| "O servidor ainda está instalando o yt-dlp" por muito tempo, ou "O yt-dlp não está disponível no servidor" | O log mostra o motivo (`iPod: ferramentas indisponíveis`). Sem saída para o GitHub, instale à mão (passo 5 do iPod) |
| "Não achada no SoundCloud" ou "Protegida no SoundCloud (DRM)" | Esperado com músicas de gravadora (ver "O que esperar") |
| O painel fecha o servidor por memória quando alguém usa o iPod | Folga fora do `-Xmx` (passo 4 do iPod) ou `ipod.maxResolves=1` |
| "A busca do Spotify precisa de uma chave da API no servidor" | `ipod.spotifyClientId` e `ipod.spotifyClientSecret` (ver "Busca do Spotify"); sem chave, links do Spotify funcionam |
| "O Spotify recusou a chave do servidor (confira o config)" | Client ID ou Secret errados, segredo trocado no painel do Spotify, ou o dono do app sem Premium |
| A busca do YouTube dá "Falhou: …" | Num IP de datacenter, o YouTube pode recusar até a busca (nos testes, num IP de nuvem, ela funcionou); a aba SoundCloud continua |
| iPod Player mostra "Ninguém por perto" | Esperado: sem ninguém no alcance do bloco nem das caixas, ele para de baixar e volta quando alguém chega |
| Não dá para colocar o alto-falante de teto/parede | Precisa de uma face sólida atrás (embaixo de um bloco ou na lateral); em cima de bloco não vai |
| "Este bloco segura o alto-falante de outro jogador." | Proteção do apoio (`protectPrivateBlocks`): o dono tira o alto-falante antes, ou um op quebra |
