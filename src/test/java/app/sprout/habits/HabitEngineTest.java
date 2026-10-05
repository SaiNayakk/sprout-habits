package app.sprout.habits;

import static org.assertj.core.api.Assertions.assertThat;

import app.sprout.habits.domain.HabitEngine;
import app.sprout.habits.domain.HabitEngine.Badge;
import app.sprout.habits.domain.HabitEngine.Picture;
import app.sprout.habits.domain.HabitEngine.Trade;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The habit rules on small, hand-checked histories. */
class HabitEngineTest {

    static Trade buy(String date, String symbol) {
        return new Trade(LocalDate.parse(date), symbol, true, false, 1, 1000_00, null);
    }

    static Trade sip(String date) {
        return new Trade(LocalDate.parse(date), "HARBOR", true, false, 1, 1000_00, "sip:plan");
    }

    static Trade sell(String date, String symbol) {
        return new Trade(LocalDate.parse(date), symbol, false, false, 1, 1000_00, null);
    }

    static Trade intraday(String date) {
        return new Trade(LocalDate.parse(date), "KOSHA", true, true, 10, 3000_00, null);
    }

    static List<String> codes(Picture p) {
        return p.badges().stream().map(Badge::code).toList();
    }

    @Test
    void aStreakCountsMonthsWithAPurchaseAndThisMonthIsntOverYet() {
        Picture p = HabitEngine.picture(List.of(buy("2026-07-03", "HARBOR"), buy("2026-08-11", "HARBOR"), buy("2026-09-02", "INKWELL")),
                LocalDate.parse("2026-10-07"));
        assertThat(p.streak()).isEqualTo(3);
        assertThat(p.atRisk()).as("only the 7th").isFalse();
        assertThat(HabitEngine.picture(List.of(buy("2026-09-02", "INKWELL")), LocalDate.parse("2026-10-21")).atRisk()).isTrue();
        assertThat(codes(p)).containsExactly("FIRST_INVESTMENT", "STREAK_3");
    }

    @Test
    void aMissedMonthEndsTheStreakUnlessAFreezeCoversIt() {
        List<Trade> trades = new ArrayList<>();
        for (int m = 1; m <= 6; m++) {
            trades.add(buy("2026-0" + m + "-05", "HARBOR"));          // six months: earns a freeze
        }
        trades.add(buy("2026-08-05", "HARBOR"));                     // July missed: the freeze covers it
        Picture p = HabitEngine.picture(trades, LocalDate.parse("2026-08-20"));
        assertThat(p.streak()).isEqualTo(7);
        assertThat(p.freezes()).isZero();
        trades.add(buy("2026-10-05", "HARBOR"));                     // September missed, no freeze left
        Picture later = HabitEngine.picture(trades, LocalDate.parse("2026-10-10"));
        assertThat(later.streak()).isEqualTo(1);
        assertThat(later.longest()).isEqualTo(7);
        assertThat(later.monthsInvested()).isEqualTo(8);
        assertThat(later.level()).isEqualTo("Young tree");
        assertThat(later.nextLevel()).isEqualTo("Grove");
        assertThat(later.monthsToNext()).isEqualTo(4);
    }

    @Test
    void sellingDoesntBreakItAndIntradayDoesntCount() {
        Picture p = HabitEngine.picture(List.of(intraday("2026-09-01"), intraday("2026-10-01")), LocalDate.parse("2026-10-07"));
        assertThat(p.streak()).isZero();
        assertThat(p.level()).isEqualTo("Seedling");
        Picture q = HabitEngine.picture(List.of(buy("2026-09-01", "HARBOR"), sell("2026-09-20", "HARBOR"), buy("2026-10-02", "HARBOR")),
                LocalDate.parse("2026-10-07"));
        assertThat(q.streak()).isEqualTo(2);
    }

    @Test
    void pointsVestAfterThirtyDaysAndAreForfeitedIfSoldSooner() {
        Picture p = HabitEngine.picture(List.of(
                buy("2026-08-01", "HARBOR"),       // 100, vested (held)
                buy("2026-09-01", "INKWELL"),      // 100, forfeited: sold within 30 days
                sell("2026-09-15", "INKWELL"),
                sip("2026-10-01")),                // 100 for October + 50 for the instalment, pending
                LocalDate.parse("2026-10-07"));
        assertThat(p.vested()).isEqualTo(100);
        assertThat(p.forfeited()).isEqualTo(100);
        assertThat(p.pending()).isEqualTo(150);
        assertThat(codes(p)).contains("FIRST_PLAN_INSTALMENT");
    }

    @Test
    void milestoneBadgesForFiveSharesAndAYearHeld() {
        List<Trade> trades = new ArrayList<>(List.of(buy("2025-09-01", "HARBOR")));
        for (String s : List.of("INKWELL", "KOSHA", "TALLY", "MERIDIAN")) {
            trades.add(buy("2026-09-10", s));
        }
        Picture p = HabitEngine.picture(trades, LocalDate.parse("2026-10-07"));
        assertThat(codes(p)).contains("FIVE_SHARES", "HELD_A_YEAR");
        Badge year = p.badges().stream().filter(b -> b.code().equals("HELD_A_YEAR")).findFirst().orElseThrow();
        assertThat(year.earnedOn()).isEqualTo(LocalDate.parse("2026-09-01"));
    }

    @Test
    void lotsOfTradingLatelyIsANudgeAndRangesNeverShowAmounts() {
        List<Trade> busy = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            busy.add(intraday("2026-10-0" + (1 + i % 7)));
        }
        assertThat(HabitEngine.picture(busy, LocalDate.parse("2026-10-07")).overtrading()).isTrue();
        assertThat(HabitEngine.picture(busy.subList(0, 20), LocalDate.parse("2026-10-07")).overtrading()).isFalse();
        assertThat(HabitEngine.range(9_999_00)).isEqualTo("under ₹10,000");
        assertThat(HabitEngine.range(75_000_00)).isEqualTo("₹50,000 to ₹2,00,000");
    }
}
