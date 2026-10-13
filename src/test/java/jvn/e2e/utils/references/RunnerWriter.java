package jvn.e2e.utils.references;

import irc.model.Paragraph;
import irc.model.ParagraphItf;
import irc.model.Sentence;
import irc.model.SentenceItf;
import jvn.proxy.JvnProxy;

public class RunnerWriter {
    public static void main(String[] args) throws Exception {
        String baseName = args[0];

        SentenceItf s1 = (SentenceItf) JvnProxy.newInstance(new Sentence(), "SENTENCE_" + baseName);
        s1.write("Bonjour");

        ParagraphItf p1 = (ParagraphItf) JvnProxy.newInstance(new Paragraph(), "PARAGRAPH_" + baseName);
        p1.addSentence(s1);

        System.out.println("READY_STEP_1");

        Thread.sleep(2500);
        s1.write("Monde");

        System.out.println("UPDATED_STEP_2");

        Thread.sleep(2000);
    }
}