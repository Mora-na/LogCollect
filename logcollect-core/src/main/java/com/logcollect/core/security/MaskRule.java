package com.logcollect.core.security;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MaskRule {
    private final Pattern pattern;
    private final Function<Matcher, String> replacer;
    private final Character quickCheckChar;

    MaskRule(Pattern pattern, Function<Matcher, String> replacer) {
        this(pattern, replacer, null);
    }

    MaskRule(Pattern pattern, Function<Matcher, String> replacer, String quickCheckHint) {
        this.pattern = pattern;
        this.replacer = replacer;
        this.quickCheckChar = quickCheckHint == null || quickCheckHint.isEmpty()
                ? null
                : Character.valueOf(quickCheckHint.charAt(0));
    }

    String apply(CharSequence content) {
        if (content == null) {
            return null;
        }
        if (content.length() == 0) {
            return content.toString();
        }
        if (quickCheckChar != null && !containsChar(content, quickCheckChar.charValue())) {
            return content.toString();
        }
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return content.toString();
        }
        matcher.reset();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(matcher)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean containsChar(CharSequence content, char target) {
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == target) {
                return true;
            }
        }
        return false;
    }
}
