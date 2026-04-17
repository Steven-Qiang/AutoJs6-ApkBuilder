package org.autojs.autojs.script;

import org.autojs.autojs.rhino.TokenStream;
import org.mozilla.javascript.Token;

import java.io.Reader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Stardust on Aug 2, 2017.
 */
public abstract class JavaScriptSource {

    public static final String ENGINE = JavaScriptSource.class.getName() + ".Engine";

    public static final String EXECUTION_MODE_UI_PREFIX = "\"ui\";";

    public static final int EXECUTION_MODE_RAW = -0x0001;
    public static final int EXECUTION_MODE_NORMAL = 0x0000;
    public static final int EXECUTION_MODE_UI = 0x0001;
    public static final int EXECUTION_MODE_AUTO = 0x0002;
    public static final int EXECUTION_MODE_JSOX = 0x0004;

    private static final String LOG_TAG = "JavaScriptSource";

    public static final Map<String, Integer> EXECUTION_MODES = new HashMap<String, Integer>() {{
        put("ui", EXECUTION_MODE_UI);
        put("auto", EXECUTION_MODE_AUTO);
        put("jsox", EXECUTION_MODE_JSOX);
        put("x", EXECUTION_MODE_JSOX);
    }};

    private static final int PARSING_MAX_TOKEN = 300;

    private int mExecutionMode = EXECUTION_MODE_RAW;
    
    protected final String name;

    public JavaScriptSource(String name) {
        this.name = name;
    }

    public abstract String getScript();

    public abstract Reader getScriptReader();

    public Reader getNonNullScriptReader() {
        Reader reader = getScriptReader();
        if (reader == null) {
            return new StringReader(getScript());
        }
        return reader;
    }

    public String toString() {
        return name;
    }

    public int getExecutionMode() {
        if (mExecutionMode == EXECUTION_MODE_RAW) {
            mExecutionMode = parseExecutionMode();
        }
        return mExecutionMode;
    }

    protected int parseExecutionMode() {
        return parseExecutionMode(getScript()).mode;
    }

    public static ExecutionInfo parseExecutionMode(String script) {
        TokenStream ts = new TokenStream(new StringReader(script), null, 1);
        int token;
        int count = 0;
        try {
            while (count <= PARSING_MAX_TOKEN && (token = ts.getToken()) != Token.EOF) {
                count++;
                if (token == Token.EOL || token == Token.COMMENT) {
                    continue;
                }
                if (token != Token.STRING || ts.getTokenLength() < 3) {
                    break;
                }
                String tokenString = script.substring(ts.getTokenBeg() + 1, ts.getTokenEnd() - 1);
                System.out.println("[DEBUG] token string: " + tokenString);
                int nextToken = ts.getToken();
                System.out.println("[DEBUG] next token: " + nextToken);
                if (nextToken != Token.SEMI && nextToken != Token.EOL) {
                    break;
                }
                int mode = parseExecutionModeByModeStrings(tokenString.split("\\s*[,;|]\\s*|\\s+"));
                int lineno = ts.getLineno();
                return new ExecutionInfo.Builder()
                        .setMode(mode)
                        .setLineno(lineno)
                        .build();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ExecutionInfo.Builder()
                .setMode(EXECUTION_MODE_NORMAL)
                .setLineno(0)
                .build();
    }

    private static int parseExecutionModeByModeStrings(String[] modeStrings) {
        int mode = EXECUTION_MODE_NORMAL;
        for (String modeString : modeStrings) {
            String niceModeString = modeString.toLowerCase();
            Integer i = EXECUTION_MODES.get(niceModeString);
            if (i != null) {
                mode |= i;
            }
        }
        return mode;
    }

    public String getEngineName() {
        return ENGINE;
    }

    public static class ExecutionInfo {

        private final int mode;
        private final int lineno;

        private ExecutionInfo(Builder builder) {
            this.mode = builder.mode;
            this.lineno = builder.lineno;
        }

        public int getMode() {
            return this.mode;
        }

        public int getLineno() {
            return this.lineno;
        }

        public static class Builder {
            private int mode;
            private int lineno;

            public Builder setMode(int mode) {
                this.mode = mode;
                return this;
            }

            public Builder setLineno(int lineno) {
                this.lineno = lineno;
                return this;
            }

            public ExecutionInfo build() {
                return new ExecutionInfo(this);
            }
        }
    }

}
