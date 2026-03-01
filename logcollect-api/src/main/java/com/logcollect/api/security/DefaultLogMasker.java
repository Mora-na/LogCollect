package com.logcollect.api.security;

import com.logcollect.api.masker.LogMasker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultLogMasker implements LogMasker {
    private static final Pattern PHONE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    private static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[0-9Xx]\\b");

    @Override
    public String mask(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String withPhoneMasked = maskPhone(content);
        return ID_CARD.matcher(withPhoneMasked).replaceAll("******************");
    }

    private String maskPhone(String input) {
        Matcher matcher = PHONE.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String value = matcher.group();
            String replacement = value.substring(0, 3) + "****" + value.substring(7);
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
