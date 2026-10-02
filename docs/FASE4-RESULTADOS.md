# Fase 4: resultados

A Fase 4 entrega a **oclusão por blocos** e o **reverb por sala**. Paredes abafam o som (lã muito, pedra bastante, vidro quase nada) e o lugar onde o jogador está muda a reverberação (sala de pedra, caverna, campo aberto). Com EFX, as paredes cortam os agudos e o reverb é de verdade. Sem EFX, a oclusão fica só no volume.

## Oclusão

### Traçado (`OcclusionTracer`)
- **DDA voxel** (Amanatides & Woo) do ouvinte até a fonte. O bloco do ouvinte e o da fonte nunca contam.
- **5 raios:** para o centro da fonte e para 4 pontos em volta dela, perpendiculares à direção, a 0,4 bloco. A oclusão é 1 − média das transmissões, então uma fonte saindo de trás de uma quina perde o abafado aos poucos, sem liga/desliga.
  - Os 4 pontos ficam **presos dentro do bloco da fonte**. Sem isso, o lado direito do par estéreo da rádio (a 0,3 do centro) mandava raios que terminavam no bloco vizinho e contavam o bloco da própria rádio.
- **Absorção pelo comprimento do caminho** (lei de Beer–Lambert): um bloco de absorção `a` atravessado por `l` blocos de caminho transmite `(1 − a)^l`.
  - **Bug achado pelo teste da quina:** contando voxels inteiros, um raio oblíquo passava por 1 ou 2 voxels da mesma parede de 1 bloco. Andando ao longo da parede, a oclusão pulava entre 0,90 e 0,99 (com pedra, entre 0,6 e 0,84).
  - Pelo comprimento do caminho a variação é contínua. A parede a 45° vale √2 bloco, como deve ser, e um raio que raspa a quina de um bloco atenua quase nada.

### Acústica dos blocos (`BlockAcoustics`)
São duas grandezas diferentes:
- **absorção**, quanto o bloco abafa o som que o *atravessa*;
- **amortecimento**, quanto a superfície absorve do som que *bate* nela (para o reverb).

Pedra abafa bastante mas reflete; lã abafa e absorve.

| Material | Absorção | Amortecimento |
|---|---|---|
| vidro / gelo | 0,1 / 0,2 | 0,05 (reflete) |
| folhas | 0,15 | 0,7 |
| água / lava | 0,3 / 0,5 | não reflete |
| madeira | 0,4 | 0,35 |
| terra, grama, areia, argila | 0,5 | 0,5 a 0,6 |
| pedra | 0,6 | 0,15 |
| ferro (máquinas do GT) | 0,75 | 0,08 |
| lã / tapete / esponja | 0,9 / 0,9 / 0,95 | 0,9 a 0,95 |
| plantas, trilhos, redstone, fogo, portal | 0 | não reflete |
| material desconhecido (sólido) | 0,5 | 0,3 |

**A forma ajusta a absorção do material:**
- laje × 0,5, escada × 0,75;
- cerca, muro, vidraça e grade × 0,25;
- tapete × 0,1;
- neve × camadas/8;
- porta fechada × 0,75 e aberta × 0,1;
- alçapão fechado × 0,5 e aberto × 0,1;
- portão × 0,25 / 0,05;
- blocos com modelo próprio (baús, canos) × 0,5.

O config aceita overrides por bloco (`acousticOverrides`, `modid:nome=absorção[,amortecimento]`). As entradas inválidas são ignoradas com aviso no log.

### Mundo e custo
- **Chunks:** os raios leem o mundo do cliente com o último chunk em cache, sem carregar nada (chunk que o cliente não tem é ar).
- **Altura:** y fora de 0..255 vira ar. No 1.7.10, `Chunk.getBlock` com y negativo **estoura o array e gera crash report**; isso foi achado lendo o código do jogo antes de escrever o adaptador.
- **Orçamento** (`OcclusionScheduler`, lógica pura e testada): até 8192 blocos visitados por tick, somando todos os emissores.
  - Cada valor vale 4 ticks, mais 1 a cada 16 blocos de distância, e vence na hora se o ouvinte andar mais de 1 bloco.
  - Emissores novos são calculados primeiro (até 4× o orçamento, para não haver um instante de som limpo atrás da parede).
  - Depois vêm os mais velhos, então ninguém passa fome. No E2E foram ~70 blocos por tick para as 2 fontes da rádio.

### Como a oclusão vira som

| Caminho | Com EFX | Sem EFX |
|---|---|---|
| direto | low-pass: ganho `1 − 0,7·o`, agudos `max(0,02; (1 − o)²)` | ganho `1 − 0,85·o` |
| envio para o reverb | menos abafado: ganho `1 − 0,5·o`, agudos `1 − o` | não há reverb |

A oclusão é suavizada por voz (τ = 0,1 s), para que uma porta abrindo não estale. Uma voz nova já nasce com a oclusão certa.

