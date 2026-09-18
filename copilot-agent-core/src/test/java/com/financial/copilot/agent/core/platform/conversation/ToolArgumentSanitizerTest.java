package com.financial.copilot.agent.core.platform.conversation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ToolArgumentSanitizerTest {
 @Test void stripsNestedSecretsAndBoundsUnicode() {
  var sanitizer = new ToolArgumentSanitizer();
  var result = sanitizer.sanitize(Map.of("apiKey", "secret", "nested", Map.of("AUTHORIZATION", "hidden", "query", "ok"), "text", "😀".repeat(1500)));
  assertThat(result.toString()).doesNotContain("secret", "hidden");
  assertThat(((String)result.get("text")).codePointCount(0, ((String)result.get("text")).length())).isEqualTo(1000);
 }
 @Test void boundsSerializedArguments() throws Exception {
  var input = new LinkedHashMap<String,Object>(); for(int i=0;i<100;i++) input.put("k"+i,"a".repeat(1000));
  assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(new ToolArgumentSanitizer().sanitize(input)).length).isLessThanOrEqualTo(16384);
 }
}
