package irc.version2;

import irc.model.Sentence;
import irc.model.SentenceItf;
import irc.ui.IrcWindow;
import jvn.core.JvnEventBus;
import jvn.proxy.JvnProxy;

/**
 * Application IRC version 2 : utilisation transparente avec proxy dynamique.
 */
public class Irc2 {

    private final SentenceItf sentence;
    private final IrcWindow window;

    public static void main(String argv[]) {
        try {
            SentenceItf sentence = (SentenceItf) JvnProxy.newInstance(new Sentence(), "IRC");
            new Irc2(sentence);
        } catch (Exception e) {
            System.out.println("Irc2 problem : " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Irc2(SentenceItf sentence) {
        this.sentence = sentence;
        this.window = new IrcWindow("IRC Javanaise - proxy", this::read, this::write);
        JvnEventBus.addListener(window::appendFailureEvent);
        window.appendFailureEvent("client connecté au coordinateur");
    }

    private void read() {
        try {
            String value = sentence.read();
            window.setInputText(value);
            window.appendMessage(value);
        } catch (Exception e) {
            window.appendFailureEvent("lecture impossible : " + e.getMessage());
        }
    }

    private void write() {
        try {
            String value = window.getInputText();
            sentence.write(value);
            window.appendMessage(value);
        } catch (Exception e) {
            window.appendFailureEvent("écriture impossible : " + e.getMessage());
        }
    }
}
