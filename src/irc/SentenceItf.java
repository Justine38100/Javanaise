package irc;

import jvn.JvnRead;
import jvn.JvnWrite;

public interface SentenceItf {

    @JvnWrite
    public void write(String text);

    @JvnRead
    public String read();
}