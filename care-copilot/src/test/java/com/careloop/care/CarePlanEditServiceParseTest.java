package com.careloop.care;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轻量校验自然语言改计划用的正则（与 CarePlanEditService 保持一致）。
 */
class CarePlanEditServiceParseTest {

    private static final Pattern FOLLOWUP_DAYS = Pattern.compile(
            "(?:随访天数|随访日数|随访时间|随访周期|随访)\\s*(?:改成|改为|调整为|设为|设置为|定为)?\\s*[：:]?\\s*(\\d{1,3})\\s*天"
                    + "|(?:改成|改为|调整为|设为|设置为)\\s*(\\d{1,3})\\s*天"
    );
    private static final Pattern DAILY_TIME = Pattern.compile(
            "(?:每日时间|每天时间|收集时间)?\\s*"
                    + "(?:每日|每天)?\\s*(?:上午|下午|早上|晚上)?"
                    + "\\s*(\\d{1,2})\\s*[:.：点]\\s*(\\d{1,2})\\s*分?"
                    + "|(?:每日时间|每天时间)\\s*[：:]?\\s*(\\d{1,2})\\s*点?"
                    + "|(?:每日|每天)\\s*(\\d{1,2})\\s*点(?!\\s*\\d)"
    );

    @Test
    void parsesNaturalFollowupDaysAndTime() {
        String text = "随访时间改成1天，每日上午9.55分收集一下今日疼痛情况，是否按时用药，伤口是否有瘙痒现象";
        Matcher d = FOLLOWUP_DAYS.matcher(text);
        assertTrue(d.find());
        assertEquals("1", d.group(1) != null ? d.group(1) : d.group(2));

        Matcher t = DAILY_TIME.matcher(text.replace('．', '.'));
        assertTrue(t.find());
        assertEquals("9", t.group(1));
        assertEquals("55", t.group(2));
    }
}
