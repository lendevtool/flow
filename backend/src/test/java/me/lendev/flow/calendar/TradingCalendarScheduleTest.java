package me.lendev.flow.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import me.lendev.flow.calendar.TradingCalendarModels.Market;
import me.lendev.flow.calendar.TradingCalendarModels.Source;
import me.lendev.flow.calendar.TradingCalendarRepository.MarketItem;
import me.lendev.flow.calendar.TradingCalendarRepository.StoredDay;
import me.lendev.flow.calendar.TradingCalendarRepository.Values;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.common.response.BizResponseCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradingCalendarScheduleTest {

    private static final LocalDate BASE = LocalDate.of(2099, 6, 1);
    private final TradingCalendarRepository repository = mock(TradingCalendarRepository.class);
    private final TradingCalendarService calendar = new TradingCalendarService(repository, null);

    @BeforeEach
    void marketAndBase() {
        when(repository.markets()).thenReturn(List.of(new MarketItem(1, new Market("CN", "中国", null, null, null))));
        when(repository.range(1, BASE, BASE, true)).thenReturn(List.of(day(0, true)));
    }

    @Test
    void sharesWindowsAcrossOffsetsInBothDirections() {
        when(repository.range(1, BASE.minusDays(128), BASE.minusDays(1), false))
                .thenReturn(List.of(day(-1, false), day(-2, true), day(-3, true)));
        when(repository.range(1, BASE.plusDays(1), BASE.plusDays(128), true))
                .thenReturn(IntStream.rangeClosed(1, 128).mapToObj(i -> day(i, true)).toList());
        when(repository.range(1, BASE.plusDays(129), BASE.plusDays(256), true))
                .thenReturn(List.of(day(129, false), day(130, true)));

        var dates = calendar.scheduleOffsets("CN", BASE, Set.of(-2, -1, 0, 1, 128, 129)).dates();
        assertEquals(Map.of(-2, BASE.minusDays(3), -1, BASE.minusDays(2), 0, BASE,
                1, BASE.plusDays(1), 128, BASE.plusDays(128), 129, BASE.plusDays(130)), dates);
        verify(repository).markets();
        verify(repository).range(1, BASE, BASE, true);
        verify(repository).range(1, BASE.minusDays(128), BASE.minusDays(1), false);
        verify(repository).range(1, BASE.plusDays(1), BASE.plusDays(128), true);
        verify(repository).range(1, BASE.plusDays(129), BASE.plusDays(256), true);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void stopsAtFirstMissingDayOnlyWhenAnOffsetNeedsThatDay() {
        when(repository.range(1, BASE.plusDays(1), BASE.plusDays(128), true))
                .thenReturn(List.of(day(1, true), day(3, true)));
        assertEquals(BASE.plusDays(1), calendar.scheduleOffsets("CN", BASE, Set.of(1)).dates().get(1));
        var offsets = Set.of(1, 2);
        var error = assertThrows(BizException.class, () -> calendar.scheduleOffsets("CN", BASE, offsets));
        assertEquals(BizResponseCode.TRADING_CALENDAR_COVERAGE_MISSING, error.getCode());
        assertEquals(BASE.plusDays(2), error.getApiMessage().arguments()[1]);
    }

    private static StoredDay day(int offset, boolean trading) {
        return new StoredDay(BASE.plusDays(offset), new Values(trading, null, Source.INIT, null), null);
    }
}