## Reverb

- **Sondagem da sala (`RoomProbe`):**
  - uma vez por segundo, 16 raios a partir da cabeça do jogador, em espiral de Fibonacci girada a cada sondagem pelo ângulo áureo, até a primeira superfície refletora (máximo de 48 blocos);
  - o resultado é suavizado (~90% da mudança em 0,5 s).
- **Modelo (`RoomModel`, testado):**
  - **Fechamento:** fração dos raios que bateram. Campo aberto ≈ 0,5 (o chão devolve, o céu não); o reverb só começa acima disso.
  - **Decaimento pela fórmula de Sabine:** T60 = 0,161·V/(S·α), com V/S = r/3 tratando o espaço como esfera, ou seja T60 ≈ 0,054·r/α.
  - **Brilho** pelo amortecimento médio, e **primeiras reflexões** em 2·r/343 s.
- **EFX (`Efx`):**
  - **Objetos:** um filtro low-pass de rascunho, um efeito de reverb e um slot auxiliar próprio, ligado à **saída auxiliar 1** das fontes do mod (o Hodgepodge usa a 0 nas fontes dele).
  - **Um filtro para todas as vozes:** o EFX **copia** os parâmetros ao ligar um filtro numa fonte ou carregar um efeito num slot, então o mesmo filtro serve todas, ajustado voz a voz.
  - **Sem sala** (campo aberto ou reverb desligado), o efeito é descarregado do slot e o mixer não gasta nada.
- **Ciclo de vida:**
  - Os objetos EFX são criados quando algo toca.
  - **Ao parar:** são soltos 4 s depois que nada mais toca. A cauda do reverb termina de soar, em vez de ser cortada.
  - **Recarregamento do som:** saem junto com o contexto, sempre **depois** das fontes (o OpenAL Soft recusa apagar um slot ainda ligado a uma fonte).
  - **Erro de OpenAL com EFX ativo:** o EFX fica desligado naquela geração do contexto e a oclusão segue só pelo ganho. Melhor isso que ficar sem som.

## Config do cliente
- `enableOcclusion` (ligado): oclusão por blocos.
- `enableReverb` (ligado): reverb conforme o lugar.
- `acousticOverrides`: absorção (e amortecimento) por bloco.

## Verificação

**Testes unitários** (182 no total; 35 novos nesta fase):
- `OcclusionTracer`, numa grade sintética:
  - campo aberto, lã contra vidro, parede dupla, blocos do ouvinte e da fonte;
  - pontos presos no bloco da fonte;
  - transição monótona na quina, andar ao longo da parede sem saltos;
  - raio rasante, absorção total, diagonal pelas quinas;
  - entradas inválidas (NaN, infinito, direção nula), orçamento e pior caso dos 5 raios;
  - primeira superfície da sondagem.
- `OcclusionScheduler`:
  - novos na hora, reaproveitamento até vencer;
  - ouvinte que andou, longe recalcula mais devagar;
  - orçamento respeitado e ninguém passa fome;
  - novos além do limite ficam para o próximo tick;
  - repetidos, entradas esquecidas.
- `RoomModel`: campo aberto, sala pequena de pedra (Sabine exato), fechamento parcial, caverna, lã contra pedra contra ferro, faixas do EFX sempre respeitadas, direções unitárias e metade para baixo.
- `AcousticOverrides`: entradas válidas, 14 tipos de entrada inválida, repetidas.

**E2E em jogo** (`tools/e2e/run-all.sh`):
- **Montagem:** um corredor limpo ao sul da rádio, com o ouvinte a 7 blocos. Paredes de 1 bloco no meio, depois uma sala de pedra fechada em volta do ouvinte. O servidor de teste ganhou `e2e:fill`, porque o 1.7.10 não tem `/fill`.
- **Medidas:** oclusão por voz, ganho aplicado e o reverb **lido de volta do OpenAL** (`AL_EFFECTSLOT_GAIN`, `AL_REVERB_DECAY_TIME`).

| Passo | Java 21 + lwjgl3ify + Hodgepodge | Java 8 (LWJGL2) |
|---|---|---|
| Parede de lã | oclusão **0,903** (teoria: 1 − 0,1^1,013 = 0,903) | **0,903** |
| Parede de vidro | oclusão **0,101** | **0,101** |
| Sem parede | oclusão **0** | **0** |
| Ganho aplicado com EFX | inalterado (o abafado é do low-pass) | idem |
| Sala de pedra (rádio do lado de fora) | sondagem: wet 0,74, decaimento 0,77 s, brilho 1,22; **lido do OpenAL: slot 0,738, decaimento 0,76 s**, saída auxiliar 1 de 4; oclusão da rádio 0,605 | idem com o OpenAL Soft 1.15.1: **slot 0,738, decaimento 0,76 s**, saída 1 de 4; oclusão 0,605 |
| Corredor aberto de novo | wet 0, efeito descarregado | wet 0, efeito descarregado |
| Recarregar o som tocando | EFX recriado (3 objetos), geração 3 → 5 | EFX recriado (3 objetos), geração 3 → 5 |
| Parar a rádio | objetos EFX soltos em **80 ticks** (4 s, a cauda) | **80 ticks** |
| Roteiro completo | **40/40 + 8/8** | **40/40 + 8/8** |

