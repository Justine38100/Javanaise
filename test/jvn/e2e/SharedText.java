package jvn.e2e;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class SharedText implements Serializable {
    private static final long serialVersionUID = 1L;

    public String value;
    public List<String> messages;

    public SharedText(String value) {
        this.value = value;
        this.messages = new ArrayList<String>();
    }
}
