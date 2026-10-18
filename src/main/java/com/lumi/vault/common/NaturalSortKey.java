package com.lumi.vault.common;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件名的自然排序键：数字段按数值大小比较（109 &lt; 1094 &lt; 110），其余部分按小写字典序。
 * 生成规则与数据库迁移中的 lumina_natural_key 保持一致：
 * - 数字段去前导零后不足 12 位 → 左补零到 12 位；
 * - 超过 12 位 → 前缀 ':'（大于任何数字字符）后接原数字，保证更长整数排在后且互相按字典序即数值序。
 */
public final class NaturalSortKey {

    private static final int DIGITS_WIDTH = 12;

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");

    private NaturalSortKey() {
    }

    public static String of(String filename) {
        if (filename == null) {
            return null;
        }
        String value = filename.toLowerCase(Locale.ROOT);
        StringBuilder key = new StringBuilder(value.length() + 16);
        Matcher matcher = DIGIT_RUN.matcher(value);
        int end = 0;
        while (matcher.find()) {
            key.append(value, end, matcher.start());
            String digits = matcher.group().replaceFirst("^0+", "");
            if (digits.isEmpty()) {
                digits = "0";
            }
            if (digits.length() <= DIGITS_WIDTH) {
                key.append("0".repeat(DIGITS_WIDTH - digits.length())).append(digits);
            } else {
                key.append(':').append(digits);
            }
            end = matcher.end();
        }
        key.append(value, end, value.length());
        return key.toString();
    }
}
