# Fase 7: resultados

A Fase 7 fecha o projeto em três partes:
- **7a:** ferramentas de admin (`/fm`), log de auditoria e bloqueio de jogadores;
- **7b:** mute no cliente e playlist;
- **7c:** OpenComputers opcional, revisão final e release 1.0.0.

## 7a: admin, auditoria e bloqueio

### Como funciona
- **`/fm`** (`FmCommand`, op nível 2): `list`, `info`, `stop`, `stopall`, `reload`, `purge`, `block`, `unblock` e `blocked`. Referência completa no [guia do admin](ADMIN.md).
  - **Nunca carrega chunk:**
    - transmissores aparecem pelo índice do mundo;
    - `info`/`stop` recusam chunk descarregado (`blockExists`);
    - `stopall`, `purge` e o bloqueio só tocam o que está carregado (`chunkExists`).
  - **Coordenadas:** o parser do vanilla soma 0,5 à coordenada inteira (centro do bloco). O comando usa `floor`, nunca `(int)`, que erraria o bloco em coordenada negativa.
  - **Sem coordenadas:** `info` e `stop` usam o bloco que o admin olha (ray trace no servidor, até 8 blocos).
  - **Erros:** saem em vermelho com o motivo (`CommandException`), sem o "Uso:" do `WrongUsageException`.
  - **Uma só parada:** `stop`, `stopall` e o bloqueio param a rádio pelo mesmo `applyStop` do botão "Parar". Status, transmissor sintonizado e título são apagados, sem estado parcial.
- **Thread principal sempre.** Duas fontes de comando rodam fora da thread principal:
  - **RCON:** no 1.7.10 puro, `MinecraftServer.handleRConCommand` chama o `CommandHandler` na thread do RCON. O Hodgepodge corrige isso (`fixRconThreading`, via `ServerThreadUtil` do GTNHLib), mas é opcional e pode estar desligado;
  - **pontes de chat** (Discord e afins) que chamam `executeCommand` da própria thread: nenhum mod corrige.
  
  O caso:
  - **Risco:** um `/fm stopall` assim mexeria em TE, índice e log ao mesmo tempo que o tick, com risco de `ConcurrentModificationException`.
  - **Correção:** vindo de outra thread, o `/fm` vai para uma fila própria, rodada no começo do próximo tick, e quem chamou espera o resultado (até 10 s). As respostas voltam normalmente.
  - **Por que não a fila do GTNHLib:** ela depende de um mixin. Se ele não aplicar, a fila nunca roda e todo comando de fora ficaria esperando; a nossa marca a thread pelos próprios eventos do mod.
  - Comando de jogador e console já rodam na thread principal: o pacote de chat do 1.7.10 não tem prioridade, então é processado no tick.
- **`/fm reload`** (`ConfigReload`): relê do disco as sete categorias do servidor.
  - **Marca obrigatória:** o GTNHLib 0.11.52 só recarrega campos marcados com `@Config.Reloadable`; sem a marca, `reloadConfig` não faz nada e não avisa. Todo campo do servidor tem `@Config.Reloadable(FmConfig.RELOAD)`. O `ConfigReloadTest` falha se um campo novo esquecer dela.
  - **Exceção:** `relay.latencyTargetMs` só muda ao reiniciar. Os ouvintes conectados guardam a latência que receberam ao entrar; mudar no servidor faria ele descartar quadros que eles ainda esperam.
  - **Energia ao vivo:** capacidade e entrada máxima do transmissor passaram a valer na hora (`EnergyBuffer.configure` a cada tick; antes ficavam fixas na criação do TE).
  - **Depois do reload:**
    - o relay desligado encerra as estações no tick seguinte;
    - a allowlist nova para as rádios com URL que deixou de valer (manutenção das caixas);
    - o resto é lido a cada uso.
- **Bloqueio** (`Moderation`, WorldSavedData global `akashicfm_moderation`, até 10 000 entradas):
  - **Permissões:** um jogador bloqueado tem controle e admin falsos em rádio e transmissor. Recebe o aviso `blocked` em vez de `no_permission`, e o portátil recusa as ações dele.
  - **Transmissores:** os dele saem das candidatas da sintonia (`FrequencyService.eligible`), inclusive os de chunk descarregado. O `/fm list transmitters` mostra "dono bloqueado".
  - **Portátil:** o dele fica mudo (`PortableSources.sourceOf`).
  - **Na hora do bloqueio:** param as rádios e os transmissores carregados que são dele.
  - **Quem pode ser bloqueado:** quem está online ou quem já entrou um dia (cache de perfis do servidor).
