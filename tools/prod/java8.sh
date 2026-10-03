# Acha um Java 8 (o instalador do Forge 1.7.10 e o teste de produção precisam dele): $JAVA8, ou o que o Gradle
# baixou para as tarefas runServer/runClient.
if [[ -z ${JAVA8:-} ]]; then
  for j in "$HOME"/.gradle/jdks/*8*/bin/java "$HOME"/.gradle/jdks/*8*/jre/bin/java; do
    [[ -x $j ]] && "$j" -version 2>&1 | grep -q 'version "1\.8' && { JAVA8=$j; break; }
  done
fi
[[ -n ${JAVA8:-} ]] || { echo "Java 8 não encontrado: defina JAVA8=/caminho/bin/java" >&2; exit 1; }
