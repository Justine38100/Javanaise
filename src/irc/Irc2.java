/***
 * Irc2 class : simple implementation of a chat using JAVANAISE transparent proxy
 */

package irc;

import java.awt.*;
import java.awt.event.*;

import jvn.*;

public class Irc2 {
    public TextArea text;
    public TextField data;
    Frame frame;

	// sentence est l'objet partagé (proxy) que manipule l'utilisateur de l'application. Il est de type SentenceItf, qui est l'interface de l'objet partagé.
	SentenceItf sentence;

    public static void main(String argv[]) {
        try {
            // JvnProxy.newInstance fait le lookup/create/register Javanaise,
            // puis retourne un objet qui implemente SentenceItf.
            SentenceItf sentence = (SentenceItf) JvnProxy.newInstance(new Sentence(), "IRC");
            new Irc2(sentence);

        } catch (Exception e) {
            System.out.println("Irc2 problem : " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Irc2(SentenceItf sentence) {
        this.sentence = sentence;
        frame = new Frame();
        frame.setLayout(new GridLayout(1, 1));
        text = new TextArea(10, 60);
        text.setEditable(false);
        text.setForeground(Color.red);
        frame.add(text);
        data = new TextField(40);
        frame.add(data);

        Button readButton = new Button("read");
        readButton.addActionListener(new Irc2ReadListener(this));
        frame.add(readButton);

        Button writeButton = new Button("write");
        writeButton.addActionListener(new Irc2WriteListener(this));
        frame.add(writeButton);

        frame.setSize(545, 201);
        text.setBackground(Color.black);
        frame.setVisible(true);
    }
}

class Irc2ReadListener implements ActionListener {
    Irc2 irc;

    public Irc2ReadListener(Irc2 irc) {
        this.irc = irc;
    }

    public void actionPerformed(ActionEvent e) {
        try {
            // Version transparente : l'appel read() est intercepte par JvnProxy.
            // Le proxy prend automatiquement un verrou lecture, appelle Sentence.read(), puis déverrouille.
            String s = irc.sentence.read();

            irc.data.setText(s);
            irc.text.append(s + "\n");
        } catch (Exception ex) {
            System.out.println("Irc2 problem : " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}

class Irc2WriteListener implements ActionListener {
    Irc2 irc;

    public Irc2WriteListener(Irc2 irc) {
        this.irc = irc;
    }

    public void actionPerformed(ActionEvent e) {
        try {
            String s = irc.data.getText();

            // Version transparente : l'appel write() est intercepte par JvnProxy.
            // Le proxy prend automatiquement un verrou ecriture, appelle Sentence.write(), puis déverrouille.
            irc.sentence.write(s);
        } catch (Exception ex) {
            System.out.println("Irc2 problem : " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}
