package com.retailsvc.http.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record PathTemplate(String raw, Pattern compiled, List<String> parameterNames) {

  private static final Pattern TOKEN = Pattern.compile("\\{([^/}]+)}");

  public static PathTemplate compile(String template) {
    StringBuilder regex = new StringBuilder("^");
    List<String> names = new ArrayList<>();
    Matcher m = TOKEN.matcher(template);
    int last = 0;
    while (m.find()) {
      regex.append(Pattern.quote(template.substring(last, m.start())));
      regex.append("([^/]+)");
      names.add(m.group(1));
      last = m.end();
    }
    regex.append(Pattern.quote(template.substring(last)));
    regex.append("$");
    return new PathTemplate(template, Pattern.compile(regex.toString()), List.copyOf(names));
  }

  public Optional<Map<String, String>> match(String path) {
    Matcher m = compiled.matcher(path);
    if (!m.matches()) return Optional.empty();
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 0; i < parameterNames.size(); i++) {
      out.put(parameterNames.get(i), m.group(i + 1));
    }
    return Optional.of(Map.copyOf(out));
  }
}
