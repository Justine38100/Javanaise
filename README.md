# Javanaise

## Compilation

Depuis la racine du projet :

```bash
mkdir -p bin
javac -d bin src/jvn/*.java src/irc/*.java
```

## Nettoyer Et Relancer Proprement

Arreter les anciens processus RMI / Javanaise :

```bash
pkill -f rmiregistry
pkill -f 'jvn.JvnCoordImpl'
pkill -f 'irc.Irc'
```

Nettoyer puis recompiler :

```bash
rm -rf bin
mkdir -p bin
javac -d bin src/jvn/*.java src/irc/*.java
```

Lancer le registre RMI :

```bash
rmiregistry -J-Djava.class.path=bin &
```

Lancer le coordinateur :

```bash
java -cp bin jvn.JvnCoordImpl
```

Dans un autre terminal, lancer un client IRC :

```bash
java -cp bin irc.Irc
```

Pour tester le partage, lancer un deuxieme client IRC dans un autre terminal :

```bash
java -cp bin irc.Irc
```

## Tests

Les tests utilisent des `main` Java et des `assert`. Il faut donc toujours lancer les tests avec `-ea` pour activer les assertions.

Compiler les sources et tous les tests :

```bash
javac -d bin src/jvn/*.java src/irc/*.java test/jvn/*.java test/jvn/e2e/*.java
```

Lancer le test de la machine à etats de `JvnObjectImpl` :

```bash
java -ea -cp bin jvn.JvnObjectImplTest
```

Lancer le test du coordinateur avec serveurs factices :

```bash
java -ea -cp bin jvn.JvnCoordImplTest
```

Lancer le test d'integration dans une seule JVM avec vrai registre RMI, vrai coordinateur et vrai serveur local :

```bash
java -ea -cp bin jvn.JvnIntegrationTest
```

Lancer le test e2e multi-JVM, qui démarre `rmiregistry`, le coordinateur, deux clients réels, puis le test de charge a 2 clients x 1000 messages :

```bash
java -ea -cp bin jvn.JvnMultiJvmIntegrationTest
```

Lancer tous les tests :

```bash
javac -d bin src/jvn/*.java src/irc/*.java test/jvn/*.java test/jvn/e2e/*.java
java -ea -cp bin jvn.JvnObjectImplTest
java -ea -cp bin jvn.JvnCoordImplTest
java -ea -cp bin jvn.JvnIntegrationTest
java -ea -cp bin jvn.JvnMultiJvmIntegrationTest
```

Dans les tests d'E2E :

JvnMultiJvmIntegrationTest = chef d’orchestre
SharedText = objet partagé
E2EWriterClient = écrit une valeur simple
E2EReaderClient = lit et vérifie cette valeur
E2ELoadCreatorClient = crée l’objet de charge
E2ELoadAppenderClient = ajoute 1000 messages
E2ELoadVerifierClient = vérifie les 2000 messages
