package com.logcollect.core.security;

import com.logcollect.api.masker.LogMasker;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultLogMasker implements LogMasker {
    private final List<MaskRule> rules = new CopyOnWriteArrayList<MaskRule>();

    public DefaultLogMasker() {
        addBuiltinRules();
    }

    private void addBuiltinRules() {
        Pattern phone = Pattern.compile("\\b1[3-9]\\d{9}\\b");
        rules.add(new MaskRule(phone, new Function<Matcher, String>() {
            @Override
            public String apply(Matcher m) {
                String v = m.group();
                return v.substring(0, 3) + "****" + v.substring(7);
            }
        }, "1"));

        Pattern id = Pattern.compile("\\b\\d{17}[0-9Xx]\\b");
        rules.add(new MaskRule(id, new Function<Matcher, String>() {
            @Override
            public String apply(Matcher m) {
                String v = m.group();
                return v.substring(0, 6) + "********" + v.substring(14);
            }
        }));

        Pattern bank = Pattern.compile("\\b\\d{12,19}\\b");
        rules.add(new MaskRule(bank, new Function<Matcher, String>() {
            @Override
            public String apply(Matcher m) {
                String v = m.group();
                if (v.length() <= 8) {
                    return "********";
                }
                return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
            }
        }));

        Pattern email = Pattern.compile("([\\w._%+-]+)@([\\w.-]+\\.[A-Za-z]{2,})");
        rules.add(new MaskRule(email, new Function<Matcher, String>() {
            @Override
            public String apply(Matcher m) {
                String user = m.group(1);
                String domain = m.group(2);
                String masked = user.length() <= 2 ? "**" : user.substring(0, 2) + "****";
                return masked + "@" + domain;
            }
        }, "@"));
    }

    public boolean addRule(String regex, Function<Matcher, String> replacer) {
        Pattern pattern = RegexSafetyValidator.safeCompile(regex);
        if (pattern == null) {
            return false;
        }
        rules.add(new MaskRule(pattern, replacer));
        return true;
    }

    @Override
    public String mask(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String result = content;
        for (MaskRule rule : rules) {
            result = rule.apply(result);
        }
        return result;
    }
}