- **Auditoria** (`AuditLog`): `logs/akashicfm-audit.log`, uma linha por evento, em UTC, e o mesmo texto no log do servidor (`[audit]`).
  - **Eventos:** trocas de URL (rádio, transmissor, portátil), frequência do transmissor e toda ação de admin que muda algo.
  - **Texto de jogador:** caracteres de controle e U+2028/2029 viram espaço, e cada campo para em 512. Ninguém forja uma linha.
  - **Arquivo:** gira em 10 MB, guardando um anterior. Sem permissão de escrita, avisa uma vez e segue só no log do servidor.

### Verificação

**Testes unitários** (269 no total; 8 novos nesta parte):
- **`AdminLogicTest`:**
  - linha de auditoria em UTC;
  - injeção de quebra de linha e U+2028 limpa, campo cortado em 512, console sem UUID;
  - bloqueados persistem no NBT (ida e volta, entrada sem UUID ignorada, `byName` sem diferenciar maiúsculas);
  - transmissor de dono bloqueado não conta, e o chunk só é consultado quando precisa;
  - coordenada negativa no bloco certo.
- **`ConfigReloadTest`:** todo campo das categorias do servidor é recarregável (menos a latência), e a lista de categorias cobre todas as do servidor.
- **`EnergyBufferTest`:** capacidade e entrada novas valem na hora; o excesso some, e com uma capacidade maior o que estava guardado fica.

**E2E** (passos novos no cenário principal, mais o segundo jogador):

| Passo | Resultado (Java 21) |
|---|---|
| `/fm list transmitters` / `radios` | lista a torre longe (índice, chunk descarregado) e a rádio |
| `/fm list transmitters` pelo RCON (`handleRConCommand`) | a resposta volta ao RCON (no dev, o Hodgepodge já entrega na thread principal: fila +0) |
| `/fm list transmitters` por uma ponte de chat (`executeCommand` de outra thread) | passa pela fila da thread principal (+1) e a resposta volta à ponte |
| `/fm info` olhando a rádio | detalhes da rádio |
| `/fm reload` | edita `maxPerPlayer` no arquivo, recarrega, o valor novo entra e os valores do roteiro continuam; volta o arquivo e confere de novo |
| `/fm stop x y z` no transmissor | sai do ar |
| `/fm block Player2` | o segundo jogador tenta mudar o volume: recusado com `blocked`, volume intacto |
| `/fm unblock Player2` | o volume volta a obedecer |
| Arquivo de auditoria | tem `radio.url`, `admin.stop`, `admin.reload: ok`, `admin.block` e `admin.unblock` |
| `/fm purge player Developer` | o roteiro dá ao jogador um transmissor real e carregado e duas entradas fantasmas em chunk descarregado (os blocos do roteiro vêm de `/setblock`, sem dono). O purge tira as duas fantasmas e o transmissor fica: índice do jogador 3 (1 carregada) → 1 (1 carregada) |
| `/fm stopall` | para tudo e o cliente fica sem nenhuma voz |

**Matriz de regressão:**

| Rodada | Resultado |
|---|---|
| Java 21, relay | **112/112 + 22/22** |
| Java 8, relay | **112/112 + 22/22** |
| Java 21, modo direto | **109/109 + 20/20** |
| Java 21, sem EFX | **112/112 + 22/22** |
| Soak de 10 min | OK: 36 amostras, **0 violações**, 19 trocas de caixa, 4 recarregamentos do som, 0 underruns, heap 147 → 145 MB |
| Prova acústica, com EFX | OK: lã −9,9 dB no nível e −21,2 dB nos agudos; vidro −1,0 e −0,9 dB; cauda na sala de pedra −36,6 dB; aberto sem cauda (−70,6 dB) |
| Prova acústica, sem EFX | OK na segunda rodada: lã −12,7 dB no nível e +0,3 dB nos agudos; sem cauda (−66,1 dB). Ver abaixo |

