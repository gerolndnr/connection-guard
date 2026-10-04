package com.github.gerolndnr.connectionguard.core.messages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LanguageFilesTest {
    @TempDir Path directory;
    private LanguageFiles.Parser<String> parser() {
        return new LanguageFiles.Parser<String>() {
            public String parse(Path file)throws Exception{return new String(Files.readAllBytes(file),StandardCharsets.UTF_8);}
            public Object value(String document,String key){return null;}
        };
    }
    @Test void newKnownSelectionsCopyTheirActualLocaleIncludingUppercaseSelection() {
        for(String code:Arrays.asList("en","de","es","DE")) {
            LanguageFiles.Loaded<String> loaded=LanguageFiles.load(directory,code,parser());
            assertEquals(directory.resolve("translation/"+code+".yml"),loaded.file);assertEquals(code,loaded.messages.language());
            if(code.equalsIgnoreCase("de"))assertTrue(loaded.document.contains("Zugangsregeln"));
            if(code.equals("es"))assertTrue(loaded.document.contains("Conexión"));
            assertTrue(loaded.document.length()<65536);
        }
    }
    @Test void existingCustomizedFileIsNeverOverwrittenAndMissingKeysFallback()throws Exception {
        Files.createDirectory(directory.resolve("translation"));Path file=directory.resolve("translation/de.yml");
        String custom="messages:\n  vpn-block: Betreibertext\n";Files.write(file,custom.getBytes(StandardCharsets.UTF_8));
        LanguageFiles.Loaded<String> loaded=LanguageFiles.load(directory,"de",new LanguageFiles.Parser<String>() {
            public String parse(Path p)throws Exception{return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);}
            public Object value(String document,String key){return key.equals("messages.vpn-block")?"Betreibertext":null;}
        });
        assertEquals(custom,new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
        assertEquals("Betreibertext",loaded.messages.getString("messages.vpn-block"));
        assertTrue(loaded.messages.getString("command.config-reload").contains("neu geladen"));
    }
    @Test void unknownSelectionCreatesAnExplicitEnglishTemplateWithoutClaimingTranslation() {
        LanguageFiles.Loaded<String> loaded=LanguageFiles.load(directory,"my-community",parser());
        assertTrue(loaded.document.contains("Connection denied by server access policy."));
        assertEquals("my-community",loaded.messages.language());
    }
    @Test void unsafeSelectionsRejectBeforeAnyFileCreationAndDoNotEchoSecrets() {
        for(Object invalid:Arrays.asList("../private-token","/absolute","en.yml","en/other","en\\other","",true,17,"en?secret","en\nsecret","en:secret",String.join("",Collections.nCopies(33,"e")))) {
            IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->LanguageFiles.load(directory,invalid,parser()));
            assertFalse(error.getMessage().contains("private-token"));assertFalse(error.getMessage().contains("secret"));
            assertFalse(Files.exists(directory.resolve("translation")));
        }
        assertEquals("en",LanguageFiles.selection(null));
    }
    @Test void symlinkFilesAndFoldersCannotReadOrOverwriteOutsideTheTranslationRoot()throws Exception {
        Path outside=directory.resolve("outside.yml");Files.write(outside,"private-token".getBytes(StandardCharsets.UTF_8));
        Path folder=directory.resolve("translation");Files.createDirectory(folder);Files.createSymbolicLink(folder.resolve("de.yml"),outside);
        assertThrows(IllegalArgumentException.class,()->LanguageFiles.load(directory,"de",parser()));
        Files.delete(folder.resolve("de.yml"));Files.delete(folder);Path another=directory.resolve("another");Files.createDirectory(another);
        Files.createSymbolicLink(folder,another);
        assertThrows(IllegalArgumentException.class,()->LanguageFiles.load(directory,"de",parser()));
        assertEquals("private-token",new String(Files.readAllBytes(outside),StandardCharsets.UTF_8));assertFalse(Files.exists(another.resolve("de.yml")));
    }
    @Test void oversizedOrInvalidDocumentsRejectBeforeActivationAndRedactParserExceptions()throws Exception {
        Files.createDirectory(directory.resolve("translation"));Path file=directory.resolve("translation/en.yml");Files.write(file,new byte[65537]);
        AtomicInteger parses=new AtomicInteger();LanguageFiles.Parser<String> parser=new LanguageFiles.Parser<String>() {
            public String parse(Path p)throws Exception{parses.incrementAndGet();throw new Exception("private-token");}
            public Object value(String document,String key){return null;}
        };
        assertThrows(IllegalArgumentException.class,()->LanguageFiles.load(directory,"en",parser));assertEquals(0,parses.get());
        Files.write(file,"invalid yaml".getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->LanguageFiles.load(directory,"en",parser));
        assertEquals(1,parses.get());assertFalse(error.getMessage().contains("private-token"));assertNull(error.getCause());
    }
}
