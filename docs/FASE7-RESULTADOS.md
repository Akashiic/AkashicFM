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
