# Fase 0: resultados dos spikes

Data: 2026-10-01. Ambiente: VM de nuvem com 4 vCPU e OpenJDK 21. Os spikes foram compilados com `--release 8`, ou seja, em bytecode Java 8, o mesmo do 1.7.10.

## 0b: custo do Opus (Concentus 1.0.2, Java puro)

O Concentus é bytecode Java 8 (major 52), compatível com o 1.7.10. O teste usou 60 s de sinal sintético "musical", 48 kHz estéreo, frames de 20 ms:

| Bitrate | Encode (servidor) | Decode (cliente) | Banda por ouvinte |
|---|---|---|---|
| 64 kbps | ~1,0% de 1 núcleo por estação | ~0,37% de 1 núcleo | ~8 KB/s |
| 96 kbps | ~0,95% de 1 núcleo por estação | ~0,38% de 1 núcleo | ~12 KB/s |

**Conclusão:** a transcodificação para Opus é barata. Não é preciso libopus nativo, e o fallback de "repassar os frames sem transcodificar" não é necessário.

## 0c: pipeline do relay contra rádios reais

O caminho testado foi: `URL → IcyHttpClient → detecção de formato → decoder → 48 kHz estéreo → Opus 20 ms`.

**9 de 9 rádios funcionaram** (45 s cada, em paralelo):

| Rádio | Formato | Entrada | Observação |
|---|---|---|---|
| Radio Paradise mp3-128 | MP3 | 44,1 kHz | StreamTitle lido |
| Radio Paradise aac-128 | AAC | 44,1 kHz | StreamTitle lido |
| FIP midfi | MP3 | **48 kHz** | O OpenFM (que força 44,1 kHz) tende a falhar aqui |
| FIP hifi | AAC | 48 kHz | |
| I Love Radio | MP3 | 44,1 kHz | Redirect 302 com token; StreamTitle lido |
| Swiss Jazz (aacp_96) | HE-AAC | 48 kHz | 2 redirects, inclusive https→http; StreamTitle lido |
| Radio 1 (global.audio) | OGG Vorbis | 44,1 kHz | Streams encadeados |
| Radio 1 (global.audio) | OGG Opus | 48 kHz | 3 streams encadeados em 20 s |
| Dancewave | OGG Vorbis | 44,1 kHz | Porta 8080 |

- **CPU por estação** (decode + resample + Opus): 1,9% a 4,2% de 1 núcleo nos primeiros 45 s, aquecimento do JIT incluído. Em regime (90 s), ficou em **~2,1% a 2,4%**.
- **Tempo até o primeiro áudio:** 0,7 s a 3,3 s, dominado pelo TLS e pelos redirects.
- **Burst inicial:** os servidores entregam ~5 a 10 s de áudio adiantado na conexão, o que é suficiente para encher o jitter buffer de cara.

### Bug encontrado e corrigido no caminho
O `StreamState.init()` do JOrbis, quando reaproveitado, mantém pacotes e o número de página do stream lógico anterior. Na troca de música, quando o Icecast abre um stream OGG novo, o primeiro cabeçalho é lido fora de ordem e o decoder morre.

**Correção:** uma instância nova de `StreamState` por stream lógico, mais o tratamento de `packetout() == -1` (buraco) sem abandonar a página.

O teste `OggResyncTest` monta um OGG sintético com:
- um stream normal;
- um stream sem cabeçalhos;
- um stream com cabeçalho corrompido;
- outro stream normal.

O resultado é 1,99 s decodificados e 2 ressincronizações, ou seja, os dois streams ruins foram pulados e o último tocou.

### Política de URL (anti-SSRF)
O `PolicyTest` recusa os 17 casos maliciosos e aceita os 3 legítimos. Os maliciosos incluem:
- loopback e `localhost`;
- `169.254.169.254` (metadata da nuvem);
- 10/8, 172.16/12, 192.168/16 e 100.64/10;
- `[::1]`, `[fd00::1]` e `[::ffff:127.0.0.1]`;
- `2130706433` (127.0.0.1 em decimal) e `0.0.0.0`;
- os esquemas `file:` e `ftp:`;
- credenciais na URL, a porta 22 e `.local`.

A allowlist recusa domínio parecido (`evil-radioparadise.com`) e aceita subdomínio.

## Build e smoke test do mod
- **Build:** `./gradlew build` passa (spotless, checkstyle, compilação, **39 testes JUnit sem rede** e jar reobfuscado).
- **Jar:** 1,3 MB. Os 4 codecs estão relocados em `com.akashiic.fm.shadow.*`, nenhuma classe do mod referencia os pacotes originais e não há `META-INF/services` (conferido com `javap`). A tabela `sfd.ser` do JLayer está no jar.
- **Servidor:** `./gradlew runServer21` (Java 21 + lwjgl3ify 3.0.33 + Hodgepodge 2.7.206 + GTNHLib 0.11.52 + UniMixins) sobe limpo: `AkashicFM ... carregado (relay=true, direto=false)` e `Done (0.659s)!`.
- **Config:** o `config/akashicfm.cfg` é gerado com as 4 categorias, os comentários em pt-BR e os limites de cada opção.
- **Nota do ambiente:** o Maven Central limitou (HTTP 429) o IP do container algumas vezes. Com retries, o build passou.

