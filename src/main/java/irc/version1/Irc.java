package irc.version1;

import java.io.Serializable;

import irc.model.Sentence;
import irc.ui.IrcWindow;
import jvn.core.JvnEventBus;
import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;

/**
 * Application IRC version 1 : utilisation explicite des verrous Javanaise.
 */
public class Irc {

    private final JvnObject sentence;
    private final IrcWindow window;

    public static void main(String argv[]) {
        try {
            JvnServerImpl server = JvnServerImpl.jvnGetServer();
            JvnObject object = server.jvnLookupObject("IRC");

            if (object == null) {
                object = server.jvnCreateObject((Serializable) new Sentence());
                object.jvnUnLock();
                server.jvnRegisterObject("IRC", object);
            }

            new Irc(object);
        } catch (Exception e) {
            System.out.println("IRC problem : " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Irc(JvnObject sentence) {
        this.sentence = sentence;
        this.window = new IrcWindow("IRC Javanaise - verrous explicites", this::read, this::write);
        JvnEventBus.addListener(window::appendFailureEvent);
        window.appendFailureEvent("client connecté au coordinateur");
    }

    private void read() {
        try {
            sentence.jvnLockRead();
            String value = ((Sentence) sentence.jvnGetObjectState()).read();
            sentence.jvnUnLock();

            window.setInputText(value);
            window.appendMessage(value);
        } catch (Exception e) {
            window.appendFailureEvent("lecture impossible : " + e.getMessage());
        }
    }

    private void write() {
        try {
            String value = window.getInputText();

            sentence.jvnLockWrite();
            ((Sentence) sentence.jvnGetObjectState()).write(value);
            sentence.jvnUnLock();

            window.appendMessage(value);
        } catch (Exception e) {
            window.appendFailureEvent("écriture impossible : " + e.getMessage());
        }
    }
}
