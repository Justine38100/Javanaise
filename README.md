# Javanaise

## Sommaire

- [Architecture](#architecture)
- [Maven](#maven)
- [Compilation](#compilation)
- [Lancement Manuel](#lancement-manuel)
- [Tests](#tests)
- [Tests E2E](#tests-e2e)

## Architecture

Le projet suit une arborescence Maven standard :

```text
src/main/java/      code applicatif
src/test/java/      tests JUnit 5
pom.xml
```

Organisation du code applicatif :

- `src/main/java/jvn/core` : coeur Javanaise version 1, avec les objets partagés, le serveur local, le coordinateur et les interfaces RMI.
- `src/main/java/jvn/proxy` : version 2 transparente avec proxy dynamique Java.
- `src/main/java/jvn/annotations` : annotations `@JvnRead` et `@JvnWrite` utilisees par le proxy.
- `src/main/java/irc/model` : modèle métier de l'application IRC (`Sentence`, `SentenceItf`).
- `src/main/java/irc/version1` : application IRC version 1, avec verrous explicites.
- `src/main/java/irc/version2` : application IRC version 2, avec proxy transparent.

## Maven

Le projet contient un `pom.xml` minimal :

- `groupId` : `fr.m2.sar`
- `artifactId` : `javanaise`
- `version` : `1.0-SNAPSHOT`
- compilation Java : 17

Le projet utilise JUnit 5 pour les tests. Maven compile le code, compile les tests, puis lance automatiquement les classes de test avec le plugin Surefire.

## Compilation

Compiler les sources applicatives :

```bash
mvn compile
```

Compiler les sources et les tests :

```bash
mvn test-compile
```

Nettoyer les fichiers générés par Maven :

```bash
mvn clean
```

Les classes compilées sont générées dans :

```text
target/classes
target/test-classes
```

## Lancement Manuel

Arrêter les anciens processus RMI / Javanaise :

```bash
pkill -f rmiregistry
pkill -f 'jvn.core.JvnCoordImpl'
pkill -f 'irc.version1.Irc'
pkill -f 'irc.version2.Irc2'
```

Compiler :

```bash
mvn compile
```

Lancer le registre RMI :

```bash
rmiregistry -J-Djava.class.path=target/classes &
```

Lancer le coordinateur :

```bash
java -cp target/classes jvn.core.JvnCoordImpl
```

Dans un autre terminal, lancer un client IRC version 1 :

```bash
java -cp target/classes irc.version1.Irc
```

Ou lancer un client IRC version 2 avec proxy dynamique :

```bash
java -cp target/classes irc.version2.Irc2
```

Pour tester le partage, lancer un deuxieme client dans un autre terminal.

## Tests

Les tests utilisent JUnit 5.

Lancer tous les tests :

```bash
mvn test
```

Lancer uniquement le test de la machine à états de `JvnObjectImpl` :

```bash
mvn -Dtest=JvnObjectImplTest test
```

Lancer uniquement le test du coordinateur avec serveurs factices :

```bash
mvn -Dtest=JvnCoordImplTest test
```

Lancer uniquement le test d'integration dans une seule JVM avec vrai registre RMI, vrai coordinateur et vrai serveur local :

```bash
mvn -Dtest=JvnIntegrationTest test
```

Lancer uniquement les tests e2e multi-JVM :

```bash
mvn -Dtest=JvnMultiJvmIntegrationTest test
```

## Tests E2E

Les tests e2e multi-JVM lancent de vrais processus Java. Ils démarrent un vrai `rmiregistry`, un vrai coordinateur RMI et de vrais clients Javanaise (les entitées sont non mockées).

Ils permettent de vérifier :

- création et lecture d'un objet partagé ;
- écriture concurrente depuis deux clients ;
- test de charge avec 2 clients x 1000 messages ;
- panne d'un client qui possède un verrou en écriture ;
- panne d'un client proxy après une écriture ;
- panne et redémarrage du coordinateur avec restauration depuis le snapshot.

Commande :

```bash
mvn -Dtest=JvnMultiJvmIntegrationTest test
```

Scénarios testés dans `JvnMultiJvmIntegrationTest` :

- `twoClientsShareOneObject` : un client écrit une valeur dans un objet partagé, puis un autre client lit cette valeur. Le test vérifie que deux JVM différentes voient bien le même objet Javanaise.
- `twoClientsAppendMessagesWithoutLoss` : deux clients ajoutent chacun 1000 messages dans le même objet partagé. Le vérificateur relit ensuite l'objet et contrôle que les 2000 messages sont présents.
- `deadWriterDoesNotBlockFutureWriter` : un client devient writer puis s'arrête brutalement sans appeler `jvnTerminate`. Un second client demande ensuite un verrou écriture. Le résultat attendu est que le coordinateur détecte la panne avec une `RemoteException`, retire le writer mort, puis donne le verrou au second client.
- `proxyWriteSurvivesClientCrash` : un client écrit avec la version proxy, puis tombe juste après. Le test vérifie qu'un autre client retrouve quand même la valeur, car le proxy a sauvegardé l'état auprès du coordinateur après l'appel `@JvnWrite`.
- `coordinatorRestartReloadsSnapshotAndClientReconnects` : le coordinateur est tué puis relancé avec le même fichier snapshot. Le client force ensuite une reconnexion et vérifie que l'objet et son état sont restaurés.

Rôle des fichiers e2e :

- `src/test/java/jvn/e2e/JvnMultiJvmIntegrationTest.java` : chef d'orchestre qui lance les processus Java.
- `src/test/java/jvn/e2e/utils/model/SharedText.java` : objet partagé utilisé par les tests.
- `src/test/java/jvn/e2e/utils/onejvm/E2EWriterClient.java` : écrit une valeur simple.
- `src/test/java/jvn/e2e/utils/onejvm/E2EReaderClient.java` : lit et vérifie cette valeur.
- `src/test/java/jvn/e2e/utils/multijvm/E2ELoadCreatorClient.java` : crée l'objet du test de charge.
- `src/test/java/jvn/e2e/utils/multijvm/E2ELoadAppenderClient.java` : ajoute 1000 messages.
- `src/test/java/jvn/e2e/utils/multijvm/E2ELoadVerifierClient.java` : vérifie les 2000 messages.
- `src/test/java/jvn/e2e/utils/failures/E2EDeadWriterClient.java` : crée un objet, devient writer, puis s'arrête brutalement.
- `src/test/java/jvn/e2e/utils/failures/E2EWriteAfterDeadWriterClient.java` : vérifie qu'un nouveau writer peut écrire après la panne du writer précédent.
- `src/test/java/jvn/e2e/utils/failures/E2EProxyWriteAndCrashClient.java` : écrit via proxy, sauvegarde l'état, puis simule une panne client brutale.
- `src/test/java/jvn/e2e/utils/failures/E2EProxySavedStateReaderClient.java` : lit l'état sauvegardé par le proxy après la panne du writer.
- `src/test/java/jvn/e2e/utils/failures/E2ECoordinatorRecoveryClient.java` : écrit une valeur, attend le redémarrage du coordinateur, puis vérifie la reconnexion et la restauration de l'état.
