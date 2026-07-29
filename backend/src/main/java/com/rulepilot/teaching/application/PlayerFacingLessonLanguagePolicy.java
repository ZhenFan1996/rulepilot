package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import java.util.regex.Pattern;

/** Keeps missing-source diagnostics out of prose a player is meant to follow at the table. */
public final class PlayerFacingLessonLanguagePolicy {

    private static final Pattern SOURCE_GAP = Pattern.compile(
            "(?iu)(?:规则待定|当前(?:可用)?[^。！？!?]{0,60}(?:未|没有|无法)[^。！？!?]{0,60}(?:说明|确定)|"
                    + "(?:已有|现有|当前)(?:资料|内容|规则)[^。！？!?]{0,40}(?:未|没有|无法)[^。！？!?]{0,60}(?:说明|确定)|"
                    + "请等待[^。！？!?]{0,60}(?:确定|说明)|(?:暂无|没有)[^。！？!?]{0,30}(?:可用|可靠|足够)[^。！？!?]{0,30}(?:资料|规则|内容)|"
                    + "(?:页|页面|规则书|材料|资料|内容)[^。！？!?]{0,80}(?:不含|没有|未(?:提及|说明|提供)?|无法)"
                    + "[^。！？!?]{0,80}(?:结束|计分|胜者|胜利|同分|规则|条件|说明|判定|信息)|"
                    + "关于[^。！？!?]{0,80}(?:需要|请)[^。！？!?]{0,60}(?:其他|别的)[^。！？!?]{0,60}"
                    + "(?:规则|部分|资料|页面)[^。！？!?]{0,60}(?:了解|确认|查找))");

    private PlayerFacingLessonLanguagePolicy() {}

    public static boolean hasSourceGap(String value) {
        return value != null && SOURCE_GAP.matcher(value).find();
    }

    public static boolean isPubliclyReadable(IllustratedLesson lesson) {
        if (lesson == null || lesson.status() == IllustratedLesson.LessonStatus.INCOMPLETE) return false;
        return lesson.sections().stream().noneMatch(section -> hasSourceGap(section.title())
                || hasSourceGap(section.visualCaption())
                || section.steps().stream().anyMatch(step -> hasSourceGap(step.heading()) || hasSourceGap(step.text())));
    }
}
