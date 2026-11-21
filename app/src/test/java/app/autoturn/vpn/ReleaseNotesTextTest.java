package app.autoturn.vpn;

import static org.junit.Assert.*;

import org.junit.Test;

public class ReleaseNotesTextTest {
    private static String styled(ReleaseNotesText notes, int index) {
        ReleaseNotesText.Range range = notes.ranges.get(index);
        return notes.text.substring(range.start, range.end);
    }

    @Test public void releaseBodyLosesMarkdownMarks() {
        ReleaseNotesText notes = ReleaseNotesText.parse("# AutoTurn VPN 2.0.1\r\n\r\nНебольшое обновление.\r\n\r\n" +
                "**Обновления приходят быстрее**\r\n- Раньше раз в сутки.\r\n- Кнопка **Настройки → Проверить** работает.\r\n");
        assertEquals("Небольшое обновление.\n\nОбновления приходят быстрее\n• Раньше раз в сутки.\n" +
                "• Кнопка Настройки → Проверить работает.", notes.text);
        assertFalse(notes.text.contains("*"));
        assertFalse(notes.text.contains("#"));
        assertEquals(2, notes.ranges.size());
        assertEquals("Обновления приходят быстрее", styled(notes, 0));
        assertEquals("Настройки → Проверить", styled(notes, 1));
    }

    @Test public void headingsLinksCodeAndNesting() {
        ReleaseNotesText notes = ReleaseNotesText.parse("Вступление\n\n## Профили\n  - вложенный `пункт`\n" +
                "См. [историю](https://example.com/x).\n\n\n\nКонец");
        assertEquals("Вступление\n\nПрофили\n  ◦ вложенный пункт\nСм. историю.\n\nКонец", notes.text);
        assertEquals(ReleaseNotesText.HEADING, notes.ranges.get(0).style);
        assertEquals("Профили", styled(notes, 0));
    }

    @Test public void topHeadingKeptWhenNotFirstAndOddMarksSurvive() {
        ReleaseNotesText notes = ReleaseNotesText.parse("Текст\n# Заголовок\n2 ** 3 и *курсив*");
        assertEquals("Текст\nЗаголовок\n2 ** 3 и *курсив*", notes.text);
        assertEquals(1, notes.ranges.size());
    }

    @Test public void emptyNotes() {
        assertEquals("", ReleaseNotesText.parse(null).text);
        assertEquals("", ReleaseNotesText.parse("  \n\n").text);
        assertEquals("", ReleaseNotesText.parse("# AutoTurn 2.0.2\n").text);
    }
}
