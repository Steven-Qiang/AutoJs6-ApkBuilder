package org.autojs.autojs.script;

import org.autojs.autojs.pio.BuildFiles;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.Reader;
import java.nio.file.Files;

/**
 * Created by Stardust on Apr 2, 2017.
 */
public class JavaScriptFileSource extends JavaScriptSource {

    private final File mFile;
    private String mScript;

    public JavaScriptFileSource(File file) {
        super(BuildFiles.getNameWithoutExtension(file.getName()));
        mFile = file;
    }

    public JavaScriptFileSource(String path) {
        this(new File(path));
    }

    @Override
    public String getScript() {
        if (mScript == null) {
            try {
                mScript = new String(Files.readAllBytes(mFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return mScript;
    }

    @Override
    protected int parseExecutionMode() {
        short flags = EncryptedScriptFileHeader.getHeaderFlags(mFile);
        if (flags == EncryptedScriptFileHeader.FLAG_INVALID_FILE) {
            return super.parseExecutionMode();
        }
        return flags;
    }

    @Override
    public Reader getScriptReader() {
        try {
            return new FileReader(mFile);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public File getFile() {
        return mFile;
    }

    @Override
    public String toString() {
        return mFile.toString();
    }
}