## 0a: OpenAL posicional + EFX dentro do cliente

A sonda é `com.akashiic.fm.client.audio.AlCapabilityProbe` e roda dentro do contexto de áudio do próprio Minecraft quando `AKASHICFM_PROBE_AUDIO=1`. Os clientes de dev rodaram sob Xvfb, com Mesa por software e OpenAL Soft no backend `null` (`ALSOFT_DRIVERS=null`).

| | Java 21 + lwjgl3ify 3.0.33 + Hodgepodge | Java 8 + LWJGL 2.9.4 |
|---|---|---|
| OpenAL | OpenAL Soft 1.25.2 | OpenAL Soft 1.15.1 (vem com o LWJGL2) |
| Fonte com `AL_POSITION` em coordenadas do mundo | OK | OK |
| Streaming mono 48 kHz em fila | OK (`AL_PLAYING`) | OK (`AL_PLAYING`) |
| `ALC_EXT_EFX` | sim, 4 sends auxiliares | sim, 4 sends auxiliares |
| Low-pass na fonte (oclusão) | OK | OK |
| Reverb em aux slot + send | OK | OK |
| Fontes livres para o mod | 184 | 224 |

**Conclusão:** oclusão com low-pass e reverb EFX funcionam nos dois caminhos, então o fallback "só ganho" fica para casos raros. O limite de 24 fontes do `SourcePool` tem folga grande.

### Achados que mudam detalhes do plano
- **O sound system reinicia durante o startup** (o paulscode faz "shutting down / starting up" uma vez no carregamento). As fontes do mod precisam ser recriadas quando o contexto AL muda; os hooks de reload do `SoundManager` são obrigatórios, não opcionais.
- **O Hodgepodge instala a própria biblioteca de som OpenAL** e loga "OpenAL source routing: stereo spatialization, direct UI/music channels, mono panning". A engine do mod usa fontes próprias e não passa pelo paulscode, mas precisa ser testada junto com essa biblioteca. A curva de volume ao quadrado (`LOGARITHMIC_VOLUME_CONTROL`) continua valendo.
- **HRTF:** o lwjgl3ify já tem `openalcontext.enableHRTF` no `config/lwjgl3ify.cfg`. O mod não precisa ligar HRTF por código; basta documentar a opção.
- **Categoria de som própria:** o lwjgl3ify torna `net.minecraft.client.audio.SoundCategory` extensível (`config/lwjgl3ify-early.json`). Dá para ter um slider "Rádio" separado do de Jukebox, pelo menos no caminho lwjgl3ify.
- **Riscos que ficam:**
  - **macOS com Java 8:** o LWJGL2 usa o OpenAL da Apple, que não tem EFX. É o caso do fallback só com ganho.
  - **Dispositivos de áudio reais:** podem ter um teto de fontes diferente do backend `null`. A contagem é feita em runtime de qualquer forma.

### Ambiente de teste sem tela nem placa de som
Para rodar o cliente headless foi preciso instalar `libegl1` e `libegl-mesa0` (o GLFW do lwjgl3ify usa EGL) e `x11-xserver-utils` (o LWJGL2 chama o `xrandr`). Os comandos foram:

```bash
AKASHICFM_PROBE_AUDIO=1 ALSOFT_DRIVERS=null LIBGL_ALWAYS_SOFTWARE=1 \
  xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient21   # ou runClient (Java 8)
```

## Notas para a implementação
- **JAAD:** usamos `de.sfuhrm:jaad:0.8.7`, que está no Maven Central, é domínio público e é bytecode Java 8. Os três streams AAC/HE-AAC foram revalidados com ele. Ele registra `META-INF/services/javax.sound.sampled.spi.AudioFileReader`; o `addon.gradle` **exclui** esse arquivo do jar para não sequestrar o Java Sound de outros mods.
- **Resampler:** o spike usa interpolação linear. A versão final usa windowed-sinc.
- **Ambiente do container:** não há acesso direto à internet, só um proxy HTTP CONNECT. Por isso o `IcyHttpClient` também aceita proxy, o que é útil para servidores atrás de proxy.

## Onde está o código
- `src/main/java/com/akashiic/fm/audio/http/`: `UrlPolicy`, `IcyHttpClient`, `IcyMetadataInputStream`, `ChunkedInputStream`.
- `src/main/java/com/akashiic/fm/audio/decode/`: `StreamFormat`, `PcmSource`, `Mp3Source`, `AacSource`, `OggSource`.
- `src/main/java/com/akashiic/fm/audio/dsp/`: `Resampler48k`.
- **Testes** (sem rede): `src/test/java/com/akashiic/fm/audio/...`.
- **Ferramentas manuais** (com rede):
  - `com.akashiic.fm.tools.RelayProbe <segundos> <url...>`: repete o teste 0c contra rádios reais.
  - `com.akashiic.fm.tools.OpusBench [segundos]`: repete o teste 0b.
