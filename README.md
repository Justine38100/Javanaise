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
