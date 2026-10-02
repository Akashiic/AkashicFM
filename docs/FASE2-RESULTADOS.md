# Fase 2: resultados

A Fase 2 fecha o áudio 3D: caixas por canal sem cortes, recarregamento do sistema de som no meio da reprodução, convivência com o Hodgepodge e prova de que nada vaza em uso prolongado.

## O que entrou

### Vozes reconciliadas por identidade, sem cortes
A `Playback` reconciliava as vozes **pela ordem da lista**, e qualquer diferença recriava todas as fontes. Isso trazia dois problemas:
- **Bug real:** quando há corte de vozes por distância (mais de 16 por rádio ou o teto global de 96), a ordem muda conforme o jogador anda. Cada mudança de ordem recriaria tudo, uma tempestade de recriações com som picotando.
- Ligar, desligar ou trocar o canal de uma caixa cortava o som de todas as fontes por uns 0,3 s.

Agora:
- **Identidade, não ordem:** voz = posição + canal. Mudou só a ordem, nada acontece além do ganho.
- **Saída sem corte:** só a fonte que saiu é apagada.
- **Entrada sem corte:** a fonte nova recebe cópia dos blocos que as outras ainda têm na fila (há um histórico curto, com número de sequência por bloco) e começa no mesmo `AL_SAMPLE_OFFSET` da fonte de referência. A diferença fica abaixo de um ciclo do mixer do OpenAL. Com o jogo pausado, entra pausada e retoma junto.
- **Sem fontes livres no OpenAL:** mantém as vozes que já tem e tenta o resto depois, sem derrubar as outras.

### Ciclo de vida do contexto OpenAL
- O recarregamento do som (o mesmo caminho do F3+T e da troca de dispositivo do ArchaicFix) passa pelo mixin em `LibraryLWJGLOpenAL`:
  - `cleanup` apaga as fontes do mod com o contexto ainda válido;
  - `init` marca o contexto novo;
  - as vozes são recriadas no frame seguinte e a reprodução continua sozinha.
- Contadores de objetos AL vivos (`Voice.LIVE_SOURCES`/`LIVE_BUFFERS`) permitem provar que nada vaza: com as reproduções estáveis, fontes = vozes e buffers = vozes × 10, sempre.

### Config dentro do jogo
- Botão "Config" na lista de mods (`FmGuiFactory` + `SimpleGuiConfig` do GTNHLib).
- As opções do cliente (volume das rádios, limite de simultâneas, streams diretos, liga/desliga) valem na hora.

## Verificação

**E2E de regressão** (`tools/e2e/run-all.sh`), com passos novos:

| Passo | Resultado |
|---|---|
| Ligar caixa durante a reprodução | 1 voz entrou alinhada, **0 reinícios, 0 underruns** |
| Trocar o canal da caixa 3× (mono → L → R → estéreo) | 4 entradas alinhadas, **0 reinícios, 0 underruns** |
| Desligar caixas | **0 reinícios, 0 underruns** |
| Recarregar o sistema de som tocando | geração 3 → 5, fontes recriadas em **9 ticks**, 2 fontes e 20 buffers vivos (= 2 vozes) |
| Tela de config do mod | abre (captura em `run/client/screenshots/e2e-config.png`) |

**Soak de 10 minutos** (`tools/e2e/run-soak.sh`):
- **Carga:** uma rádio tocando um stream real, uma caixa ligando e desligando a cada 30 s (19 trocas) e o sistema de som recarregado a cada 2,5 min (4 recarregamentos).
- **Amostras:** a cada 10 s, com tudo estável. Em todas, toda voz tem 1 fonte AL tocando e exatamente 10 buffers, não há objeto AL fora das vozes, e há 1 reprodução e 1 thread de rede.

| Medida | Resultado |
|---|---|
| Violações das invariantes | **0** (36 amostras) |
| Underruns | **0** |
| Heap depois de GC | 144 MB → 145 MB |

## Convivência
- **Hodgepodge 2.7.206** (no `runClient21`/`runServer21`): todos os cenários passam com a biblioteca OpenAL dele e o pré-alocamento de fontes.
- **ArchaicFix:** não está no ambiente de dev. A troca de dispositivo dele recarrega o sound system pelo mesmo caminho do F3+T, que está coberto.
- **Hodgepodge `ALC_SOFT_reopen_device`:** reabre o dispositivo mantendo o contexto, então as fontes do mod continuam vivas sem nada a fazer.