**A primeira rodada da prova sem EFX falhou** ("esperava 7 trechos, achei 8"), com os 11 passos do jogo passando.
- **O que aconteceu:** o cliente parou cerca de 4 s no trecho D. Os mesmos 161 ticks levaram 12 s de relógio em vez de 8. O WAV tem 3,75 s de silêncio digital bem nessa janela.
- **Não foi o servidor:** ele não registrou atraso ("Can't keep up" não aparece). Nem o código da 7a: o cenário acústico não passa por nada que mudou.
- **A segunda rodada passou**, com o trecho D nos 6 s normais.
- **O que isso mostrou:** a engine enche o OpenAL pela thread do cliente com uma fila de cerca de 341 ms, então um engasgo do cliente maior que isso corta o som. Em pack pesado, engasgos de 0,5 a 1 s são comuns. Vira item da 7b: fila mais funda e um E2E que simula o engasgo.

### Revisão adversarial (corrigido antes do commit)
- **`/fm reload` não recarregava nada.** Achado lendo o bytecode do GTNHLib: `reloadConfig(classe, grupo)` procura os campos marcados com `@Config.Reloadable(grupo)` e, sem nenhum, retorna em silêncio.
  - **Correção:** a marca em todos os campos do servidor e o teste que impede esquecer.
  - **Prova:** o E2E edita o arquivo de verdade e confere o valor.
- **`/fm` fora da thread principal:** ver acima. O E2E chama o `handleRConCommand` do RCON e o `executeCommand` de uma ponte, cada um numa thread separada.
  - O primeiro E2E esperava que o RCON passasse pela fila e falhou: o Hodgepodge do ambiente de dev já resolve o RCON.
  - A ponte de chat é o caso que nada cobre, e é ela que prova a fila.
- **`/fm purge player` derrubava transmissores reais.** Ele tirava do índice até as entradas de blocos carregados. O transmissor só regrava a entrada quando algo muda nele, então ficava fora do ar sem aviso.
  - **Correção:** só saem as entradas que não dá para confirmar (sem bloco, ou em chunk descarregado). Os blocos que existirem voltam ao índice ao carregar: a rádio no `validate`, o transmissor no primeiro tick, que recalcula o alcance.
- **Parada do admin incompleta:** o `/fm stop` deixava o transmissor sintonizado e o título na rádio parada. Agora usa o mesmo `applyStop` do jogador.
- **`/fm reload` no E2E:** o roteiro força alguns valores na memória (transporte, alcance curto), e o reload os desfaria. `ConfigReload` os reaplica no ambiente de teste (`E2EServer.applyOverrides`).

### Harness E2E
- **Comandos novos no servidor de teste** (`E2EServer`):
  - `e2e:rcon` e `e2e:bridge`: um comando de outra thread, pelo RCON ou como uma ponte de chat;
  - `e2e:config-get` e `e2e:config-file`: lê os valores e edita o arquivo de config;
  - `e2e:index-seed` e `e2e:index-owned`: semeia e conta as entradas do índice do jogador;
  - `e2e:audit-tail`: confere o arquivo de auditoria, agora também com o reload.
- **Abortar uma rodada:** matar também o `run-all.sh`, não só os processos Java. Numa rodada abortada, o laço de espera de 900 s do script continuou vivo e derrubou o servidor da rodada seguinte ("Connection refused" no passo de reconectar). Não era regressão: a rodada limpa passou.

## 7b: playlist, mute e robustez do áudio

### Como funciona
- **Playlist** (`common/Playlist`, `server/relay/PlaylistService`):
  - **Estado:** `RadioState.playlist`, salvo no NBT e nas configurações do item; NBT antigo = desligada.
  - **Controle:** ação `SET_PLAYLIST` (no fim do enum, permissão de controle) e botão "Playlist" na tela da rádio, verde quando ligada, com dica.
  - **Quando avança:** a cada atualização de status do relay (10 ticks), a rádio em modo URL, tocando, com playlist e favoritas, olha a estação dela:
    - **terminou com áudio** (arquivo): espera o fim **soar** nos clientes e passa para a próxima favorita, em loop;
    - **falhou**, ou terminou sem nenhum áudio (URL que responde vazio): conta uma falha e passa adiante.
  - **Esperar o fim soar:** a estação marca "terminou" assim que acaba o download, mas o áudio segue ~3,5 s adiante (2 s de PTS à frente do relógio mais 1,5 s de latência). A troca espera `FrameRing.endPtsMs()` + latência + 250 ms. Trocar no "terminou" cortaria os últimos segundos de cada faixa.
    - Enquanto o fim ainda soa, o status "terminou" fica escondido, porque a próxima já vem.
  - **Proteções:**
    - no máximo uma troca a cada 5 s por rádio;
    - favorita que a política recusa (allowlist mudou) é pulada;
    - depois de tantas falhas seguidas quanto há favoritas, a rádio para e mostra o motivo da última falha.
  - **Troca:** sessão nova (os clientes recomeçam), e nova conexão se a estação da próxima já existe e terminou (loop de uma faixa só). A estação anterior é fechada na hora se ninguém mais a toca (`RelayService.releaseIfUnused`): a vaga do limite de estações volta sem esperar os 10 s de expiração.
  - **Modo direto:** não avança, porque o servidor não sabe quando o arquivo termina.
