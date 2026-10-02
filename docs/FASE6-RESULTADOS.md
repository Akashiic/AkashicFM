# Fase 6: resultados

A Fase 6 tem duas partes. A **6a** (este documento, por enquanto) entrega as frequências: **transmissor de FM**, **antenas**, **energia opcional** (EU ou RF) e o **modo FM da rádio**. A 6b (rádio portátil e fone) vem em seguida.

## 6a: frequências, transmissor e antenas

### Como funciona
- **Frequência:** 87,5 a 108,0 MHz, guardada em décimos (875 a 1080) em `Frequency` (formatação "98.7", leitura com vírgula ou ponto, limites).
- **Transmissor** (`BlockTransmitter` + `TileTransmitter`): transmite a URL dele numa frequência, com um nome de estação.
  - **Mesma política da rádio:** a URL passa pela `UrlPolicy`/allowlist do servidor ao ser gravada e de novo a cada sintonia. Um transmissor é só outro jeito de fazer as rádios tocarem uma URL.
  - **Permissões:** as mesmas da rádio (dono, privado/público, ops), pelas sobrecargas novas de `Permissions` por (dono, acesso). Proteção contra quebra também.
  - **Redstone:** ignorar, transmitir enquanto ligado, alternar no pulso (como a rádio).
  - **Limite:** `transmitter.maxPerPlayer` (4) por jogador, contado no índice do mundo (vale para transmissores em chunks descarregados também).
  - **Item:** quebrar devolve o item com URL, frequência, nome, acesso e redstone.
- **Antenas** (`BlockAntenna`): mastro fino empilhado em cima do transmissor. A cada 20 ticks o transmissor conta as antenas logo acima dele.
  - **Alcance:** `baseRange` (64) + `rangePerAntenna` (32) × antenas, até `maxAntennas` (16) e `maxRange` (512).
  - Com uma antena na mão, o clique no transmissor coloca a antena (não abre a tela).
  - Na acústica (Fase 4) a antena conta como bloco fino, como grade e cerca.
- **Índice do mundo** (`TransmitterIndex`, WorldSavedData global): posição → frequência, alcance, URL, nome, dono, ativo.
  - O transmissor atualiza a entrada quando algo muda (só marca para salvar se mudou de fato) e a remove quando o bloco sai.
  - É por ele que as rádios acham os transmissores **sem carregar chunk nenhum**: uma torre longe, em chunk descarregado, continua sendo ouvida.
  - **Exceção:** com energia exigida, transmissor em chunk descarregado não conta. Parado no tempo, ele não consumiria energia, e transmitir de graça seria um exploit.
- **Rádio no modo FM** (`RadioState.mode` = `FREQUENCY`): a rádio toca a URL do transmissor de **sinal mais forte** que cobre o lugar, naquela frequência (`FrequencyResolver`, lógica pura).
  - **Sinal:** 1 − distância/alcance. Vence o maior.
  - **Histerese:** o transmissor atual só perde se sair da cobertura ou se outro tiver pelo menos 0,1 de sinal a mais. Assim a rádio não alterna na fronteira entre dois transmissores.
  - **Desempate determinístico:** mais perto, depois a menor posição.
  - **Sem sinal:** a rádio fica ligada, em silêncio, com o status "sem sinal", e pega sozinha quando um transmissor passa a cobrir o lugar.
- **Serviço de sintonia** (`FrequencyService`, a cada 10 ticks, antes da audiência do relay):
  - resolve as rádios ligadas no modo FM;
  - grava `tunedUrl`, `tunedName` e `signal` no estado, e o pacote do bloco leva ao cliente;
  - mudou a fonte (outra URL ou outro transporte): sessão nova, título e status da fonte anterior apagados, transporte escolhido de novo (`ServerPolicy.chooseTransport`, que respeita o limite de estações do relay);
  - o sinal só vai para a rede quando muda 5 pontos (mesma fonte), para não gerar um pacote por passo do jogador.
- **`effectiveUrl()` em todo lugar** que antes lia `state.url`:
  - relay: audiência, limite de estações e status/título;
  - cliente: controlador de áudio, chave da reprodução, "tocando agora", WAILA e tela da rádio.
  
  No modo FM, a revalidação periódica de política/transporte da manutenção das caixas fica com o serviço de sintonia (a URL é a do transmissor).
