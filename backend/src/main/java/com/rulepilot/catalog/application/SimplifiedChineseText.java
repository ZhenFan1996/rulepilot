package com.rulepilot.catalog.application;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import java.util.List;

public final class SimplifiedChineseText {

    private SimplifiedChineseText() {}

    public static String normalize(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        return ZhConverterUtil.toSimple(value);
    }

    public static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().map(SimplifiedChineseText::normalize).toList();
    }
}
