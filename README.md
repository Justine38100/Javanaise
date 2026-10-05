# Javanaise

## Sommaire

- [Architecture](#architecture)
- [Compilation](#compilation)
- [Lancement Manuel](#lancement-manuel)
- [Tests](#tests)
- [Tests E2E](#tests-e2e)
- [Remarque Sur Maven](#remarque-sur-maven)

## Architecture

Le projet est organisé par responsabilité :

- `src/jvn/core` : coeur Javanaise version 1, avec les objets partagés, le serveur local, le coordinateur et les interfaces RMI.
- `src/jvn/proxy` : version 2 transparente avec proxy dynamique Java.
- `src/jvn/annotations` : annotations `@JvnRead` et `@JvnWrite` utilisées par le proxy.
- `src/irc/model` : modèle métier de l'application IRC (`Sentence`, `SentenceItf`).
- `src/irc/version1` : application IRC version 1, avec verrous explicites.
- `src/irc/version2` : application IRC version 2, avec proxy transparent.

Le package `irc` est gardé parce qu'il décrit l'application exemple. Le renommer en `main` serait moins précis : `main` indique seulement qu'il y a un point d'entrée, pas le domaine fonctionnel.

## Compilation

Depuis la racine du projet :

```bash
mkdir -p bin
javac -d bin $(find src -name '*.java' | sort)
```

## Lancement Manuel

Arrêter les anciens processus RMI / Javanaise :

```bash
pkill -f rmiregistry
pkill -f 'jvn.core.JvnCoordImpl'
pkill -f 'irc.version1.Irc'
pkill -f 'irc.version2.Irc2'
```

Nettoyer puis recompiler :

```bash
rm -rf bin
mkdir -p bin
javac -d bin $(find src -name '*.java' | sort)
```

Lancer le registre RMI :

```bash
rmiregistry -J-Djava.class.path=bin &
```

Lancer le coordinateur :

```bash
java -cp bin jvn.core.JvnCoordImpl
```

Dans un autre terminal, lancer un client IRC version 1 :

```bash
java -cp bin irc.version1.Irc
```

Ou lancer un client IRC version 2 avec proxy dynamique :

```bash
java -cp bin irc.version2.Irc2
```

Pour tester le partage, lancer un deuxième client dans un autre terminal.

## Tests

Les tests utilisent des `main` Java et des `assert`. Il faut donc toujours lancer les tests avec `-ea` pour activer les assertions.

Compiler les sources et tous les tests :

```bash
rm -rf bin
mkdir -p bin
javac -d bin $(find src test -name '*.java' | sort)
```

Lancer le test de la machine à états de `JvnObjectImpl` :

```bash
java -ea -cp bin jvn.units.JvnObjectImplTest
```

Lancer le test du coordinateur avec serveurs factices :

```bash
java -ea -cp bin jvn.units.JvnCoordImplTest
```

Lancer le test d'intégration dans une seule JVM avec vrai registre RMI, vrai coordinateur et vrai serveur local :

```bash
java -ea -cp bin jvn.integration.JvnIntegrationTest
```

Lancer tous les tests principaux :

```bash
rm -rf bin
mkdir -p bin
javac -d bin $(find src test -name '*.java' | sort)
java -ea -cp bin jvn.units.JvnObjectImplTest
java -ea -cp bin jvn.units.JvnCoordImplTest
java -ea -cp bin jvn.integration.JvnIntegrationTest
java -ea -cp bin jvn.e2e.JvnMultiJvmIntegrationTest
```

## Tests E2E

Le test e2e multi-JVM démarre `rmiregistry`, le coordinateur, deux clients réels, puis un test de charge à 2 clients x 1000 messages :

```bash
java -ea -cp bin jvn.e2e.JvnMultiJvmIntegrationTest
```

Rôle des fichiers e2e :

- `test/jvn/e2e/JvnMultiJvmIntegrationTest.java` : chef d'orchestre qui lance les processus Java.
- `test/jvn/e2e/utils/model/SharedText.java` : objet partagé utilisé par les tests.
- `test/jvn/e2e/utils/onejvm/E2EWriterClient.java` : écrit une valeur simple.
- `test/jvn/e2e/utils/onejvm/E2EReaderClient.java` : lit et vérifie cette valeur.
- `test/jvn/e2e/utils/multijvm/E2ELoadCreatorClient.java` : crée l'objet du test de charge.
- `test/jvn/e2e/utils/multijvm/E2ELoadAppenderClient.java` : ajoute 1000 messages.
- `test/jvn/e2e/utils/multijvm/E2ELoadVerifierClient.java` : vérifie les 2000 messages.