- **Mesma estação, mesma reprodução:** uma rádio sintonizada num transmissor e outra tocando a mesma URL direto entram no mesmo grupo do relay, sincronizadas.
- **Compatibilidade:** NBT antigo (sem as chaves `mode` e `frequency`) cai no modo URL. O novo modo e a frequência viajam com o item da rádio.
- **Rede:** duas ações novas no fim do enum (`SET_MODE`, `SET_FREQUENCY`). O pacote é o mesmo da rádio; o servidor despacha pelo tipo do bloco (`TransmitterActionHandler`), com a mesma ordem de checagens: chunk carregado, distância de uso, permissão e conteúdo.

### Energia opcional (EU e RF)
- **Interfaces opcionais:** `ic2.api.energy.tile.IEnergySink` (modid `IC2`) e `cofh.api.energy.IEnergyReceiver` (API `CoFHAPI|energy`) via `@Optional.Interface`.
  - Sem o mod, o FML tira a interface da classe e nada do IC2/CoFH é carregado.
  - As APIs entram só para compilar (`compileOnly`, nada embutido no jar): IC2 `2.2.654-experimental:dev` do repositório `maven_cil_li` da GTNH, que só tem o jar e por isso é exclusivo para o grupo `net.industrial-craft`, e CoFHLib pelo CurseMaven.
- **Quando exige:** `transmitter.requireEnergy` (ligado) **e** IC2 ou uma API de RF presente. Sem nenhum dos dois, nunca exige.
- **Buffer** (`EnergyBuffer`, lógica pura): em EU; RF entra a `rfPerEu` (4) RF por EU.
  - **Capacidade:** `energyCapacity` (8000).
  - **Entrada:** `maxInputPerTick` (128) por tick.
  - **Consumo:** `euPerTick` (8) enquanto transmite.
- **Tier do IC2:** o transmissor aceita qualquer tensão (tier 14, que o IC2 nunca excede). Ele não explode com cabo do GregTech de tensão alta; o que limita é a entrada por tick.
  - **Desvio do plano**, que previa recusar acima do tier 1: num servidor GTNH, recusar significaria explodir ou não conectar em quase todo cabo.
- **Sem energia:** sai do ar e só volta com 2 s de consumo guardado.
  - Com a entrada um pouco abaixo do consumo, o transmissor não liga e desliga a cada tick, porque cada troca derrubaria e religaria as rádios sintonizadas.
  - O teste com 7 EU/t de entrada para 8 de consumo mostra ciclos longos, sem energia de graça.

### Tela, visor e WAILA
- **Tela da rádio** (`GuiRadio`):
  - o botão **URL/FM** troca o modo; no FM o seletor « ‹ 98.7 MHz › » (±1,0 e ±0,1; ← e → do teclado também) ocupa o lugar do campo de URL;
  - **envio adiado:** girar o dial não manda um pacote por clique; o valor vai 300 ms depois do último clique e a tela mostra o escolhido até o servidor confirmar (`FrequencyDial`);
  - **status:** "Sinal 81% · Perto FM · tocando em sincronia", ou "98.7 FM · sem sinal".
- **Tela do transmissor** (`GuiTransmitter`): URL, frequência, nome, no ar/parar, acesso, redstone, cobertura (alcance e antenas), energia com barra (quando exigida), estado (no ar, esperando energia, fora do ar) e o título que está tocando.
- **Visor do transmissor** (TESR): "98.7 FM" em âmbar no ar, apagado fora dele.
- **Tela da rádio:** "98.7 FM ♪ título", ou "98.7 FM · sem sinal".
- **WAILA:**
  - rádio: "Sintonizada em 98.7 FM", estação, sinal e título;
  - transmissor: no ar/fora, nome, fonte, título, cobertura, energia, acesso e dono.
- **Texturas** (`tools/gen_textures.py`): transmissor (frente com o visor no mesmo lugar da tela da rádio, lados com aletas, topo com a base da antena) e antena (mastro treliçado com faixas). Novas no fim do gerador, para as texturas antigas saírem idênticas.
- **Receitas** (ore dictionary): transmissor com ferro, vidro, redstone e bloco musical; 4 antenas com grade de ferro e 2 lingotes.

