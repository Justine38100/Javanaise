package irc.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Interface graphique de l'application IRC.
 *
 * Spécification :
 * - cette classe ne contient pas de logique Javanaise ;
 * - les messages applicatifs sont affichés en bleu ;
 * - les messages de panne, reconnexion ou diagnostic sont affichés en rouge ;
 * - la fermeture de la fenêtre termine le processus pour libérer le serveur local RMI.
 */
public class IrcWindow {

    private final JFrame frame;
    private final JTextPane messages;
    private final JTextField input;

    public IrcWindow(String title, Runnable readAction, Runnable writeAction) {
        frame = new JFrame(title);
        frame.setLayout(new BorderLayout(8, 8));

        messages = new JTextPane();
        messages.setEditable(false);
        messages.setBackground(new Color(15, 18, 24));
        messages.setPreferredSize(new Dimension(620, 260));

        input = new JTextField(42);

        JButton readButton = new JButton("Lire");
        readButton.addActionListener(event -> readAction.run());

        JButton writeButton = new JButton("Ecrire");
        writeButton.addActionListener(event -> writeAction.run());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(input);
        controls.add(readButton);
        controls.add(writeButton);

        frame.add(new JScrollPane(messages), BorderLayout.CENTER);
        frame.add(controls, BorderLayout.SOUTH);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                frame.dispose();
                System.exit(0);
            }
        });

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public String getInputText() {
        return input.getText();
    }

    public void setInputText(String value) {
        runOnUiThread(() -> input.setText(value));
    }

    public void appendMessage(String message) {
        appendLine(message, new Color(74, 144, 226));
    }

    public void appendFailureEvent(String message) {
        appendLine("[JVN] " + message, new Color(220, 74, 74));
    }

    private void appendLine(String message, Color color) {
        runOnUiThread(() -> {
            StyledDocument document = messages.getStyledDocument();
            SimpleAttributeSet attributes = new SimpleAttributeSet();
            StyleConstants.setForeground(attributes, color);

            try {
                document.insertString(document.getLength(), message + "\n", attributes);
                messages.setCaretPosition(document.getLength());
            } catch (BadLocationException e) {
                throw new IllegalStateException("Impossible d'afficher le message", e);
            }
        });
    }

    private void runOnUiThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
