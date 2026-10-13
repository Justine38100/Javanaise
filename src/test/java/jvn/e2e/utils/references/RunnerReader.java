package jvn.e2e.utils.references;

import irc.model.Paragraph;
import irc.model.ParagraphItf;
import jvn.proxy.JvnProxy;

public class RunnerReader {
    public static void main(String[] args) throws Exception {
        String baseName = args[0];

        ParagraphItf p1 = (ParagraphItf) JvnProxy.newInstance(new Paragraph(), "PARAGRAPH_" + baseName);

        String firstRead = p1.read();
        System.out.println("READ_1:" + firstRead);
        System.out.flush();

        Thread.sleep(3500);

        String secondRead = p1.read();
        System.out.println("READ_2:" + secondRead);
        System.out.flush();
        System.exit(0);
    }
}