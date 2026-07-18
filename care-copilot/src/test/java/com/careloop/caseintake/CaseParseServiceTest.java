package com.careloop.caseintake;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseParseServiceTest {

    private final CaseParseService service = new CaseParseService();

    @Test
    void parsesNameInsideBrackets() {
        String text = """
                一、一般项目
                姓名：【刘秀英】
                性别：【女】
                年龄：【52岁】
                出院诊断：膝关节置换术后
                主治医生：李医生
                """;
        Map<String, String> r = service.parse(text);
        assertEquals("刘秀英", r.get("name"));
        assertEquals("女", r.get("gender"));
        assertTrue(r.get("confidence").equals("high") || r.get("confidence").equals("medium"));
    }
}
