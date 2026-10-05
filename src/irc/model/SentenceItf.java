package irc.model;

import jvn.annotations.JvnRead;
import jvn.annotations.JvnWrite;

public interface SentenceItf {

    @JvnWrite
    public void write(String text);

    @JvnRead
    public String read();
}