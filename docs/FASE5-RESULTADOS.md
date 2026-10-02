# Fase 5: resultados

A Fase 5 entrega o **"tocando agora"** completo, o **visualizador** (espectro na tela da rádio e cone das caixas pulsando) e a integração opcional com o **WAILA**.

## Tocando agora

- **Relay:** o servidor lê o `StreamTitle` (ICY) da estação e grava no estado da rádio (no máximo uma atualização a cada 5 s; já vinha da Fase 3).
- **Modo direto:** o servidor não baixa o stream, então não sabe o título. Agora o cliente usa o título que o stream anunciou para ele (`AudioFeed.streamTitle()`), saneado do mesmo jeito (`TextSanitizer`, 128 caracteres).
- **Onde aparece** (`NowPlaying`):
  - na tela da rádio (TESR);
  - na GUI;
  - no aviso acima da barra de itens;
  - no WAILA.
- **Host:** o nome da estação (host da URL) passou a tratar IPv6 literal (`[2001:db8::1]`). Antes cortava em `[2001`.

### Aviso "Tocando agora"
- **Onde:** é a **mesma mensagem dos discos da jukebox** (`GuiIngame.setRecordPlayingMessage`), acima da barra de itens, traduzida pelo próprio Minecraft e com o efeito arco-íris.
  - **Por que não um painel próprio:** a primeira versão desenhava um aviso no canto superior direito. A captura de tela do E2E mostrou que ele **ficava escondido atrás do popup de conquistas do vanilla**, que ocupa o mesmo canto (e na GTNH ali fica o minimapa do JourneyMap).
  - O lugar que o jogo reserva para música não briga com nada.
- **Quando** (`NowPlayingTracker`, lógica pura testada):
  - só para a rádio mais alta que o jogador **ouve de verdade**: a reprodução já tocando (não conectando nem enchendo o buffer), com ganho acima de ~-34 dB e já considerando o abafado das paredes;
  - ao começar a ouvir uma estação e quando o título muda; sem título, espera até 4 s por ele antes de mostrar o nome da estação, para não sair "host" e logo depois "título";
  - não repete o mesmo texto; quem passa 10 s sem ouvir é esquecido (voltar mostra de novo);
  - no máximo um aviso a cada 3 s.
- **Config:** `client.showNowPlaying` (ligado).

## Visualizador

- **Espectro (`SpectrumAnalyzer`):** FFT de 2048 pontos (um bloco da reprodução) com janela de Hann sobre a mistura mono.
  - **Bandas:** 8, quase oitavas, de 40 Hz a 16 kHz, cada uma de 0 a 1 entre −60 e 0 dBFS (seno cheio = 0 dBFS).
  - **Por que 2048 e não 1024:** o teste do seno de 63 Hz mostrou que, com 1024 pontos, a banda de 40–100 Hz tinha 1 bin só e um seno entre bins vazava para a vizinha.
- **Alinhado com o que soa:** cada bloco guarda o próprio espectro junto do histórico. A tela mostra o do bloco que **está tocando agora** (o primeiro da fila do OpenAL), não o que acabou de entrar na fila, 340 ms adiante. As barras sobem rápido (40 ms) e descem devagar (300 ms), como num VU.
- **Custo:** só calcula se alguém pediu nos últimos 2 s (a tela da rádio ou o cone de uma caixa à vista). Mesmo assim são ~23 FFTs por segundo por estação.
- **Tela da rádio:** 8 barras na cor da tela, translúcidas, atrás do texto (que continua legível).
- **Cone da caixa** (`TileSpeakerRenderer`):
  - **Movimento:** o mesmo disco do cone da textura, desenhado por cima da face, cresce até 7% e avança até 0,012 bloco com os graves (as 3 primeiras bandas). Em repouso coincide com a face.
  - **Fica dentro da moldura:** o disco tem raio 6,6 px, 7,06 px no máximo, contra a moldura a 7,5 px.
  - **Luz:** usa a do bloco em frente com o sombreamento do vanilla para aquela face, como a face de verdade (a luz "de dentro" de um bloco sólido seria escura).
- **Config:** `client.radioVisualizer` (ligado).