**Prova acústica** (`tools/e2e/run-acoustic.sh`): o OpenAL Soft do cliente grava a própria mixagem num WAV (backend `wave`), e `tools/e2e/analyze_acoustic.py` mede o que **sai de fato no som**.
- **Roteiro:** os segmentos são separados por 2 s de rádio muda: aberto, lã, vidro, aberto, sala de pedra (parando a rádio lá dentro) e parada no aberto.
- **Medidas:**
  - "agudos" = energia acima de 4 kHz (passa-altas Butterworth de 2ª ordem);
  - "cauda" = energia de 30 a 330 ms depois do corte em relação aos 500 ms antes.

Diferenças contra o corredor aberto (média dos segmentos A e D: −28,3 dBFS, agudos −42,4 dBFS):

| Segmento (Java 21) | Nível | Agudos (> 4 kHz) | Teoria (low-pass do caminho direto) | Cauda |
|---|---|---|---|---|
| **lã** (oclusão 0,903) | **−9,3 dB** | **−34,1 dB** | ganho −8,6 dB, agudos 20·log₁₀(0,02) = −34 dB | |
| vidro (0,101) | −0,8 dB | −2,0 dB | −0,6 dB e −1,8 dB | |
| sala de pedra (rádio atrás de 1 bloco de pedra, 0,605, com reverb) | −3,6 dB | −12,7 dB | −4,8 dB e −16,1 dB, menos o que o reverb devolve | **−20,9 dB** (cauda audível) |
| parada no corredor aberto | | | | **−71 dB** (silêncio) |

No Java 8 (OpenAL Soft 1.15.1, que vem com o LWJGL2), contra o aberto (−23,6 dBFS, agudos −35,6 dBFS):

| Segmento (Java 8) | Nível | Agudos (> 4 kHz) | Cauda |
|---|---|---|---|
| **lã** | **−11,8 dB** | **−30,9 dB** | |
| vidro | −0,8 dB | −2,8 dB | |
| sala de pedra | −6,0 dB | −15,4 dB | **−32,9 dB** |
| parada no corredor aberto | | | silêncio digital |

O low-pass e o reverb do OpenAL Soft 1.15 são implementações antigas: o filtro corta um pouco menos acima de 4 kHz e a cauda do reverb é ~12 dB mais discreta que no 1.25. A diferença contra o aberto continua enorme (a fonte é apagada no corte, então a energia depois dele só pode ser o reverb). Por isso o analisador exige cauda acima de −45 dB na sala e abaixo de −60 dB no aberto.

**Caminho sem EFX** (`AKASHICFM_E2E_NO_EFX=1`, como no macOS com Java 8):
- **Roteiro: 40/40 + 8/8**, sem nenhum objeto EFX criado (nem depois do recarregamento do som).
- **A oclusão vira só ganho, exatamente o calculado:**
  - lã: razão 0,232 (esperado 1 − 0,85·0,903 = 0,232);
  - vidro: 0,914 (esperado 0,914).
- **No áudio gravado:**
  - a lã baixa o nível em 13,4 dB (teoria −12,7 dB) **sem** mudar o equilíbrio dos agudos (+1,2 dB, nada de low-pass);
  - a sala de pedra não tem cauda (−64 dB).

**Soak de 10 min com oclusão e reverb ativos** (`tools/e2e/run-soak.sh`):
- **Carga:** 19 trocas de caixa e 4 recarregamentos do som.
- **Resultado:** **0 violações** em 36 amostras, agora também da invariante "3 objetos EFX enquanto toca"; **0 underruns**; heap 144 → 147 MB.

**Regressão do modo direto** (`AKASHICFM_E2E_TRANSPORT=direct`): **37/37 + 7/7**, com os passos de acústica.

As rodadas finais (Java 21, Java 8, sem EFX, direto, soak e prova acústica) foram feitas com o mesmo jar do commit.

## Revisão adversarial (corrigido antes do commit)
- O EFX era solto no mesmo frame em que a última rádio parava, o que cortava a cauda do reverb. Agora espera 4 s.
- **Falha de EFX ficava permanente:** a flag de falha não voltava a zero quando o contexto era trocado pelo fallback sem o gancho. Agora a falha vale só para a geração em que aconteceu.
- O contador de objetos EFX podia ser decrementado duas vezes numa falha parcial ao apagar. Agora cada id conta exatamente uma vez.
