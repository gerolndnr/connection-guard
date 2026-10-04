import com.github.gerolndnr.connectionguard.core.messages.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Real supplied Bukkit/Bungee YAML implementations; this is not a server or account login. */
public final class NativeLanguageParsers {
    static int cases;
    static void require(boolean value){if(!value)throw new AssertionError("Native language parser contract failed");}
    static <T> void exercise(String platform,Path directory,LanguageFiles.Parser<T> parser)throws Exception {
        Files.createDirectories(directory);
        for(String code:Arrays.asList("en","de","es")) {
            LanguageFiles.Loaded<T> loaded=LanguageFiles.load(directory,code,parser);
            require(loaded.messages.getString("messages.access-denied").equals(MessageCatalog.defaults(code).getString("messages.access-denied")));
            require(loaded.messages.getStringList("messages.help").stream().anyMatch(line->line.contains("/cg") && line.contains("doctor")));cases++;
        }
        Path file=directory.resolve("translation/de.yml");String custom="messages:\n  vpn-block: Eigener Text %NAME%\n";
        Files.write(file,custom.getBytes(StandardCharsets.UTF_8));LanguageFiles.Loaded<T> loaded=LanguageFiles.load(directory,"de",parser);
        require(loaded.messages.getString("messages.vpn-block").equals("Eigener Text %NAME%"));
        require(loaded.messages.getString("webhook.title-deny").contains("abgelehnt"));require(new String(Files.readAllBytes(file),StandardCharsets.UTF_8).equals(custom));cases++;
        Files.write(file,"messages:\n  help: 17\n".getBytes(StandardCharsets.UTF_8));
        try {LanguageFiles.load(directory,"de",parser);throw new AssertionError("Invalid type accepted");}catch(IllegalArgumentException expected){cases++;}
        Files.write(file,"messages: [invalid: yaml\n".getBytes(StandardCharsets.UTF_8));
        try {LanguageFiles.load(directory,"de",parser);throw new AssertionError("Invalid YAML accepted");}catch(IllegalArgumentException expected){cases++;}
        System.out.println("NATIVE_LANGUAGE "+platform+" six cases passed");
    }
    public static void main(String[] args)throws Exception {
        Path root=Paths.get(args[0]);require(!Files.exists(root));Files.createDirectories(root);
        exercise("Bukkit",root.resolve("bukkit"),new LanguageFiles.Parser<org.bukkit.configuration.file.YamlConfiguration>() {
            public org.bukkit.configuration.file.YamlConfiguration parse(Path file)throws Exception {
                org.bukkit.configuration.file.YamlConfiguration value=new org.bukkit.configuration.file.YamlConfiguration();
                try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){value.load(reader);}return value;
            }
            public Object value(org.bukkit.configuration.file.YamlConfiguration document,String key){return document.get(key,null);}
        });
        exercise("Bungee",root.resolve("bungee"),new LanguageFiles.Parser<net.md_5.bungee.config.Configuration>() {
            public net.md_5.bungee.config.Configuration parse(Path file)throws Exception {
                try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){return net.md_5.bungee.config.ConfigurationProvider.getProvider(net.md_5.bungee.config.YamlConfiguration.class).load(reader);}
            }
            public Object value(net.md_5.bungee.config.Configuration document,String key){return document.get(key,null);}
        });
        require(cases==12);System.out.println("NATIVE_LANGUAGE_PARSERS_PASSED cases="+cases);
    }
}
