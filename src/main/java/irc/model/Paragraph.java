package irc.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Paragraph implements ParagraphItf, Serializable {

    private static final long serialVersionUID = 1L;

    private List<SentenceItf> sentences;

    public Paragraph() {
        this.sentences = new ArrayList<>();
    }

    @Override
    public void addSentence(SentenceItf sentence) {
        sentences.add(sentence);
    }

    @Override
    public void removeLastSentence() {
        if (!sentences.isEmpty()) {
            sentences.remove(sentences.size() - 1);
        }
    }

    @Override
    public String read() {
        StringBuilder paragraphText = new StringBuilder();
        for (SentenceItf sentence : sentences) {
            paragraphText.append(sentence.read()).append(" ");
        }
        return paragraphText.toString().trim();
    }
}