### Verificação

**Testes unitários** (242 no total; 42 novos nesta parte):
- `Frequency`: limites, formatação, leitura.
- `FrequencyResolver`:
  - cobertura, o mais forte vence, histerese (fica e troca);
  - inativo, sem URL e outra frequência não contam;
  - desempate.
- `EnergyBuffer`:
  - EU, RF com simulação e arredondamento, limite por tick, capacidade, consumo, entradas inválidas;
  - entrada abaixo do consumo sem piscar e sem energia de graça;
  - reserva limitada à capacidade; parado não gasta.
- `TransmitterState` e os campos novos do `RadioState`: NBT, NBT antigo cai no modo URL, saneamento, configurações do item.
- `FrequencyLogicTest` (servidor, sem mundo):
  - ligar sintonizada sem URL;
  - sem sinal fica ligada em silêncio, sem pacote repetido;
  - troca de transmissor dá sessão nova e apaga o título; o sinal só vai para a rede em passos;
  - URL do transmissor passa pela política (e volta quando o admin libera);
  - status do relay preservado na mesma fonte e apagado na troca;
  - sem transporte;
  - trocar de modo tocando recomeça com a fonte nova, e voltar para URL sem URL para;
  - redstone liga a sintonizada;
  - transmissor só transmite com URL aceita; redstone no transmissor.
- `LangFilesTest`: toda chave de tradução literal usada no código existe (teste novo, que varre o código-fonte), mais os modos de sintonia.

**E2E** (`tools/e2e/run-all.sh`): passos novos no cenário principal, com o segundo jogador acompanhando. No servidor de teste o alcance é 16 + 16 por antena, para o teste das antenas caber perto da rádio, onde o chunk está carregado (view-distance 4).

| Passo | Resultado (Java 21) |
|---|---|
| Torre a 150 blocos, 9 antenas, 100.0 MHz | no ar, alcance 160, "energia exigida" falso (sem IC2/RF no dev) |
| Transmissor a 24 blocos, 2 antenas, 98.7 MHz | no ar, alcance 48; GUI e visor (capturas `e2e-gui-transmissor.png`, `e2e-transmissor.png`) |
| Chunk da torre descarrega ao voltar | `loaded=false` |
| Rádio em FM 98.7 | ouve o transmissor: sinal **50%** (1 − 24,5/48), mesmo áudio |
| Transmissor a 3 blocos na mesma frequência, outra URL | **assume** (sinal 81%) e toca a outra estação; tela "98.7 FM ♪ título" e GUI no FM (capturas `e2e-radio-fm.png`, `e2e-gui-fm.png`) |
| Segundo jogador | acompanha a troca: ouve a nova estação, sincronizado (de 1,6 a 6,8 ms nas rodadas) |
| Desligar o transmissor perto | a rádio volta para o outro (50%) |
| Antena colocada com a mão no transmissor | coloca e conta (não abre a tela) |
| Tirar as 2 antenas do transmissor | alcance 16 < 24: "sem sinal", rádio ligada e **silêncio** neste cliente |
| Frequência 100.0 | ouve a **torre em chunk descarregado** (sinal 6%), e o chunk **continua descarregado** |
| Voltar para URL | toca a URL própria de novo |

**Matriz de regressão:**

| Rodada | Resultado |
|---|---|
| Java 21, relay | **73/73 + 12/12** (segundo jogador sincronizado no FM: 6,8 ms) |
| Java 8, relay | **73/73 + 12/12** (1,6 ms) |
| Java 21, modo direto | **69/69 + 10/10** (antes do passo da antena, que não depende do transporte) |
| Java 21, sem EFX | **72/72 + 12/12** (idem) |
| Soak de 10 min | OK: 36 amostras, **0 violações** (objetos AL, EFX, threads), 19 trocas de caixa, 4 recarregamentos do som, 0 underruns, heap 144 → 144 MB |
| Prova acústica, com EFX | OK: lã −9,6 dB no nível e −16,5 dB nos agudos; vidro −0,2 e −0,8 dB; cauda na sala de pedra −19,5 dB; aberto sem cauda (−71,7 dB) |
| Prova acústica, sem EFX | OK: lã −13,0 dB no nível e −1,2 dB nos agudos (só ganho); sem cauda |

