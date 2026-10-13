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

public class IrcWindow2 {

    private final JFrame frame;
    private final JTextPane messages;
    private final JTextField input;

    public IrcWindow2(
            String title,
            Runnable readSentenceAction,
            Runnable writeSentenceAction,
            Runnable readParagraphAction,
            Runnable appendParagraphAction,
            Runnable removeLastSentenceFromParagraphAction,
            Runnable updateSentenceAction)
    {

        frame = new JFrame(title);
        frame.setLayout(new BorderLayout(8, 8));

        messages = new JTextPane();
        messages.setEditable(false);
        messages.setBackground(new Color(15, 18, 24));
        messages.setPreferredSize(new Dimension(680, 280));

        input = new JTextField(30);

        // Boutons Sentence
        JButton readSentenceBtn = new JButton("Lire Phrase");
        readSentenceBtn.addActionListener(e -> readSentenceAction.run());

        JButton writeSentenceBtn = new JButton("Ecrire Phrase");
        writeSentenceBtn.addActionListener(e -> writeSentenceAction.run());

        // Boutons Paragraph
        JButton readParagraphBtn = new JButton("Lire Para");
        readParagraphBtn.addActionListener(e -> readParagraphAction.run());

        JButton appendParagraphBtn = new JButton("Ajouter au Para");
        appendParagraphBtn.addActionListener(e -> appendParagraphAction.run());

        JButton removeLastSentenceFromParagraphBtn = new JButton("Supprimer la dernière phrase");
        removeLastSentenceFromParagraphBtn.addActionListener(e -> removeLastSentenceFromParagraphAction.run());

        JButton updateSentenceBtn = new JButton("Modifier phrase (idx:txt)");
        updateSentenceBtn.addActionListener(e -> updateSentenceAction.run());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(input);
        controls.add(readSentenceBtn);
        controls.add(writeSentenceBtn);
        controls.add(readParagraphBtn);
        controls.add(appendParagraphBtn);
        controls.add(removeLastSentenceFromParagraphBtn);
        controls.add(updateSentenceBtn);

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

    public void appendParagraphMessage(String message) {
        appendLine("[PARAGRAPHE] " + message, new Color(46, 204, 113));
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