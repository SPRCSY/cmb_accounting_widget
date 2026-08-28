package com.csy.cmbspend;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/** 用户自定义规则：排除关键词。
 *  命中任一排除词（出现在通知正文或商户名里）的动账，不计入消费。
 *  用户可在 App 的「规则设置」里自行增删，无需重新编译。 */
public final class Rules {

    private static final String PREFS = "rules";
    private static final String KEY_EXCLUDE = "exclude_words";

    private Rules() {}

    /** 读取当前排除关键词集合（可能为空）。 */
    public static Set<String> getExcludeWords(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new LinkedHashSet<>(p.getStringSet(KEY_EXCLUDE, new LinkedHashSet<>()));
    }

    /** 保存排除关键词集合。 */
    public static void saveExcludeWords(Context ctx, Set<String> words) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_EXCLUDE, new LinkedHashSet<>(words)).apply();
    }

    /** 判断文本/商户是否命中任一排除词（忽略大小写、去除空白）。 */
    public static boolean isExcluded(Set<String> excludeWords, String text, String merchant) {
        if (excludeWords == null || excludeWords.isEmpty()) return false;
        String haystack = (text == null ? "" : text) + " " + (merchant == null ? "" : merchant);
        for (String w : excludeWords) {
            if (w == null) continue;
            String kw = w.trim();
            if (!kw.isEmpty() && haystack.contains(kw)) return true;
        }
        return false;
    }
}