**Prova acústica e a música:** a primeira rodada da prova acústica falhou nos agudos (lã −9,8 dB contra o limite de −10; vidro +6,6 dB). O áudio gravado foi conferido por bandas:

| Trecho | Agudos acima de 8 kHz em relação aos graves abaixo de 1 kHz |
|---|---|
| R0, D, F (abertos) | −22,4, −23,7, −22,2 dB |
| A (aberto) | **−37,7 dB** |
| B (lã) | −53,0 dB |
| C (vidro) | −20,2 dB |

O filtro da lã estava certo: −30 dB em relação aos trechos abertos típicos. O trecho A caiu numa passagem da música quase sem agudos, e como a referência era a média de A e D, ela foi puxada para baixo.

- **Correção do analisador:** a referência dos agudos passou a ser a **mediana dos quatro trechos sem parede** (R0, A, D, F), que não se deixa levar por um trecho fora da curva. Os limites físicos ficaram os mesmos.
- **Resultado:** a mesma gravação passa com folga (lã −14,4 dB, vidro +2,0 dB). A rodada nova, do zero, também passou (números acima), assim como a sem EFX.

### Revisão adversarial (corrigido antes do commit)
- **Transmissor piscando sem energia:** com a entrada um pouco abaixo do consumo, ele ligava e desligava a cada tick, e cada troca reiniciaria as rádios sintonizadas. Agora só volta com reserva.
- **Primeiro "no ar" sem energia:** o estado inicial "com energia" deixava o transmissor ativo no índice por um tick ao começar a transmitir sem nada no buffer. Parado, agora ele só conta como "com energia" se daria para transmitir naquele instante.
- **Índice inativo a cada chunk carregado:** gravar a entrada no `validate` (antes da primeira contagem das antenas, com alcance 0) deixava o transmissor inativo por um tick sempre que o chunk carregava. O primeiro tick já grava.
- **Rádio sintonizada parada por URL vazia:** apagar o campo de URL parava a rádio mesmo no modo FM, e trocar a URL própria no FM reiniciava a reprodução do transmissor. Agora isso só vale no modo URL.
- **Favorita no FM:** clicar numa favorita no FM volta para o modo URL com sessão nova, mesmo quando o transporte é o mesmo; antes a troca de fonte passaria sem reiniciar os clientes.
- **Status preso:** o erro do relay de uma estação ficava na rádio depois de ela trocar de transmissor (no modo direto ninguém o apagaria). Agora a troca de fonte apaga.
- **Sinal desatualizado:** trocando de transmissor com sinal parecido (menos de 5 pontos), o sinal mostrado ficava o do anterior. Agora fonte nova sempre atualiza.
- **Repositório do IC2:** o Gradle perguntava ao Maven Central primeiro (429) e o repositório da GTNH não tem pom. Agora o repositório é exclusivo para o grupo e lê só o artefato.
- **Custo da sintonia:** os candidatos de cada dimensão são montados uma vez por ciclo, não uma vez por rádio.
- **Dial e modo:** trocar de modo logo depois de girar o dial mandava o modo antes da frequência. Agora a frequência pendente sai antes.
- **Linha de status no FM:** "Sinal · estação · transporte · estado" não cabia (captura da GUI). No FM o transporte sai da linha (continua no WAILA).

### Harness E2E
- **Passo da antena no Java 8:** a primeira versão escolhia o slot num tick fixo. O `/give` do 1.7.10 joga o item no chão e o jogador o pega alguns ticks depois; no Java 8, mais lento, o clique saiu com o sintonizador na mão e abriu a tela (comportamento certo do bloco). Agora o passo espera o item chegar e a troca de slot ir ao servidor.
- **Menu de pausa sozinho:** sob o Xvfb a janela pode ficar sem foco, e o jogo abre o menu de pausa (`pauseOnLostFocus`). O harness desliga essa opção ao registrar o cenário.
