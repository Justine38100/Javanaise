package irc.version3;

import irc.model.Paragraph;
import irc.model.ParagraphItf;
import irc.model.Sentence;
import irc.model.SentenceItf;
import irc.ui.IrcWindow2;
import jvn.core.JvnEventBus;
import jvn.core.JvnServerImpl;
import jvn.core.JvnObject;
import jvn.proxy.JvnProxy;

public class Irc3 {

    private final SentenceItf sentence;
    private final ParagraphItf paragraph;
    private final IrcWindow2 window;

    public static void main(String argv[]) {
        try {
            // Objets partagés globaux (Sentence commune et Paragraph commun)
            SentenceItf sentence = (SentenceItf) JvnProxy.newInstance(new Sentence(), "IRC_SENTENCE");
            ParagraphItf paragraph = (ParagraphItf) JvnProxy.newInstance(new Paragraph(), "IRC_PARAGRAPH");

            new Irc3(sentence, paragraph);
        } catch (Exception e) {
            System.out.println("Irc2 problem : " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Irc3(SentenceItf sentence, ParagraphItf paragraph) {
        this.sentence = sentence;
        this.paragraph = paragraph;

        this.window = new IrcWindow2(
                "IRC Javanaise - Objets composites",
                this::readSentence,
                this::writeSentence,
                this::readParagraph,
                this::addToParagraph,
                this::removeLastSentenceFromParagraph
        );

        JvnEventBus.addListener(window::appendFailureEvent);
        window.appendFailureEvent("Client connecté au coordinateur");
    }

    // --- Actions sur la Sentence simple ---

    private void readSentence() {
        try {
            String value = sentence.read();
            window.setInputText(value);
            window.appendMessage("[Sentence] " + value);
        } catch (Exception e) {
            window.appendFailureEvent("Lecture sentence impossible : " + e.getMessage());
        }
    }

    private void writeSentence() {
        try {
            String value = window.getInputText();
            sentence.write(value);
            window.appendMessage("[Sentence modifiée] " + value);
        } catch (Exception e) {
            window.appendFailureEvent("Ecriture sentence impossible : " + e.getMessage());
        }
    }

    // --- Actions sur le Paragraph composite ---

    private void readParagraph() {
        try {
            String text = paragraph.read();
            window.appendParagraphMessage(text.isEmpty() ? "(Paragraphe vide)" : text);
        } catch (Exception e) {
            window.appendFailureEvent("Lecture paragraphe impossible : " + e.getMessage());
        }
    }

    private void removeLastSentenceFromParagraph() {
        try {
            paragraph.removeLastSentence();
            window.appendParagraphMessage("Dernière phrase supprimée du paragraphe.");
        } catch (Exception e) {
            window.appendFailureEvent("Suppression de la dernière phrase impossible : " + e.getMessage());
        }
    }

    private void addToParagraph() {
        try {
            String text = window.getInputText();
            if (text == null || text.trim().isEmpty()) {
                return;
            }

            // Création du proxy associé
            SentenceItf newSentenceProxy = (SentenceItf) JvnProxy.newInstance(
                    new Sentence(),
                    "IRC_SENTENCE_" + System.currentTimeMillis()
            );

            // On écrit le texte et on l'ajoute au paragraphe
            newSentenceProxy.write(text);
            paragraph.addSentence(newSentenceProxy);

            window.appendParagraphMessage("Ajouté : \"" + text + "\"");
            window.setInputText("");
        } catch (Exception e) {
            window.appendFailureEvent("Ajout au paragraphe impossible : " + e.getMessage());
        }
    }
}