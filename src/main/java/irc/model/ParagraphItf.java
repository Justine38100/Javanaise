package irc.model;

import jvn.annotations.JvnRead;
import jvn.annotations.JvnWrite;

public interface ParagraphItf {

    @JvnWrite
    void addSentence(SentenceItf sentence);

    @JvnWrite
    void removeLastSentence();

    @JvnRead
    String read();
}
