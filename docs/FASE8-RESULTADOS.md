# Fase 8: resultados (iPod, 1.1.0)

A Fase 8 traz o iPod: uma fila de músicas do SoundCloud, do YouTube e do Spotify, tocando de qualquer slot do inventário, num servidor Pterodactyl sem shell. Em quatro partes:
- **8a:** spike neste ambiente (IP de nuvem, como um host Pterodactyl), para decidir o desenho;
- **8b:** infraestrutura (yt-dlp gerenciado, espelho no SoundCloud, Spotify, Range e WebM);
- **8c:** o item (estado, ações, serviço, tela, E2E);
- **8d:** integração com o `/fm`, documentação, teste de produção e release 1.1.0.

Guia do admin: [`ADMIN.md`](ADMIN.md#ipod-opcional).

## 8a: spike

Rodado neste container, com o yt-dlp 2026.08.19 e o Deno 2.9.7 oficiais (checksum conferido):

| Teste | Resultado |
|---|---|
| Instalação automática (download, SHA-256, chmod) | OK |
| SoundCloud: resolver | OK, ~5 s |
| SoundCloud: tocar | OK: MP3 progressivo no CDN (`audio/mpeg`, `content-length`, `accept-ranges`), que o `StreamPump` já tocava |
| SoundCloud: busca `scsearch` | OK, mas com prévias de 30 s (descartadas pela duração) |
| YouTube: metadados e playlists (`--flat-playlist`, 182 itens) | OK, sem Deno |
| YouTube: baixar o áudio | **Bloqueado em todos os clientes:** PO token do GVS exigido e experimento SABR |
| Memória do yt-dlp | 75 a 107 MB por processo; com o Deno resolvendo o desafio JS, pico de 423 MB |

**Decisão:** o yt-dlp só resolve (metadados e a URL do áudio), nunca toca; o áudio vai pelo relay. O YouTube, que num IP de datacenter entrega título e duração mas não o áudio, toca pelo **espelho no SoundCloud**. O Spotify também: ele nunca entrega áudio.

## 8b: infraestrutura

### Como funciona
- **`ToolManager`:** baixa o yt-dlp oficial da plataforma (Linux x64/arm64, musl, Windows, macOS) do release mais novo, confere o SHA-256 publicado no próprio release, marca como executável e procura atualização uma vez por dia. Caminho manual opcional (`ipod.ytDlpPath`); o Deno só com `ipod.youtubeDirect`.
- **`YtDlp`:**
  - lista de argumentos (sem shell), `--no-config` e `--` antes do alvo;
  - só os extratores do SoundCloud e do YouTube (`--use-extractors`), nunca o genérico: um link não vira SSRF;
  - allowlist de hosts do link (SoundCloud, YouTube, Spotify) antes de chamar o processo;
  - timeout que mata o processo e os filhos (`ProcessHandle` pelas interfaces públicas, que funcionam com módulos);
  - `TMPDIR` em `akashicfm/tools/tmp` (no Pterodactyl o `/tmp` é um tmpfs pequeno, e o yt-dlp empacotado se extrai lá);
  - `--ignore-no-formats-error`: sem ele, num IP de datacenter, vídeos de gravadora davam "Video unavailable" em vez do título e da duração.
- **`MediaResolver`:** link ou busca → faixas; na hora de tocar, a URL do áudio. Faixa com DRM é pulada, nunca contornada. Cache do espelho e do "não achado" (não para falhas passageiras), cancelamento sem abrir processos novos e no máximo `ipod.maxResolves` processos ao mesmo tempo.
- **`MirrorMatcher`/`TitleCleaner`**, calibrados com buscas de verdade:
  - duração: Spotify max(6 s, 4%), YouTube max(10 s, 6%); clipe: áudio até 25% mais curto; prévias de até 31 s recusadas;
  - outra versão recusada no título, dentro de palavras ("RodrydjFlowRMX") e no link (remix, cover, ao vivo, acelerada, efeitos);
  - artista por nome inteiro, obrigatório quando conhecido; as duas leituras de "A - B"; bônus para o upload do próprio artista;
  - duas buscas (a principal e uma reserva), até quatro candidatos cada, pulando os com DRM.
- **`SpotifyClient`:** título, artistas e duração pela página pública do player embutido (`open.spotify.com/embed/...`), sem chave. A API oficial deixou de servir em fevereiro de 2026: o modo de desenvolvimento exige Premium do dono do app e não lista playlists de outros. Até 100 faixas (o limite do player).
- **Relay:** estações com chave (`ipod:<dono>`) e o áudio de um `MediaLocator`, que renova a URL assinada quando o CDN a recusa. `FrameRing.pause` guarda os frames ainda não enviados e os devolve na volta com as mesmas sequências.
- **`ResumableInputStream`:** um arquivo que cai no meio retoma do byte exato por Range (206 com `Content-Range` conferido), com novas tentativas e a conta zerada depois de progresso. Vale também para as rádios.
- **`WebmSource`:** WebM/Matroska com Opus (tamanhos desconhecidos, lacing Xiph, fixo e EBML, pre-skip); blocos grandes de vídeo são pulados.

### Verificação
- **389 testes unitários:** fixtures gravadas do yt-dlp e do Spotify, um yt-dlp falso para o fluxo inteiro e processos de verdade para o timeout e o kill.
- **Sondas contra os serviços reais** (`IPodProbe`, `RangeProbe`): SoundCloud direto, set, YouTube e Spotify pelo espelho, busca. A retomada por Range com duas quedas saiu idêntica byte a byte no CDN, e uma URL expirada responde 403 (renovada).
- **Matriz completa** com o jar da fase.

### Revisão adversarial (corrigido antes do commit)
- **Falsos positivos do espelho**, achados com buscas reais: outra música com o mesmo título (agora o artista é obrigatório, por nome inteiro), versão cortada, remix colado no nome, "ao vivo" só no link, versão acelerada e com efeitos. E um falso negativo: o upload oficial ficava de fora (as duas leituras de "A - B", o bônus do artista e a janela do clipe).
- **Retomada:** sem novas tentativas quando a reabertura falhava, e com um limite global em vez de por progresso.
- **Cache:** falhas passageiras viravam "não achado".
- **Cancelamento:** continuava abrindo processos.
- **Kill dos filhos:** falhava em silêncio com módulos (reflexão no `ProcessHandle`).
- **Segredos:** a `HttpStatusException` levava a query string (assinaturas) para o log e a tela.
- **URLs do CDN:** passam de 600 caracteres e eram recusadas pelo limite das rádios (`UrlPolicy.resolvedMedia`, até 4096).
- **WebM:** blocos acima de 1 MB derrubavam a leitura; agora são pulados.

## 8c: o iPod

### Como funciona
- **`IPodState`** no NBT do item: fila (link, título, artista, duração, origem), faixa atual, pausa, volume e repetição (não, tudo, esta faixa), saneado como o `PortableState`. **Orçamento de 24 KB de texto**, além do limite de faixas: o 1.7.10 manda o NBT do item comprimido com o tamanho num `short` (32 KB), e o pior caso (200 títulos que não comprimem) cabe (teste com o NBT comprimido).
- **`IPodService`** (thread principal; o lento num pool próprio, conferido por `Future.isDone`):
  - resolve a faixa e abre a estação do relay com a chave do dono: quem está perto ouve sincronizado; com fone, só o dono;
  - avança quando o último áudio soou nos clientes, pela repetição;
  - pula a faixa que falha, com o motivo na tela (DRM, não achada, rede); cinco falhas seguidas (ou a fila inteira) param o iPod;
  - resolve a próxima no último minuto da atual (as URLs dos CDNs expiram em minutos);
  - guarda os metadados que faltavam (itens de set do SoundCloud chegam sem título);
  - pausa com timeout (`ipod.pauseTimeoutMinutes`).
- **`PortableSources`:** o primeiro aparelho ligado do inventário (rádio portátil ou iPod) decide; o iPod vira uma fonte do relay com a chave da estação, o título e o motivo quando não toca.
- **`C2SIPodAction`:** adicionar, tocar índice, tocar/pausar, parar, próxima, anterior (com mais de 5 s tocados, volta ao começo), remover, limpar, embaralhar as seguintes, repetir, volume. Mesmo rate limit e fila das outras ações; link conferido pela allowlist antes do yt-dlp; uma adição por vez por jogador; auditoria `ipod.add`. **`S2CIPodStatus`** para o dono: fase, posição, duração e motivo.
- **`GuiIPod`:** campo de link ou busca, faixa atual com progresso, controles, fila com rolagem, clique duplo para tocar, marcas [YT]/[SP], volume e avisos com vários argumentos. **`ItemIPod`:** agachado + botão direito toca/pausa; textura, receita, idiomas e "tocando agora" como "iPod".

### E2E
Um **yt-dlp falso** (`tools/e2e/fake-yt-dlp.sh`: a mesma linha de comando, respostas fixas e áudio público curto) torna o fluxo determinístico:
- link de outro site recusado;
- set do SoundCloud com a primeira URL expirada (404), renovada;
- faixa protegida pulada e fim da fila;
- YouTube pelo espelho escolhendo a versão certa (sem a prévia nem o remix), conferido pelas chamadas registradas pelo falso;
- faixa longa (160 s) com pausa (o relay para de mandar e a posição congela) e retomada;
- o segundo jogador ouve, e o fone isola;
- repetir e anterior; relay desligado e religado com o iPod tocando; parar; auditoria; captura da tela;
- no modo direto, o aviso de que o iPod precisa do relay.

### Revisão adversarial (corrigido antes do commit)
- **Jogador morto ou bloqueado** continuava resolvendo e tocando: o serviço pula esses jogadores (o `PortableSources` já os deixava mudos).
- **Identidades repetidas:** copiar um iPod no criativo copia a identidade do item, e duas cópias em jogadores diferentes dividiam sessão e estação. Agora as sessões e as chaves de estação são do dono.
- **Relay desligado e religado pelo `/fm reload`:** o iPod ficava parado até o jogador apertar algo (a sessão achava que a faixa já estava resolvida). Agora para com o motivo e recomeça sozinho; um passo do E2E cobre.
- **Custo por tick e por quadro:** o `onUpdate` do item (a cada tick) e o brilho (a cada quadro) liam a fila inteira do NBT; agora leem só a identidade e o estado. A tela relê o NBT só quando ele muda.
- **Tela:** o subtítulo passava da largura e o cabeçalho da fila encostava nos botões.
- **Do roteiro, não do mod:** um heredoc sem aspas no yt-dlp falso apagava as variáveis do ramo da faixa longa (o diagnóstico veio dos logs de transição do serviço).

### Prova acústica estável
Na matriz da 8c, uma rodada da prova acústica sem EFX falhou: C, D e E saíram 8 dB mais baixos, com o estado acústico do jogo (oclusão e ganho) idêntico ao das rodadas que passaram. Três repetições passaram. Duas causas, nenhuma no som:
- **A fonte era a rádio ao vivo.** Os trechos são medidos um depois do outro, e uma passagem baixa da música derruba as comparações (A contra D, vidro contra o aberto). Já tinha acontecido na Fase 6, nos agudos.
- **O WAV era int16, com o dither do OpenAL Soft** (~-99 dBFS) no lugar do silêncio. A "cauda" depois do corte media esse piso em relação à música logo antes: com uma passagem baixa, já dava -54 dB (o limite sem cauda é -60).

**Correção, sem mudar nenhum limite:** a fonte é chuva forte (`rain_heavy_loud.ogg`, OGG Opus de 8 min no mesmo host das faixas do E2E: ±1 dB no nível e ±0,5 dB nos agudos entre trechos), e o WAV é float32 (sem dither, o silêncio é zero). Os números passaram a bater com a conta e a se repetir de uma rodada para outra:

| Medida | Com EFX | Sem EFX |
|---|---|---|
| Lã: nível | -14,2 dB | -12,2 dB (calculado: -12,6) |
| Lã: agudos | -23,5 dB | -0,1 dB |
| Vidro: nível e agudos | -1,7 e -1,3 dB | -0,9 e +0,4 dB |
| Aberto A e D | -50,2 e -50,7 dBFS | -50,2 e -50,7 dBFS |
| Cauda na sala de pedra | -20,2 dB | -142,6 dB |
| Cauda no aberto | -147,3 dB | -147,3 dB |

No Java 8 (OpenAL Soft 1.15): lã -16,2/-22,9 dB com EFX e -12,2/+0,2 dB sem; cauda na sala -20,3 dB.

### Matriz de regressão da 8c

No commit `15241d7` (jar limpo, sem mudanças locais):

| Rodada | Resultado |
|---|---|
| Testes unitários | **410/410** |
| Java 21, relay | **162/162 + 27/27** |
| Java 8, relay | **162/162 + 27/27** |
| Java 21, modo direto | **130/130 + 20/20** |
| Java 21, sem EFX | **162/162** |
| Soak de 10 min | OK: 36 amostras, **0 violações**, 19 trocas de caixa, 4 recarregamentos do som, 0 underruns, heap 167 → 165 MB |
| Prova acústica | OK, duas rodadas com EFX e duas sem (valores acima) |

## 8d: fechamento

### Integração com o admin
- **`/fm stopall`** desliga também os iPods (antes, só os rádios portáteis).
- **`/fm list portables`** mostra a faixa de cada iPod ("iPod: Forss - Flickermood"), não a chave da estação.
- Os dois com passos no E2E.

### Teste com os jars de produção e o yt-dlp de verdade
`tools/prod/run.sh <pack> ipod`: servidor Forge dedicado e cliente Forge real (Java 8, LWJGL 2.9.1 e OpenAL Soft originais gravando um WAV), com o jar reobfuscado e os mods do pack.
- Liga o iPod como no Pterodactyl: edita `config/akashicfm.cfg` e roda `/fm reload`, sem reiniciar.
- O mod baixa o yt-dlp oficial do GitHub e confere o SHA-256.
- Pelo console, o jogador ganha um iPod com um link do SoundCloud (toca direto) e depois outro com um do YouTube (toca a mesma música achada no SoundCloud); o `/fm stopall` desliga o segundo.
- `tools/prod/analyze.py ... ipod` confere o WAV contra as marcas, as estações do iPod no relay, o `/fm list portables` com as duas faixas e que nenhuma faixa falhou.

| Pack (Java 8, servidor + cliente) | Resultado |
|---|---|
| **GTNH 2.9:** GTNHLib 0.11.52, UniMixins 0.3.1, Hodgepodge 2.7.206, OpenComputers 1.12.64 | **OK**: yt-dlp pronto 2 s depois do reload; SoundCloud tocando 7,5 s depois de dado, até ser tirado; YouTube pelo espelho 10,5 s depois, até o `stopall` |
| **GTNH 2.8.4** (estável): GTNHLib 0.7.10, UniMixins 0.1.23, Hodgepodge 2.6.112, OpenComputers 1.11.20 | **OK**: SoundCloud em 7,1 s e YouTube pelo espelho em 10,2 s, com o `/fm reload` pelo caminho alternativo |
| **GTNH 2.9, roteiro da rádio** (regressão do `run.sh`) | **OK**: som de 25,5 a 67,0 s (esperado 22,9 a 66,9) e de 87,3 a 110,0 s (85,0 a 110,0) |

Em todos, nenhuma pilha de exceção nem aviso do mod; a pasta temporária do yt-dlp fica vazia depois de cada processo.

**Armadilha do ambiente, não do mod:** a primeira rodada falhou com PKIX ao baixar o yt-dlp. Aqui o HTTPS sai por um proxy que re-assina o TLS com uma CA que só está no truststore do sistema (`javax.net.ssl.trustStore`). O `LetsEncryptHelper` do FalsePatternLib (no GTNHLib do GTNH 2.9) troca o `SSLContext` padrão por um feito do `cacerts` do próprio JDK, sem essa CA. Num servidor de verdade, sem proxy, o GitHub fecha a cadeia com raízes públicas que estão no `cacerts`. Com proxy, o `run.sh` desliga só esse helper (`falsepatternlib-early.json`), e a verificação de TLS continua, com o truststore configurado.

### Matriz de regressão do release

No código da release (o jar do commit da 8d, sem mudanças locais):

| Rodada | Resultado |
|---|---|
| Testes unitários | **410/410** |
| Java 21, relay | **165/165 + 27/27** |
| Java 8, relay | **165/165 + 27/27** |
| Java 21, modo direto | **130/130 + 20/20** |
| Java 21, sem EFX | **165/165** |
| Soak de 10 min | OK: 36 amostras, **0 violações**, 19 trocas de caixa, 4 recarregamentos do som, 0 underruns, heap 166 → 165 MB |
| Prova acústica, com EFX (2 rodadas) | OK: lã −14,2 dB no nível e −23,5 dB nos agudos; vidro −1,8 e −1,2 dB; cauda na sala de pedra −21,5 e −20,7 dB; aberto sem cauda (−147,2 dB) |
| Prova acústica, sem EFX (2 rodadas) | OK: lã −12,2 dB no nível e −0,2 dB nos agudos; vidro −0,9 e +0,4 dB; sem cauda (−142,5 dB) |

## Limitações conhecidas
- **Músicas de gravadora** no Spotify e no YouTube muitas vezes só existem com DRM no SoundCloud: o iPod pula ("Protegida no SoundCloud") ou não acha. Música independente, remixes e uploads de fãs funcionam bem.
- **YouTube direto** (`ipod.youtubeDirect`) só fora de datacenter: num IP de nuvem o YouTube exige PO token para o áudio.
- **Spotify:** até 100 faixas por playlist (o limite do player embutido).
- **Termos de uso:** SoundCloud, YouTube e Spotify não permitem esse uso; por isso o iPod vem desligado.
- **Só pelo relay:** no modo direto o iPod não toca (o cliente teria de rodar o yt-dlp).
