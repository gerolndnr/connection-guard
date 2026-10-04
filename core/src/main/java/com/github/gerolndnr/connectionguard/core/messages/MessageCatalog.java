package com.github.gerolndnr.connectionguard.core.messages;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.*;

/** Immutable, bounded message draft. Missing operator keys use the selected bundled locale. */
public final class MessageCatalog {
    private static final Pattern TOKENS = Pattern.compile("%[A-Z][A-Z0-9_]*%|\\{[0-9]+\\}");
    private static final Map<String,Map<String,Object>> BUNDLED = loadBundled();
    private final String language;
    private final Map<String,Object> messages;
    private final Map<String,String> translations;
    private MessageCatalog(String language, Map<String,Object> values) {
        this.language=language; messages=Collections.unmodifiableMap(values);
        Map<String,String> translated=new HashMap<>();
        for(Map.Entry<String,Object> item:BUNDLED.get("en").entrySet())
            if(item.getValue() instanceof String) translated.put((String)item.getValue(),(String)values.get(item.getKey()));
        translations=Collections.unmodifiableMap(translated);
    }
    public static MessageCatalog defaults(String language) { return read(language,key->null); }
    public static MessageCatalog read(String language, Function<String,Object> value) {
        String selected=LanguageFiles.selection(language);
        Map<String,Object> fallback=BUNDLED.getOrDefault(selected.toLowerCase(Locale.ROOT),BUNDLED.get("en"));
        Map<String,Object> result=new LinkedHashMap<>(); int total=0;
        for(Map.Entry<String,Object> item:fallback.entrySet()) {
            Object raw=value.apply(item.getKey()); if(raw==null)raw=item.getValue();
            Object valid=validate(item.getKey(),raw,BUNDLED.get("en").get(item.getKey()));
            total+=valid instanceof String?((String)valid).length():((List<?>)valid).stream().mapToInt(v->((String)v).length()).sum();
            if(total>65536)throw invalid(); result.put(item.getKey(),valid);
        }
        return new MessageCatalog(selected,result);
    }
    public String language() { return language; }
    public Set<String> keys() { return messages.keySet(); }
    public String getString(String key) {
        Object value=messages.get(key); if(!(value instanceof String))throw new IllegalArgumentException("Unknown message key."); return (String)value;
    }
    @SuppressWarnings("unchecked") public List<String> getStringList(String key) {
        Object value=messages.get(key); if(!(value instanceof List))throw new IllegalArgumentException("Unknown message list key."); return (List<String>)value;
    }
    /** Replacements are literal, one-pass; argument braces/dollars/backslashes are never interpreted. */
    public String text(String key,Object...args) {
        Matcher matcher=Pattern.compile("\\{([0-9]+)\\}").matcher(getString(key)); StringBuffer out=new StringBuffer();
        while(matcher.find()) { int index=Integer.parseInt(matcher.group(1)); if(index>=args.length)throw invalid();
            matcher.appendReplacement(out,Matcher.quoteReplacement(String.valueOf(args[index]))); }
        matcher.appendTail(out); return out.toString();
    }
    /** Only exact known human text is translated; typed facts and third-party messages are unchanged. */
    public String translate(String english) { return translations.getOrDefault(english,english); }
    private static Object validate(String key,Object raw,Object schema) {
        Set<String> allowed=new HashSet<>();
        if(schema instanceof String)tokens((String)schema,allowed); else for(Object line:(List<?>)schema)tokens((String)line,allowed);
        // Historical documented player templates may add supported placeholders absent in defaults.
        if(key.startsWith("messages.vpn-"))allowed.addAll(Arrays.asList("%NAME%","%IP%"));
        if(key.startsWith("messages.geo-"))allowed.addAll(Arrays.asList("%NAME%","%IP%","%COUNTRY%","%CITY%","%ISP%"));
        if(key.equals("messages.info.text"))allowed.addAll(Arrays.asList("%INPUT%","%IP%","%IS_VPN%","%CITY%","%COUNTRY%","%ISP%"));
        if(schema instanceof String) { if(!(raw instanceof String))throw invalid(); validateText(key,(String)raw,allowed); return raw; }
        if(!(raw instanceof List) || ((List<?>)raw).size()>32)throw invalid();
        List<String> lines=new ArrayList<>(); for(Object line:(List<?>)raw) {
            if(!(line instanceof String))throw invalid(); validateText(key,(String)line,allowed); lines.add((String)line); }
        return Collections.unmodifiableList(lines);
    }
    private static void tokens(String value,Set<String> tokens) { Matcher matcher=TOKENS.matcher(value); while(matcher.find())tokens.add(matcher.group()); }
    private static void validateText(String key,String text,Set<String> allowed) {
        int limit=key.startsWith("webhook.field.")?80:key.equals("webhook.footer")?220:key.startsWith("webhook.")?512:2000;
        if(text.isEmpty() || text.length()>limit)throw invalid();
        for(int i=0;i<text.length();) { char first=text.charAt(i); int point=text.codePointAt(i);
            if(Character.isSurrogate(first) && (point<=0xffff) || Character.getType(point)==Character.FORMAT
                    || Character.isISOControl(point) && point!='\n' && point!='\t')throw invalid(); i+=Character.charCount(point); }
        Set<String> actual=new HashSet<>(); tokens(text,actual); if(!allowed.containsAll(actual))throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Message file is invalid; active messages/settings preserved (values redacted)."); }
    private static Map<String,Map<String,Object>> loadBundled() {
        Map<String,Map<String,Object>> all=new LinkedHashMap<>();
        for(String locale:Arrays.asList("en","de","es")) {
            Properties source=new Properties();
            try(InputStream stream=MessageCatalog.class.getResourceAsStream("/translation/catalog/"+locale+".properties")) {
                if(stream==null)throw new IOException();
                source.load(new InputStreamReader(stream,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)));
            } catch(IOException failure) { throw new IllegalStateException("Bundled messages unavailable."); }
            Map<String,Object> values=new TreeMap<>();
            for(String key:source.stringPropertyNames()) if(!key.matches(".*\\.[0-9]+$"))values.put(key,source.getProperty(key));
            for(String group:Arrays.asList("messages.help","messages.info.text")) {
                List<String> lines=new ArrayList<>(); for(int i=0;source.containsKey(group+"."+i);i++)lines.add(source.getProperty(group+"."+i));
                if(lines.isEmpty())throw new IllegalStateException("Bundled message list unavailable.");
                values.put(group,Collections.unmodifiableList(lines));
            }
            if(!all.isEmpty() && !all.get("en").keySet().equals(values.keySet()))throw new IllegalStateException("Bundled message schema mismatch.");
            Map<String,Object> english=all.isEmpty()?values:all.get("en");
            for(Map.Entry<String,Object> entry:values.entrySet())validate(entry.getKey(),entry.getValue(),english.get(entry.getKey()));
            all.put(locale,Collections.unmodifiableMap(values));
        }
        return Collections.unmodifiableMap(all);
    }
}
