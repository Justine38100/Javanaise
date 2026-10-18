## 1. Préparation & Démarrage

### Étape 1 : Compilation
Dans un terminal à la racine du projet :
```bash
mvn compile
```

### Étape 2 : Nettoyage d'un ancien snapshot (recommandé pour partir d'un état propre)
**Linux / macOS** :
  ```bash
  rm -f jvn-coordinator.snapshot
  ```

### Étape 3 : Démarrer le Coordinateur JVN
Dans un terminal dédié (Terminal 1) :
```bash
java -cp target/classes jvn.core.JvnCoordImpl
```
> Le coordinateur démarre le registre RMI local (port 1099) et attend les connexions.

---

## 2. Scénarios Fonctionnels sur `main` (`Irc2`)

Ces scénarios utilisent `irc.version2.Irc2` (proxy transparent avec annotations `@JvnRead` / `@JvnWrite`).

### Scénario 1 : Partage et propagation immédiate (2 fenêtres)
* **Objectif** : Vérifier que deux clients connectés partagent le même objet en temps réel.
* **Mise en place** :
  1. Ouvrir le Terminal 2 : `java -cp target/classes irc.version2.Irc2` (Fenêtre Alice).
  2. Ouvrir le Terminal 3 : `java -cp target/classes irc.version2.Irc2` (Fenêtre Bob).
* **Actions IHM** :
  1. Dans la fenêtre d'**Alice**, taper dans le champ de saisie :
     ```text
     Salut Bob, tu es bien connecté ?
     ```
  2. Cliquer sur le bouton **[Ecrire]**.
     * *Observation* : Le texte s'affiche en bleu dans la console d'Alice.
  3. Dans la fenêtre de **Bob**, cliquer sur le bouton **[Lire]**.
* **Résultat attendu** :
  * Le message apparaît en bleu dans la fenêtre de Bob : `"Salut Bob, tu es bien connecté ?"`.

---

### Scénario 2 : Invalidation de cache et alternance d'écriture
* **Objectif** : Vérifier que lorsqu'un client écrit, le cache local de l'autre client est automatiquement invalidé par le coordinateur.
* **Actions IHM** :
  1. Dans la fenêtre de **Bob**, taper :
     ```text
     Oui bien reçu Alice ! Tu viens déjeuner ?
     ```
  2. Cliquer sur **[Ecrire]**.
  3. Dans la fenêtre d'**Alice**, cliquer sur **[Lire]**.
* **Résultat attendu** :
  * Alice reçoit immédiatement la réponse de Bob : `"Oui bien reçu Alice ! Tu viens déjeuner ?"`.
  * Le coordinateur a invalidé le cache d'Alice lors de l'écriture de Bob, garantissant une cohérence stricte sans stale read.

---

## 3. Scénarios de Gestion des Erreurs & Pannes sur `main` (`Irc2`)

Toutes les erreurs et événements de diagnostic sont capturés par le bus d'événements (`JvnEventBus`) et affichés en **rouge** (`[JVN] ...`) directement dans la console de l'IHM `IrcWindow` sans fermer l'application.

---

