package com.github.gerolndnr.connectionguard.core.messages;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

/** Selected files stay inside translation/, are bounded, never symlinks and never overwritten. */
public final class LanguageFiles {
    private LanguageFiles() { }
    public interface Parser<T> {
        T parse(Path file) throws Exception;
        Object value(T document,String key);
    }
    public static final class Loaded<T> {
        public final Path file; public final T document; public final MessageCatalog messages;
        private Loaded(Path file,T document,MessageCatalog messages) { this.file=file;this.document=document;this.messages=messages; }
    }
    public static String selection(Object raw) {
        if(raw==null)return "en";
        if(!(raw instanceof String) || !((String)raw).matches("[A-Za-z][A-Za-z0-9_-]{0,31}"))throw invalid();
        return (String)raw;
    }
    public static <T> Loaded<T> load(Path directory,Object raw,Parser<T> parser) {
        String selected=selection(raw); Path root=directory.toAbsolutePath().normalize(), folder=root.resolve("translation");
        Path file=folder.resolve(selected+".yml");
        try {
            if(Files.isSymbolicLink(root) || Files.isSymbolicLink(folder) || Files.isSymbolicLink(file))throw new IOException();
            Files.createDirectories(folder);
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                String bundled=selected.toLowerCase(Locale.ROOT);
                if(!bundled.equals("de") && !bundled.equals("es"))bundled="en";
                byte[] bytes;
                try(InputStream source=LanguageFiles.class.getResourceAsStream("/translation/"+bundled+".yml")) {
                    if(source==null)throw new IOException(); ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] block=new byte[4096];int count;
                    while((count=source.read(block))!=-1) { if(out.size()+count>65536)throw new IOException(); out.write(block,0,count); } bytes=out.toByteArray();
                }
                try { Files.write(file,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                    try { Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------")); } catch(UnsupportedOperationException ignored) { }
                } catch(FileAlreadyExistsException concurrent) { /* Validate the file created by the other loader. */ }
            }
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file) || Files.size(file)>65536)throw new IOException();
            T document=parser.parse(file); MessageCatalog messages=MessageCatalog.read(selected,key->parser.value(document,key));
            return new Loaded<>(file,document,messages);
        } catch(Exception failure) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("message-language or selected message file is invalid (values redacted); active settings preserved."); }
}