## WAILA (opcional)
- **Dependência:** só para compilar (`com.github.GTNewHorizons:waila:1.19.34`, maven da GTNH). O registro é por IMC e acontece apenas se o WAILA estiver carregado.
  - Nenhuma classe do mod referencia a integração: sem o WAILA, nada dela é carregado.
  - O formato `classe.método` foi conferido no bytecode do WAILA.
- **Corpo do tooltip:**
  - rádio: estado e estação, título, volume e alcance, transporte, acesso e dono, caixas ligadas, aviso da estação;
  - caixa: canal e rádio ligada.
- **Onde registra:** só no cliente. As linhas usam classes do cliente, e num servidor dedicado com WAILA o registro não faz nada.
- As linhas saem de `RadioInfo`, que não depende do WAILA (o E2E testa por ela).

## GeckoLib
Não há artefato do GeckoLib para 1.7.10 no maven da GTNH (busca no Nexus: 0 resultados). Como o plano previa, o visual fica no TESR, sem dependência obrigatória.

## Verificação

**Testes unitários** (200 no total; 18 novos nesta fase):
- `SpectrumAnalyzer`:
  - seno no centro de cada banda cai na banda certa com −6 dBFS → 0,9, e seno cheio vale 0 dBFS;
  - silêncio é 0; ruído branco espalha; canais opostos se cancelam;
  - bloco curto; FFT de impulso plana; mapeamento de nível.
- `NowPlayingTracker`:
  - mostra uma vez; título novo; espera do título;
  - título que chega na espera gera um aviso só;
  - inaudível não conta; intervalo mínimo com pendência;
  - pendente que deixou de tocar; esquecimento; reset.
- `NowPlaying.hostOf`: usuário, porta, caminho, IPv6.

**E2E** (`tools/e2e/run-all.sh`):

| Passo | Java 21 | Java 8 |
|---|---|---|
| Título do relay chega ao cliente | 'KELS - Amphetamines' (pelo estado da rádio) | 'Oi Va Voi - Worry Lines' |
| Aviso entregue com o título, e de novo depois de esquecer (captura `e2e-tocando-agora.png`) | ok | ok |
| Segundo jogador (não-op) também recebe o aviso | ok | ok |
| Espectro tocando música | nível máx 0,88, soma das bandas 5,65 (energia nas 8 bandas) | nível máx 0,89, soma 5,13 |
| Graves que movem o cone (captura `e2e-speaker.png`) | 0,85 (o cone se mexe acima de 0,45) | 0,88 |
| WAILA da rádio e da caixa | rádio: "Playing: stream.radioparadise.com", "♪ título", volume/alcance, transporte, acesso; caixa: canal e "Linked to the radio at x, y, z" | ok |
| Roteiro completo | **45/45 + 9/9** | **45/45 + 9/9** |

**Modo direto** (`AKASHICFM_E2E_TRANSPORT=direct`): **42/42 + 8/8**.
- **Título:** veio do próprio stream neste cliente ("Marisa Anderson - Into the Light", com o estado da rádio vazio, como deve ser no direto).
- **Espectro:** nível 0,90.
- **Graves:** 0,86.

**Soak de 10 min:** 36 amostras, **0 violações** (objetos AL, EFX e threads), 0 underruns, heap 150 → 144 MB.

O aviso entregue ao começar a ouvir é **um só**, já com o título. Antes da revisão saíam dois: o host e, logo depois, o título.

**Capturas de tela** (`run/client/screenshots/`):
- `e2e-tocando-agora.png`: "Now playing: …" acima da barra de itens;
- `e2e-radio-tesr.png`: barras atrás do texto da tela;
- `e2e-speaker.png`: cone limpo, com o mesmo sombreamento da face.

## Revisão adversarial (corrigido antes do commit)
- **Aviso cedo demais:** saía assim que a reprodução era criada, antes do som (pré-buffer de 1,5 s no relay; no direto, uma estação que nem conecta ganharia "Tocando agora"). Agora só conta reprodução que já está tocando.
- **Aviso escondido:** o painel próprio ficava escondido atrás do popup de conquistas (achado na captura de tela). Trocado pela mensagem da jukebox.
- **Host com IPv6:** o host de URL com IPv6 literal era cortado no primeiro ":".