### Scénario 3 : Tentative de démarrage sans Coordinateur (Erreur initiale)
* **Objectif** : Vérifier le comportement lorsqu'un client démarre alors que le coordinateur est éteint.
* **Actions** :
  1. S'assurer qu'aucun coordinateur ne tourne (fermer le Terminal 1 s'il est ouvert).
  2. Tenter de lancer un client dans un terminal :
     ```bash
     java -cp target/classes irc.version2.Irc2
     ```
* **Résultat attendu** :
  * Le client ne démarre pas silencieusement dans le vide.
  * Il intercepte l'erreur de connexion RMI et affiche un message explicite dans le terminal :
    ```text
    Irc2 problem : Erreur de connexion au registre RMI... Connection refused to host: localhost
    ```

---

### Scénario 4 : Perte de connexion en cours de session (Coupure du Coordinateur)
* **Objectif** : Vérifier le comportement de l'IHM d'Alice lorsque le coordinateur tombe en cours d'utilisation.
* **Mise en place** :
  1. Relancer le coordinateur : `java -cp target/classes jvn.core.JvnCoordImpl` (Terminal 1).
  2. Lancer la fenêtre d'Alice : `java -cp target/classes irc.version2.Irc2` (Terminal 2).
  3. Dans la fenêtre d'Alice, taper `"Test avant panne"` et cliquer sur **[Ecrire]**.
* **Déclenchement de l'erreur** :
  4. Dans le Terminal 1 (coordinateur), faire **`Ctrl + C`** pour arrêter brutalement le coordinateur.
  5. Retourner sur la fenêtre d'Alice et cliquer sur **[Lire]** ou taper un mot et cliquer sur **[Ecrire]**.
* **Résultat attendu dans l'IHM d'Alice** :
  * La fenêtre **ne crash pas**.
  * Des alertes en rouge apparaissent dans l'IHM :
    ```text
    [JVN] coordinateur indisponible, tentative de reconnexion
    [JVN] reconnexion au coordinateur échouée : Connection refused to host...
    [JVN] lecture impossible : Erreur lors de la lecture...
    ```

---

### Scénario 5 : Guérison et reconnexion automatique après redémarrage du Coordinateur
* **Objectif** : Vérifier que le client `Irc2` laissé ouvert se reconnecte automatiquement dès que le coordinateur est relancé, tout en restaurant son état via le snapshot.
* **Déroulement** :
  1. Suite au scénario 4, **sans fermer la fenêtre d'Alice**, relancer le coordinateur dans le Terminal 1 :
     ```bash
     java -cp target/classes jvn.core.JvnCoordImpl
     ```
     *(Le coordinateur restaure l'objet à partir de `jvn-coordinator.snapshot`)*.
  2. Dans la fenêtre d'Alice, cliquer simplement sur **[Lire]**.
* **Résultat attendu dans l'IHM d'Alice** :
  * Le client réessaie de joindre le coordinateur et réussit :
    ```text
    [JVN] coordinateur indisponible, tentative de reconnexion
    [JVN] reconnexion au coordinateur réussie
    [JVN] verrous locaux remis à NL après redémarrage du coordinateur
    ```
  * L'état restauré (`"Test avant panne"`) s'affiche à nouveau en bleu dans l'IHM !

## 4. Scénarios Avancés sur la branche `feat/object-references` (`Irc3`)

Sur la branche `feat/object-references`, le nouveau client `irc.version3.Irc3` gère des **objets composites** : un objet partagé `Paragraph` contient une collection de références vers d'autres objets partagés autonomes `Sentence`.

L'IHM propose des boutons supplémentaires : `[Lire Para]`, `[Ajouter au Para]`, `[Modifier phrase (idx:txt)]`, `[Supprimer la dernière phrase]`.

---

### Scénario 6 : Discussion collaborative dans un Paragraphe
* **Objectif** : Construire une conversation collaborative où chaque phrase est un objet partagé autonome référencé dans le paragraphe.
* **Actions IHM** :
  1. Ouvrir deux fenêtres `Irc3` : Alice et Bob.
  2. Dans la fenêtre d'**Alice**, taper :
     ```text
     Salut, tu es dispo pour la réunion ?
     ```
     Puis cliquer sur **[Ajouter au Para]**.
     * *Observation* : message vert `[PARAGRAPHE] Ajouté : "Salut, tu es dispo pour la réunion ?"`.
  3. Dans la fenêtre de **Bob**, taper :
     ```text
     Oui, on se retrouve en salle B.
     ```
     Puis cliquer sur **[Ajouter au Para]**.
     * *Observation* : message vert `[PARAGRAPHE] Ajouté : "Oui, on se retrouve en salle B."`.
  4. Dans la fenêtre d'**Alice**, cliquer sur **[Lire Para]**.
* **Résultat attendu** :
  * Alice voit s'afficher l'ensemble de la discussion :
    ```text
    [PARAGRAPHE] Salut, tu es dispo pour la réunion ? Oui, on se retrouve en salle B.
    ```

---

### Scénario 7 : Granularité fine - Modification ciblée d'une phrase
* **Objectif** : Prouver qu'on peut modifier une sous-phrase sans verrouiller le paragraphe entier.
* **Actions IHM** :
  1. Dans la fenêtre de **Bob**, pour rectifier le lieu (phrase à l'index `1`), taper :
     ```text
     1:Finalement la salle B est prise, plutôt à la cafétéria ?
     ```
  2. Cliquer sur le bouton **[Modifier phrase (idx:txt)]**.
     * *Observation Bob* : `[PARAGRAPHE] Phrase [1] modifiée en : "Finalement la salle B est prise, plutôt à la cafétéria ?"`.
  3. Dans la fenêtre d'**Alice**, cliquer sur le bouton **[Lire Para]**.
* **Résultat attendu** :
  * Alice affiche la conversation à jour :
    ```text
    [PARAGRAPHE] Salut, tu es dispo pour la réunion ? Finalement la salle B est prise, plutôt à la cafétéria ?
    ```
  * Seul l'objet `Sentence` d'indice 1 a été verrouillé en écriture et invalidé auprès des autres clients.

---

### Scénario 8 : Suppression de la dernière phrase
* **Objectif** : Retirer une référence d'objet du composite.
* **Actions IHM** :
  1. Dans la fenêtre de **Bob**, cliquer sur **[Supprimer la dernière phrase]**.
     * *Observation Bob* : `[PARAGRAPHE] Dernière phrase supprimée du paragraphe.`.
  2. Dans la fenêtre d'**Alice**, cliquer sur **[Lire Para]**.
* **Résultat attendu** :
  * Alice voit désormais :
    ```text
    [PARAGRAPHE] Salut, tu es dispo pour la réunion ?
    ```
  * La dernière phrase a bien disparu de la liste sur tous les clients.