- **Mute no cliente** (`client/MuteKeys`, `client/ClientMutes`): duas teclas em Controles → AkashicFM, **sem tecla padrão** (a GTNH já usa quase todas).
  - **"Silenciar todas as rádios":** alterna `FmConfig.Client.enableAudio` e salva o config. Confirmação acima da barra de itens.
  - **"Silenciar a rádio que estou olhando":** mira até 32 blocos (o raio do vanilla para blocos e um teste de caixa para jogadores na frente). O que conta:
    - rádio, pela posição;
    - caixa: a rádio dela;
    - transmissor: a estação dele, pela URL; as rádios e os portáteis sintonizados nela ficam mudos;
    - outro jogador: o portátil dele, pelo UUID, porque o id da entidade muda ao trocar de dimensão.
    
    Vale só para você e só na sessão (limpo ao desconectar). O controlador pula as fontes silenciadas e mantém as outras do mesmo grupo do relay. O seu próprio portátil nunca é silenciado por URL: foi você que ligou.
  - **Onde aparece:** status "silenciada para você" na tela da rádio; linhas no WAILA da rádio, da caixa e do transmissor ("Silenciada para você", "Estação silenciada para você").
- **Quem não ouve não recebe** (`network/C2SListening`, `client/ListeningReporter`):
  - **O que conta como "não ouvindo":** áudio do mod desligado ou um volume que as rádios usam em zero (geral, Jukebox/Discos ou o do mod).
  - **O que o cliente faz:** avisa o servidor ao entrar e a cada mudança.
  - **O que o servidor faz:** tira o jogador da audiência do relay como quem está longe; para de mandar áudio e o cliente para de decodificar. Antes, um jogador com o áudio desligado continuava custando ~8,5 KB/s de upload e o decoder Opus por estação.
- **Fila do OpenAL de ~1 s** (`Playback.TARGET_CHUNKS` 8 → 24; `Voice.POOL_SIZE` = fila + 2):
  - **O problema:** quem enche a fila de cada fonte é a thread do cliente, a cada frame. Um engasgo do jogo maior que a fila (chunks carregando, GC, autosave do singleplayer) cortava o som; com 341 ms, isso é comum num pack pesado.
  - **O que não muda:** volume, posição, oclusão e parada continuam imediatos (são parâmetros da fonte, não da fila). A sincronia mede a posição real tocada, então não depende da profundidade.
  - **De onde vem a folga:** a latência do relay (1,5 s) e os anéis de PCM dos feeds (6 a 8 s).
  - **Memória:** ~104 KB de buffers por voz, ~10 MB no pior caso de 96 vozes.

### Verificação

**Testes unitários** (280 no total; 11 novos nesta parte):
- `PlaylistTest`: próxima em loop, URL fora da lista e lista vazia; espera o fim soar; tocando não troca; intervalo mínimo; todas falhando param; fim normal não conta como falha.
- `ClientMutesTest`: rádio por posição e dimensão; estação por URL; portátil pelo UUID, reconhecido de novo com outro id de entidade; tudo limpo ao desconectar.
- `RadioStateTest`: playlist no NBT, no item e desligada no NBT antigo.
- `FrameRingTest`: fim do áudio = fim do último frame; sem nada, −1.

**E2E** (passos novos):

