package com.logcollect.core.security;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MaskRule {
    private final Pattern pattern;
    private final Function<Matcher, String> replacer;
    private final String preCheck;

    public MaskRule(Pattern pattern, Function<Matcher, String> replacer) {
        this(pattern, replacer, null);
    }

    public MaskRule(Pattern pattern, Function<Matcher, String> replacer, String preCheck) {
        this.pattern = pattern;
        this.replacer = replacer;
        this.preCheck = preCheck;
    }

    public String apply(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        if (preCheck != null && !content.contains(preCheck)) {
            return content;
        }
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        matcher.reset();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(matcher)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
