package com.financial.copilot.agent.core.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Bounded, allow-value JSON projection; unknown objects are never stringified. */
public final class ToolArgumentSanitizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DENIED = Set.of("authorization", "cookie", "setcookie", "apikey", "accesstoken", "refreshtoken", "password", "privatekey", "secret", "signature", "systemprompt", "sysprompt", "reasoning", "reasoningcontent", "headers");
    public Map<String,Object> sanitize(Map<String,?> input) {
        Map<String,Object> result = new LinkedHashMap<>();
        if(input == null) return result;
        int count=0;
        for(var entry:input.entrySet()) {
            if(++count>100) break;
            if(entry.getKey()==null || denied(entry.getKey())) continue;
            String key=truncate(entry.getKey(),1000);
            result.put(key, clean(entry.getValue(),1));
            try { if(JSON.writeValueAsBytes(result).length>16384) {result.remove(key);break;} }
            catch(Exception e) {result.remove(key);}
        }
        return Collections.unmodifiableMap(result);
    }
    private Object clean(Object value,int depth) {
        if(depth>8) return "[depth limit]";
        if(value==null || value instanceof Boolean || value instanceof Number) return value;
        if(value instanceof String text) return truncate(text,1000);
        if(value instanceof Map<?,?> map) {
            Map<String,Object> result=new LinkedHashMap<>();int count=0;
            for(var entry:map.entrySet()) {if(++count>100)break;if(entry.getKey() instanceof String key && !denied(key))result.put(truncate(key,1000),clean(entry.getValue(),depth+1));}
            return result;
        }
        if(value instanceof Iterable<?> list) {List<Object> result=new ArrayList<>();for(Object item:list){if(result.size()==100)break;result.add(clean(item,depth+1));}return result;}
        if(value.getClass().isArray()) {List<Object> result=new ArrayList<>();for(int i=0;i<Math.min(100,java.lang.reflect.Array.getLength(value));i++)result.add(clean(java.lang.reflect.Array.get(value,i),depth+1));return result;}
        return "[unsupported value]";
    }
    private boolean denied(String key) {return DENIED.contains(key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]",""));}
    public static String truncate(String value,int limit) {if(value==null)return null;return value.substring(0,value.offsetByCodePoints(0,Math.min(limit,value.codePointCount(0,value.length()))));}
}