| Passo | Resultado (Java 21) |
|---|---|
| Engasgo de 700 ms com a thread do cliente congelada | **antes da mudança: underrun (0 → 1)**; depois: sem underrun e sem ressincronizar |
| Playlist com duas faixas públicas curtas (OGG Opus de 6,0 s e 3,0 s) | a primeira toca inteira (5,97 s entregues ao OpenAL) e a troca vem **6,45 s** depois de ela começar a soar; sessão nova |
| Segunda faixa | toca no principal e **também no segundo jogador** (mesma estação do relay) |
| Volta para a primeira | loop com sessão nova e estação reaberta |
| Tela e WAILA com a playlist | botão verde, faixa atual marcada, WAILA "Playlist: 2 favoritas" (captura `e2e-gui-playlist.png`) |
| Silenciar todas as rádios | nenhuma reprodução nem thread de áudio; o servidor tira o jogador da audiência (**0 bytes em 2 s**); config salvo com `enableAudio=false` |
| Desfazer | o som volta e o config é salvo com `true` |
| Segunda rádio tocando outra estação; silenciar a rádio olhada | **só a olhada some**, a segunda continua; WAILA "Muted for you"; tela "silenciada para você" (captura `e2e-gui-silenciada.png`) |
| Silenciar o transmissor olhado | as rádios na estação dele somem (a segunda rádio), a outra continua; WAILA do transmissor "Station muted for you" |
| Desfazer e limpar | tudo com som de novo, nada silenciado |

**Sincronia:** −0,9 ms entre os clientes com a fila nova (antes, −1,7 ms).

**Matriz de regressão:**

| Rodada | Resultado |
|---|---|
| Java 21, relay | **129/129 + 23/23** |
| Java 8, relay | **129/129 + 23/23** |
| Java 21, modo direto | **117/117 + 20/20** (sem playlist, que é só do relay, e sem o teste de engasgo) |
| Java 21, sem EFX | **129/129 + 23/23** |
| Soak de 10 min | OK: 36 amostras, **0 violações**, 19 trocas de caixa, 4 recarregamentos do som, 0 underruns, heap 145 → 145 MB (buffers = vozes × 26) |
| Prova acústica, com EFX | OK: lã −9,2 dB no nível e −25,7 dB nos agudos; vidro −1,0 e −0,8 dB; cauda na sala de pedra −21,5 dB; aberto sem cauda (−70,9 dB) |
| Prova acústica, sem EFX | OK: lã −13,0 dB no nível e −0,1 dB nos agudos; sem cauda (−65,7 dB) |

### Revisão adversarial (corrigido antes do commit)
- **O fim de cada faixa seria cortado:** o "terminou" do servidor chega ~3,5 s antes do fim soar. A playlist espera o fim real.
- **As falhas seguidas seriam esquecidas na troca:** logo depois de trocar, a estação nova só abre na próxima atualização da audiência. Sem marcar a rádio como vista nesse ciclo, o contador de falhas zerava e uma lista de URLs quebradas giraria para sempre, de 5 em 5 s.
- **Estação terminada segurando a vaga:** a estação que acabou ficava aberta até expirar. No limite de estações, a próxima faixa não abria e a manutenção poderia parar a rádio. Agora é liberada na troca, se ninguém mais a toca.
- **Banda gasta com quem silenciou:** ver "quem não ouve não recebe".
- **Som cortando em engasgos de 0,5 a 1 s:** ver a fila de ~1 s. Achado na matriz da 7a: o cliente parou 4 s na prova acústica.
- **Custo por tick:** a checagem de silenciada montava uma chave de texto por rádio a cada tick mesmo sem nada silenciado; agora sai na hora com os conjuntos vazios.
- **Texto cortado:** o status "silenciada para você" passava da largura da tela (captura); encurtado.
- **E2E:** as coordenadas da segunda rádio eram calculadas na montagem do roteiro, antes de a rádio ter posição ("Cannot place block outside of the world"); agora no início do passo.

## 7c: OpenComputers (opcional)

### Como funciona
- **Driver, não `SimpleComponent`.** O `SimpleComponent` faria o OC injetar métodos no `TileRadio` por class transformer, e a rádio já sobrescreve `validate`, `invalidate` e NBT.
  - **Como ficou:** os drivers (`compat/oc`: `DriverSidedTileEntity` + `prefab.ManagedEnvironment`) são registrados no init só com o OC instalado. Nenhuma classe do OC carrega sem ele, e rádio e transmissor ficam intocados.
  - **Adaptador:** o computador alcança o bloco por um Adaptador encostado nele. No OpenFM era cabo direto; os scripts são os mesmos.
- **Componente `openfm_radio`:** o nome e os métodos do OpenFM 1.7.10, conferidos na fonte dele.
  - **Volume:** lê de 0 a 1 e escreve de 0 a 10. `setVol(10)` funciona: o OpenFM o recusava por um erro de arredondamento.
  - **Passos:** `volUp`/`volDown` devolvem `false` fora da faixa.
  - **Redstone:** `setListenRedstone` = tocar enquanto ligada.
  - **Erros:** `false, "motivo"`, como no OpenFM.
  - **Novos:** URL, modo URL/FM, frequência, "tocando agora", sinal e playlist.
