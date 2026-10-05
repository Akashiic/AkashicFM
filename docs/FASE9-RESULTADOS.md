# Fase 9: resultados (busca e abas, iPod Player e alto-falante de teto/parede, 1.2.0)

A Fase 9 atende três pedidos: escolher a música numa lista de resultados, com abas por serviço; um bloco que toca uma fila como o iPod; e uma caixa de som que gruda no teto ou na parede. Em cinco partes:
- **9a-1:** o serviço do iPod passa a tocar "hosts", sem mudar o comportamento (prepara o bloco);
- **9a-2:** busca com lista de resultados e abas no iPod;
- **9b:** o iPod Player (bloco);
- **9c:** o alto-falante de teto e de parede;
- **9d:** documentação, teste de produção e release 1.2.0.

Guia do admin: [`ADMIN.md`](ADMIN.md#busca-e-abas) (busca e chave do Spotify), [iPod Player](ADMIN.md#ipod-player-bloco) e [alto-falante](ADMIN.md#alto-falante-de-teto-e-de-parede).

## 9a-1: hosts e destinos

O `IPodService` deixa de conhecer o item e passa a tocar **hosts** (`IPodHost`: chave da estação, identidade, estado, gravar, status). O item vira um `ItemHost` (o mesmo jogador, stack e NBT de antes), e as sessões ficam pela chave da estação em vez do UUID do dono. A chave do item continua sendo a do jogador, então nada muda para ele.

As ações e as adições em segundo plano ganham um **destino** (`IPodTarget`): o iPod item de um dono, achado de novo pela identidade quando a adição termina. Ele também leva os avisos.

**Verificação:** 410 testes unitários e o E2E Java 21 com o segundo jogador, com os mesmos passos do iPod de antes (165/165 + 27/27).

## 9a-2: busca e abas

### Como funciona
- **Abas** na tela do iPod: Fila, SoundCloud, YouTube e Spotify. Numa aba de serviço, o campo busca pelo nome e a lista mostra até 10 resultados (título, artista, duração, marca do serviço). Um clique põe na fila (e começa, se o iPod estava parado); "Tocar agora" toca o escolhido logo depois da atual e, se ele já está ali, muda de lugar em vez de repetir (`IPodState.playNext`). Um link colado em qualquer aba entra na fila direto.
- **`IPodSearch`, no servidor:** o cliente manda só o pedido e, depois, o índice escolhido. Os resultados, com os links, ficam no servidor por 10 minutos (somem quando o jogador sai), então ninguém injeta título nem link. Uma busca por vez por jogador, 2 s entre buscas, no máximo 2 no servidor, que dividem os processos do yt-dlp com as faixas tocando. Auditoria: `ipod.search` (o texto) e `ipod.add` (o link escolhido).
- **`MediaResolver.searchTracks`:**
  - SoundCloud: `scsearch10`, sem as prévias de 30 s das faixas pagas;
  - YouTube: `ytsearch10`, montado só no servidor, depois de conferir que o texto não é um link; toca pelo espelho;
  - Spotify: `SpotifySearch`;
  - tira as faixas mais longas que `maxTrackMinutes` e as repetidas.
- **`SpotifySearch`:** só com a chave opcional (`ipod.spotifyClientId` e `ipod.spotifyClientSecret`, recarregáveis).
  - Token de credenciais do cliente em cache até expirar; trocar a chave descarta o antigo.
  - `GET /v1/search` com limite 10; hosts fixos (`accounts.spotify.com` e `api.spotify.com`), sem redirecionamento, no máximo 1 MB.
  - Chave recusada, Premium vencido (403), 429 com `Retry-After` e falhas de rede viram motivos claros.
  - O segredo e o token nunca vão para o log nem para a mensagem; a mensagem de uma falha de rede nem cita a exceção, que poderia trazer a URL com a busca.
  - Sem chave, a aba aceita links do Spotify e explica que a busca precisa de chave no servidor.
- **Pacotes:**
  - `C2SIPodAction` ganha `SEARCH`, `ADD_RESULT`, `PLAY_RESULT` e `HELLO` (ao abrir, a tela pergunta o que o servidor oferece: iPod ligado, links e busca do Spotify);
  - `S2CIPodSearchResults`: saneado na leitura, até 10 resultados, sem links.

**A regra nova do Spotify:** desde fevereiro de 2026, um app em modo de desenvolvimento exige Premium do dono e devolve no máximo 10 resultados por busca. Por isso a busca é opcional e os links continuam funcionando sem chave (pela página pública do player embutido, como na 1.1.0). Sem uma chave, a busca foi testada contra um servidor HTTP local com o formato documentado da API (token, busca e cada erro), não contra o Spotify de verdade.

### Verificação
- **439 testes unitários:** busca por serviço, `SpotifySearch` contra um servidor HTTP local (Basic auth, cada erro, o segredo fora das mensagens), pacotes hostis (mais de 10 resultados, textos longos, serviço inválido) e tocar agora.
- **E2E:** o yt-dlp falso ganha o `ytsearch`.
  - Busca no SoundCloud (a prévia fica de fora); escolher põe na fila sem chamar o yt-dlp de novo para os metadados.
  - Busca no YouTube (o vídeo de 1 hora fica de fora) e "Tocar agora" pelo espelho.
  - Spotify sem chave explica; capturas das abas.
- **Com o yt-dlp de verdade:** o `ytsearch` passa pela lista de extratores do mod (`soundcloud.*,youtube.*`) num IP de nuvem. A saída gravada virou fixture.

### Matriz de regressão da 9a

No commit `9722aef` (jar limpo, sem mudanças locais):

| Rodada | Resultado |
|---|---|
| Testes unitários | **439/439** |
| Java 21, relay | **176/176 + 27/27** |
| Java 8, relay | **176/176 + 27/27** |
| Java 21, modo direto | **130/130 + 20/20** |
| Java 21, sem EFX | **176/176** |
| Soak de 10 min | OK: 36 amostras, **0 violações**, 19 trocas de caixa, 4 recarregamentos do som, 0 underruns, heap 166 → 167 MB |
| Prova acústica, com EFX (2 rodadas) | OK: lã −14,2 dB no nível e −23,5 dB nos agudos; vidro −1,7 e −1,2 dB; cauda na sala de pedra −21,3 e −20,1 dB; aberto sem cauda (−147,1 dB) |
| Prova acústica, sem EFX (2 rodadas) | OK: lã −12,2 dB no nível e −0,2 dB nos agudos; vidro −0,9 e +0,4 dB; sem cauda (−142,4 dB) |

## 9b: iPod Player

### Como funciona
Por dentro, o bloco é uma rádio: `TileIPodPlayer` estende `TileRadio`. Quase tudo no mod testa `instanceof TileRadio`, então o bloco ganha de graça dono, acesso, alcance, volume, caixas ligadas pelo sintonizador, tela com o espectro, WAILA, tecla de silenciar, proteção e os limites de rádios (por chunk e por jogador).

- **Modo preso:** `TuneMode.IPOD` e o flag fixo `RadioState.ipodPlayer`. O `sanitize` força o modo no bloco (sem URL, favoritas nem playlist) e devolve uma rádio comum para URL se vier um NBT de fora. O cliente e os itens só escolhem URL ou FM (`TuneMode.tunable`).
- **Estação do relay** com a chave da posição (`ipod:b<dim>_<x>_<y>_<z>`).
- **Ligado e desligado:** o interruptor da rádio é o que todos veem, e a fila acompanha.
  - As mudanças do iPod (tela, avanço, falhas, fim da pausa) passam por `commitIPod`: tocando, transporte, chave, faixa e um só pacote.
  - Uma parada de rádio (`/fm stop`, `stopall`, bloqueio) desliga a fila no ciclo seguinte ("desligado vence").
  - Redstone nos modos da rádio, sem nunca ligar uma fila vazia.
- **Host do bloco no `IPodService`:** status para quem está a até 10 blocos, a faixa e o motivo na tela e no WAILA.
- **Ninguém por perto:** sem ninguém ao alcance (com 4 blocos de folga) do bloco nem das caixas, a estação não começa (chunk carregado sem ninguém, volta do servidor) e, tocando, fecha depois de 30 s. Volta quando alguém chega. Um bloco esquecido num chunk loader não segura estação do relay nem roda o yt-dlp.
- **Ações do bloco** (`C2SIPodAction` com coordenadas): chunk carregado, a até 8 blocos, sem bloqueio e com permissão de controle. As ações de rádio no bloco ficam só nas configurações (volume, alcance, tela, acesso, redstone, caixas).
- **Tela:** a do iPod, também para o bloco, com dono e acesso no subtítulo e o botão **Ajustes** no canto (volume, alcance, acesso, redstone, texto e cor da tela, caixas ligadas com desligar uma ou todas), liberados pelas permissões que o servidor responde, como na rádio.
- **Proteções no código que já existia:**
  - o relay não apaga a faixa do bloco (o título ICY vem vazio) nem reinicia a estação dele (uma chave não é uma URL);
  - o OpenComputers não fala com o bloco;
  - NowPlaying, WAILA e `/fm list` e `info` mostram "iPod: faixa (N na fila)".
- **Quebrar** guarda a fila e as configurações no item (volta desligado). Receita: tábuas, o iPod, uma jukebox e redstone.
- **Pacote de descrição:** a fila vai junto. O pior caso (24 KB de texto e 64 caixas) comprime para ~19,6 KB, abaixo do limite de 32.767 bytes do 1.7.10 (teste de tamanho).

### E2E
- Colocar à mão (dono e privado), adicionar e tocar (2 vozes), tela e WAILA, tela do bloco com Ajustes.
- Caixa de chão ligada ao bloco (3 vozes).
- O segundo jogador ouve, é recusado no privado e controla no público.
- Pausa e retomada, `/fm list`, `/fm stop`, redstone liga e desliga.
- Ninguém perto fecha a estação e ela volta quando alguém chega; o outro deixa de ouvir longe e ouve de novo.
- O OpenComputers recusa; quebrar devolve o item com a fila; sem relay, o bloco mostra o motivo.

### Revisão adversarial (corrigido antes do commit)
- **Chunk carregado sem ninguém:** o bloco abria a estação e chamava o yt-dlp mesmo sem ouvinte. Agora nem começa, e fecha depois de 30 s quando todos saem.
- **Memória:** o registro de "parado desde" não esquecia os blocos quebrados ou descarregados.
- **Pacotes:** os campos novos do bloco liam um byte além num pacote truncado do item; agora a leitura é tolerante (achado por dois testes).
- **Tela:** em inglês, cinco abas não cabiam e saíam cortadas; o Ajustes virou um botão no canto.
- **Do roteiro, não do mod:** o passo de colocar conferia o dono no tile que o cliente cria por previsão, antes de o servidor mandar o estado; rodadas E2E sobrepostas se matavam (agora um script isola cada uma).

### Matriz de regressão da 9b

No commit `3e23a23` (jar limpo, sem mudanças locais):

| Rodada | Resultado |
|---|---|
| Testes unitários | **454/454** |
| Java 21, relay | **202/202 + 32/32** |
| Java 8, relay | **202/202 + 32/32** |
| Java 21, modo direto | **134/134 + 20/20** |
| Java 21, sem EFX | **202/202** |
| Soak de 10 min | OK: 36 amostras, **0 violações**, 19 trocas de caixa, 4 recarregamentos do som, 0 underruns, heap 166 → 165 MB |
| Prova acústica, com EFX (2 rodadas) | OK: lã −14,2 dB no nível e −23,5 dB nos agudos; vidro −1,7 e −1,2 dB; cauda na sala de pedra −21,0 e −20,3 dB; aberto sem cauda (−147,3 dB) |
| Prova acústica, sem EFX (2 rodadas) | OK: lã −12,2 dB no nível e −0,1 dB nos agudos; vidro −0,9 e +0,4 dB; sem cauda (−142,6 e −142,4 dB) |

## 9c: alto-falante de teto e de parede

### Como funciona
Uma caixa de som fina (uma placa de 12 × 12 px e 3 px de espessura) que vai embaixo de um bloco (teto) ou na lateral dele (parede). Por dentro é uma `TileSpeaker`: canal, dono, WAILA e manutenção das ligações continuam os mesmos, e ela liga numa rádio ou num iPod Player como a caixa de chão.

- **`CeilingMount`** (só contas, testável sem o jogo): metadata 2..5 = parede, virada para fora; 6..9 = teto, com a orientação de quem colocou para o par estéreo. Em cima de bloco não vai. Forma, apoio, ponto do som e face exposta saem daqui.
- **`BlockCeilingSpeaker`:** precisa de uma face sólida atrás (em cima, no teto) e cai como item se o apoio sumir (quebrado, empurrado, explodido), desligando-se da rádio. Colisão e mira na placa; no inventário, deitada com a grade em cima. Um alto-falante não serve de apoio para outro.
- **Som:** a `TileSpeaker` diz de onde o som sai e para onde a frente aponta, e o `RadioAudioController` usa isso para todas as caixas. O emissor é o centro da placa, dentro do próprio bloco, que a oclusão ignora. Na acústica a placa conta como um tapete (o som passa quase todo).
- **Visual:** o cone pulsa na face exposta (para baixo, no teto), com o raio da grade da placa e a luz do próprio bloco.
- **Proteção do apoio:** quebrar o bloco que segura o alto-falante de outro jogador é cancelado, com um aviso próprio. Dono, admin da caixa e ops podem. Isso roda em toda quebra de bloco (mineradores do GregTech incluídos), então o vizinho só é lido se já for um alto-falante de teto, e nunca carrega chunk.
- **Receita:** uma caixa e duas lajes de madeira dão duas placas. Texturas no fim do `gen_textures.py` (as antigas não mudam); idiomas pt e en.

### E2E
- Colocar com a mão no teto e na parede (dono e orientação); em cima de um bloco é recusado.
- Ligar o do teto no iPod Player (4 vozes); ligar o da parede na rádio e trocar para estéreo (2 fontes, sem reiniciar o som).
- Captura e WAILA.
- O outro jogador não derruba a parede quebrando o apoio (o evento de quebra do Forge com o jogador de verdade).
- Tirar o apoio do teto derruba e desliga (volta a 3 vozes).

### Revisão adversarial (corrigido antes do commit)
- **Teste unitário:** o sinal da face exposta estava trocado na conta de conferência (o código estava certo).
- **Do roteiro, não do mod:**
  - a volta para perto do iPod Player calculava o destino com as coordenadas de antes de o bloco existir, e o servidor descartava o clique por distância (o 1.7.10 aceita até 6 blocos do centro do bloco clicado; o cliente prevê a colocação e gasta o item mesmo assim);
  - a parede ficava presa num TNT, que no 1.7.10 não tem face sólida (recusada, corretamente).

### Falhas intermitentes no roteiro E2E (achadas pela matriz, corrigidas antes do merge)
A primeira matriz da 9c falhou no Java 8, num passo novo: o alto-falante da parede em estéreo nunca chegava às 4 vozes esperadas. Outra rodada isolada passou inteira, então rodei o E2E Java 8 em laço, com um diagnóstico novo em todo prazo esgotado: o que o passo esperava, os contadores do OpenAL e, no log do servidor, o tile como o servidor o vê e a fila de ações (comando de teste `e2e:nbt`). Apareceram três falhas, nenhuma no alto-falante:
- **Relay desligado e religado (passos da 8c):** o roteiro desliga o relay por 1 a 2 s pelo `/fm reload`. Se a manutenção do servidor (a cada 5 s) cai nessa janela, a rádio principal fica sem transporte e é desligada, e não volta sozinha (o iPod volta). Os passos seguintes que precisam dela tocando, como os do alto-falante na rádio, falhavam de vez em quando. O diagnóstico mostrou a rádio parada no servidor (`playing:0b`) e a placa já em estéreo dos dois lados. **Correção:** o passo espera a rádio parar e, com o relay de volta, liga de novo e confere que voltou pelo relay. O comportamento do mod está documentado no guia do admin; fazer a rádio voltar sozinha, como o iPod, ficou como tarefa à parte.
- **Portátil sem o fone (passo da 6b):** falhava na hora se, no instante conferido, uma voz da rádio estivesse parada por um engasgo do stream ao vivo. Fontes e buffers batiam (sem vazamento). **Correção:** vazamento de fonte ou buffer continua falhando na hora; voz parada espera o engasgo passar.
- **Texto da tela da rádio (passo antigo):** numa rodada, o servidor nunca aplicou o texto. O mundo salvo mostrou `screenText ''`, nenhum aviso de recusa chegou ao cliente, e o limitador de ações não tinha descartado nada desde o teste de cliente malicioso, 30 s antes. A causa não foi achada. A falha não se repetiu nas 14 rodadas seguintes; se voltar, o diagnóstico novo mostra a fila de ações e o tile no servidor.

Com as correções, o E2E Java 8 passou 6 vezes seguidas com o segundo jogador (212/212 + 33/33 em cada).

### Matriz de regressão da 9c

No commit `a6e90a9` (jar limpo, sem mudanças locais):

| Rodada | Resultado |
|---|---|
| Testes unitários | **461/461** |
| Java 21, relay | **212/212 + 33/33** |
| Java 8, relay | **212/212 + 33/33** (e 6 rodadas seguidas antes, no laço) |
| Java 21, modo direto | **134/134 + 20/20** |
| Java 21, sem EFX | **212/212 + 33/33** |
| Soak de 10 min | OK: 36 amostras, **0 violações**, 19 trocas de caixa, 4 recarregamentos do som, 0 underruns, heap 165 → 165 MB |
| Prova acústica, com EFX (2 rodadas) | OK: lã −14,2 dB no nível e −23,5 dB nos agudos; vidro −1,7/−1,8 e −1,2 dB; cauda na sala de pedra −21,0 e −20,5 dB; aberto sem cauda (−147,2 dB) |
| Prova acústica, sem EFX (2 rodadas) | OK: lã −12,2 dB no nível e −0,2/−0,1 dB nos agudos; vidro −0,9 e +0,4 dB; sem cauda (−142,6 dB) |

## 9d: fechamento

### Documentação
- **Guia do admin** ([`ADMIN.md`](ADMIN.md)):
  - busca e abas (como cada serviço busca e toca, segurança e custo);
  - a chave opcional do Spotify, passo a passo: Premium do dono desde fevereiro de 2026, `chmod 600` no config, rotação do segredo e hosts usados;
  - o iPod Player: permissões, limites divididos com as rádios, relay, redstone, comandos, ninguém por perto e OpenComputers;
  - o alto-falante de teto/parede: apoio, proteção, explosões e pistões;
  - o que acontece com as rádios quando o relay é desligado e religado;
  - linhas novas na solução de problemas.
- **README**, notas do release em `.changelogs/1.2.0.md` e este documento.

### Teste com os jars de produção e o yt-dlp de verdade
`tools/prod/run.sh <pack> ipodblock`: servidor Forge dedicado e cliente Forge real (Java 8, LWJGL 2.9.1 e OpenAL Soft originais gravando um WAV), com o jar reobfuscado e os mods do pack.
- Liga o iPod pelo arquivo de config e pelo `/fm reload`, e o mod baixa o yt-dlp oficial.
- Dois iPod Players entram pelo console (`/setblock` com NBT): um com um link do SoundCloud e outro com um do YouTube, ambos com alcance 8, a ~16 blocos do jogador. Cada um está ligado a um alto-falante colado no jogador, um no teto e um na parede.
- **O som só pode chegar pelo alto-falante:** se o emissor da placa, a audiência do relay pela caixa ou o alto-falante no jar reobfuscado falhassem, o WAV sairia mudo.
- O primeiro toca pela redstone e para quando ela desliga; o segundo toca pela redstone e para com o `/fm stop`, com a redstone ainda ligada ("desligado vence").
- `tools/prod/analyze.py ... ipodblock` confere:
  - as estações do relay com a chave de cada bloco;
  - o `/fm list` com a faixa e a fila de cada um;
  - o `/fm info` tocando e parado, com o alcance e o alto-falante ligado;
  - que nenhuma faixa falhou;
  - o WAV contra as marcas.

| Pack (Java 8, servidor + cliente) | Resultado |
|---|---|
| **GTNH 2.9:** GTNHLib 0.11.52, UniMixins 0.3.1, Hodgepodge 2.7.206, OpenComputers 1.12.64 | **OK**: yt-dlp baixado e conferido 16 s depois do reload (a verificação das ferramentas roda em ciclos); SoundCloud pelo teto 7,3 s depois da redstone, até ela desligar; YouTube pelo espelho, pela parede, 10,7 s depois, até o `/fm stop` |
| **GTNH 2.8.4** (estável): GTNHLib 0.7.10, UniMixins 0.1.23, Hodgepodge 2.6.112, OpenComputers 1.11.20 | **OK**: yt-dlp pronto 3 s depois do reload; SoundCloud em 7,4 s e YouTube pelo espelho em 10,2 s |
| **GTNH 2.9, roteiros antigos** (regressão do `run.sh`, que ganhou a função que liga o iPod) | **OK**: rádio com som de 25,2 a 66,7 s (esperado 22,8 a 66,8) e de 86,9 a 109,7 s (84,9 a 109,9); iPod item com SoundCloud em 8,2 s e YouTube pelo espelho em 11,0 s |

Em todos, nenhuma pilha de exceção nem aviso do mod.

### Matriz de regressão do release

A 9d não muda código do mod (só documentação e o roteiro de produção). A matriz rodou de novo no jar limpo do commit da 9d:

| Rodada | Resultado |
|---|---|
| Testes unitários | **461/461** |
| Java 21, relay | **212/212 + 33/33** |
| Java 8, relay | **212/212 + 33/33** |
| Java 21, modo direto | **134/134 + 20/20** |
| Java 21, sem EFX | **212/212 + 33/33** |
| Soak de 10 min | OK: 36 amostras, **0 violações**, 19 trocas de caixa, 4 recarregamentos do som, 0 underruns, heap 172 → 169 MB |
| Prova acústica, com EFX (2 rodadas) | OK: lã −14,2 dB no nível e −23,5 dB nos agudos; vidro −1,7 e −1,2 dB; cauda na sala de pedra −20,5 e −22,3 dB; aberto sem cauda (−147,2 dB) |
| Prova acústica, sem EFX (2 rodadas) | OK: lã −12,2 dB no nível e −0,1/−0,2 dB nos agudos; vidro −0,9 e +0,4 dB; sem cauda (−142,5 dB) |

## Limitações conhecidas
- **Busca do Spotify** só com uma chave de um app cujo dono tem Premium (regra do Spotify desde fevereiro de 2026) e no máximo 10 resultados por busca; testada contra o formato documentado da API, não contra o Spotify de verdade (sem chave).
- **Rádios e relay:** desligar o relay pelo `/fm reload` desliga as rádios que tocavam por ele, e elas não voltam sozinhas quando ele volta (o iPod e o iPod Player voltam).
- **iPod Player:** divide com as rádios os limites por jogador e por chunk e as estações do relay (`relay.maxStations`), e cada faixa nova abre uma estação nova, como no iPod item.
- **Alto-falante de teto/parede:** explosões e pistões não passam pelo evento de quebra do Forge, então derrubam o alto-falante de outro jogador ao tirar o apoio (ele cai como item).
- As limitações do iPod da 1.1.0 continuam (DRM, YouTube direto só fora de datacenter, só pelo relay).
