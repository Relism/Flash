package dev.relism.flash.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class DateHeaderTest {
  @Test
  void imfFixdateAlwaysUsesTwoDigitDayOfMonth() {
    ZonedDateTime thirdOfMonth = ZonedDateTime.of(2026, 8, 3, 7, 5, 9, 0, ZoneOffset.UTC);
    assertEquals("Mon, 03 Aug 2026 07:05:09 GMT", DateHeader.format(thirdOfMonth));
  }
}