- **Componente `akashicfm_transmitter`:** no ar, URL, frequência, nome, alcance e energia.
- **Lógica sem o OC:** `server/RadioScripting`, testável sem o mod. Reaproveita o caminho do jogador: `applyPlay`/`applyStop`/`setMode` e o novo `RadioActionHandler.applyUrl`, extraído do `setUrl` (as mesmas mutações, sem aviso nem jogador).
- **Segurança** (o OpenFM não tinha nenhuma):
  - as permissões de um jogador qualquer: controla bloco público ou sem dono; tela, redstone e nome da estação só em bloco sem dono; `opencomputers.allowPrivate` (config nova, recarregável, padrão desligado) libera tudo;
  - dono bloqueado, sempre recusado;
  - a mesma política de URL;
  - 4 mudanças por segundo por bloco;
  - trocas de URL e frequência no log de auditoria, com `oc:<endereço>` como autor.
- **Thread:** callbacks não diretos, então o OC os roda na thread principal, um por tick por computador.

### Verificação

**Testes unitários** (287 no total; 7 novos): `RadioScriptingTest`:
- permissões: pública de alguém controla mas não mexe na tela nem na redstone; privada recusada; `allowPrivate`; dono bloqueado;
- escala de volume do OpenFM nos dois extremos;
- URL pela política (com bytes do Lua) e na auditoria só quando muda;
- tocar e parar;
- limite por segundo, por bloco;
- redstone, tela, modo e frequência;
- transmissor.

**E2E** com o OpenComputers no ambiente de dev (`devOnlyNonPublishable`, fora do jar). O roteiro acha o driver do bloco como um Adaptador acharia (`Driver.driverFor`) e chama pelo próprio OC (`Component.invoke`, que confere o `@Callback` e converte os argumentos como para um programa Lua):

| Passo | Resultado (Java 21) |
|---|---|
| `greet` no componente da rádio | o componente se chama `openfm_radio` e responde a frase do OpenFM |
| `setVol(5)` | devolve `[0.5]` e a rádio vai a 50% |
| `setScreenText("OC")`, `isPlaying()` | `[true]`: a tela muda; e `[true]`: está tocando |
| `setURL("http://10.0.0.1/live")` | `[false, URL refused: internal: 10.0.0.1]`, URL intacta |
| `setVol(3)` com a rádio privada de um jogador | `[false, private block: …]`, volume intacto |
| `getFrequency()` no transmissor | componente `akashicfm_transmitter`, `[98.7]` |

**Matriz de regressão** (os 8 passos do OC entram em todas as rodadas com o OC no dev, Java 8 incluso):

| Rodada | Resultado |
|---|---|
| Java 21, relay | **137/137 + 23/23** |
| Java 8, relay | **137/137 + 23/23** |
| Java 21, modo direto | **125/125 + 20/20** |
| Java 21, sem EFX | **137/137 + 23/23** |
| Soak de 10 min | OK: 36 amostras, **0 violações**, 19 trocas de caixa, 4 recarregamentos do som, 0 underruns, heap 165 → 166 MB |
| Prova acústica, com EFX | OK: lã −10,3 dB no nível e −19,4 dB nos agudos; vidro −1,6 e −4,7 dB; cauda na sala de pedra −23,6 dB; aberto sem cauda (−70,3 dB) |
| Prova acústica, sem EFX | OK: lã −12,3 dB no nível e +1,1 dB nos agudos; sem cauda (−64,0 dB) |

### Revisão adversarial (corrigido antes do commit)
- **Toda chamada de computador falhava** (achado pelo E2E). O OC gera, no pacote dele, uma classe que chama cada callback direto. Com as classes do adaptador package-private, isso dava `IllegalAccessError` em toda chamada. Agora são públicas.
- **O componente não se chamaria `openfm_radio`** (achado pelo E2E). O Adaptador junta os drivers num componente só, com o nome do bloco ("akashicfm_radio"), e os scripts do OpenFM não o achariam. Os ambientes implementam `NamedBlock` com o nome preferido.
- **Computador com mais poder que um jogador.** Numa rádio pública de alguém, o computador mudaria a tela e a redstone, que são do dono. Agora segue as mesmas permissões.
