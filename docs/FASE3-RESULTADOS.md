# Fase 3: resultados

A Fase 3 entrega o **relay**: o servidor baixa cada estação uma vez, codifica em Opus e manda só para quem está no alcance. Todos os jogadores ouvem em sincronia, e nenhum cliente conecta na URL (o IP dos jogadores não vaza para o servidor de streaming).

## Arquitetura

### Servidor
- **`StreamPump`** (comum ao modo direto e ao relay): HTTP/ICY → detecção de formato → decoder → 48 kHz, com as regras de reconexão já validadas na Fase 1. O DNS roda na thread da estação; a política de URL é a do servidor.
- **`Station`:**
  - junta blocos de 20 ms e codifica em Opus (bitrate do config);
  - guarda no **`FrameRing`** com número de sequência e PTS (relógio monotônico do servidor): o primeiro frame recebe o relógio atual e cada seguinte +20 ms; se a entrada atrasa mais de 500 ms, o PTS volta para o relógio (rebase, uma lacuna para os clientes);
  - a escrita bloqueia quando o PTS está mais de 2 s à frente: o burst inicial dos servidores de streaming vira folga, e o TCP segura o resto.
- **`StationHub`:** uma estação por URL, servindo todas as rádios que tocam aquela URL, com carência de 10 s sem ouvintes antes de fechar. O limite `maxStations` é respeitado já na escolha do transporte: se não cabe, a rádio vai para o modo direto (se permitido) ou o jogador é avisado.
- **`RelayService`** (tick principal):
  - **Audiência**, a cada 10 ticks: o jogador recebe a estação se alguma fonte (rádio ou caixa, sem carregar chunk) está dentro do alcance, com histerese de 4 blocos; respeita `maxListeners`.
  - **Envio**, todo tick: frames com PTS até 100 ms à frente. Quem entra começa em `agora − latência + 200 ms` e já toca sincronizado, com 1,3 s de folga. Depois de um lag do servidor, frames velhos demais são pulados.
  - **Relógio:** responde os pings com t2 carimbado no envio.
  - **Status:** erro ou reconexão da estação vai para a rádio (traduzível), e o título ICY vai para `nowPlaying` (no máximo uma atualização a cada 5 s). "Tocar" numa estação com erro abre outra conexão.

### Cliente
- **`ClockSync`:**
  - ping a cada 250 ms até ter 4 amostras, depois a cada 5 s;
  - offset NTP = mediana das 8 trocas de menor RTT entre as últimas 16.
- **`RelayFeed`:** os frames vão para uma fila e uma thread por estação decodifica (`RelayDecoder`) para o `TimedPcmRing`, que conhece o PTS da posição de leitura.
  - Lacuna de até 1 s é preenchida (PLC do Opus nos primeiros frames, depois silêncio), mantendo a linha do tempo.
  - Lacuna maior abre um segmento novo.
  - Frame repetido é ignorado; frame corrompido vira PLC.
- **Reprodução por estação:** rádios com a mesma URL viram **uma** reprodução com as fontes de todas, então ficam exatamente juntas.
- **Início** (`Playback`): com o relógio pronto, pula até o PTS devido (`relógio do servidor − latência`) com precisão de amostra e espera se o dado ainda for do futuro. Assim todos os clientes começam no mesmo ponto, independente do FPS.
- **Deriva:** mede a posição tocada de verdade (PTS do bloco na frente da fila + `AL_SAMPLE_OFFSET`), suaviza o erro e corrige pelo `AL_PITCH` (±0,2%, inaudível; zona morta de 2 ms). Acima de 250 ms, ressincroniza.

## Achado: os handlers de pacote do 1.7.10 rodam no tick
O código do FML 1.7.10 mostra que o `FMLProxyPacket` **não** tem prioridade: pacotes de mod entram na fila do vanilla e o handler só roda no tick seguinte da thread principal (0 a 50 ms depois da chegada). Carimbar t1/t3 no handler deu RTT mínimo de **53 ms** em localhost e viés de relógio de até ±25 ms.

**Correção:** um mixin em `NetworkManager.channelRead0` (método do netty, mesmo nome em produção) anota, na thread do netty, a hora de chegada dos pings e pongs do AkashicFM (`ClockStamps`).
- Ele só lê o discriminador e o t0, sem consumir o buffer, e o pacote segue o caminho normal.
- Processar os pacotes com prioridade seria mais simples, mas inseguro no servidor: o `EmbeddedChannel` do FML é compartilhado entre conexões, e duas threads do netty poderiam trocar o jogador de uma ação.
- Os comentários do código que diziam "handler roda na thread de rede" foram corrigidos (os handlers já eram seguros em qualquer thread).

## Verificação

**Testes unitários** (147 no total):
- `FrameRing`: PTS, rebase, contrapressão, busca para quem entra;
- `TimedPcmRing`: PTS na leitura, segmentos, pular;
- `ClockMath`: troca simétrica e assimétrica, estimador com metade das trocas congestionadas;
- `RelayDecoder`, com Opus real: lacunas de 60 ms e 33 ms, descontinuidade de 8 s, frame repetido e corrompido;
- ida e volta dos pacotes, com pacotes malformados descartados sem exceção.

**E2E** (`tools/e2e/run-all.sh`, servidor + 2 clientes reais, rádio real pelo relay):

| Medida | Java 21 + lwjgl3ify + Hodgepodge | Java 8 (LWJGL2) |
|---|---|---|
| Roteiro | **33/33 + 8/8** | **33/33 + 8/8** |
| RTT mínimo / erro da estimativa do relógio | 1,1 a 1,9 ms / 0,05 a 0,6 ms | 1,5 a 1,7 ms / 0,4 ms |
| **Erro real de sincronia** (`main` / `peer`) | **−3,9 / −3,3 ms → 0,6 ms entre os clientes** | **−3,3 / +3,2 ms → 6,5 ms entre os clientes** |
| Banda por ouvinte | **8,46 KB/s** | **8,55 KB/s** |
| Jogador fora do alcance | **0 bytes** enviados; a estação fecha no cliente | idem |
| Caixas sem cortes, recarregamento do som, teleporte, reconexão | passam também com o relay | idem |

Meta do plano: menos de 50 ms entre clientes. O Opus a 64 kbps custa 8 KB/s; o resto é cabeçalho.

**Soak de 10 min com o relay** (`tools/e2e/run-soak.sh`):
- **Carga:** 19 trocas de caixa e 4 recarregamentos do som.
- **Resultado:** **0 violações** das invariantes de objetos AL e threads, **0 underruns**, heap 144 → 144 MB.

O **erro real** usa o fato de que no E2E servidor e clientes rodam na mesma máquina: o `nanoTime` é o mesmo relógio monotônico, então o offset verdadeiro é 0 e erro real = erro medido + offset estimado. Sem isso, o teste só veria o erro contra a própria estimativa de cada cliente. Essa medida é uma asserção do E2E (|erro real| < 20 ms).

**Regressão do modo direto** (`AKASHICFM_E2E_TRANSPORT=direct tools/e2e/run-all.sh`): **30/30 + 7/7**.

## Revisão adversarial (corrigido)
- Relay desligado no config com o servidor rodando: as assinaturas eram limpas sem avisar os clientes, que ficavam com threads de decoder ociosas até desconectar. Agora cada ouvinte recebe "parar".
