package app.sprout.habits.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A customer's habit picture, worked out from their executed trades alone: the same trades always give
 * the same picture, so it can't drift from what they did. Only delivery purchases build the habit;
 * selling doesn't break it, and intraday trading doesn't count towards it.
 */
public final class HabitEngine {

    /** One executed trade, oldest first. {@code tag} is set on a plan's instalment (sip:...). */
    public record Trade(LocalDate date, String symbol, boolean buy, boolean intraday, long quantity, long valuePaise, String tag) {
        boolean investment() {
            return buy && !intraday;
        }
    }

    public record Badge(String code, String name, LocalDate earnedOn) {}

    public record Picture(int streak, int longest, int freezes, boolean atRisk, int monthsInvested, String level, String nextLevel,
                          Integer monthsToNext, List<Badge> badges, int vested, int pending, int forfeited, boolean overtrading,
                          int monthsInvestedLast12, long investedPaise) {}

    static final int FREEZE_EVERY = 6;
    static final int MAX_FREEZES = 2;
    static final int AT_RISK_AFTER_DAY = 20;
    static final int POINTS_PER_MONTH = 100;
    static final int POINTS_PER_INSTALMENT = 50;
    static final int VEST_DAYS = 30;
    static final int OVERTRADING_TRADES = 20;
    static final int OVERTRADING_DAYS = 7;   // about five sessions

    /** Shares bought on one day, and how many of them are still held. */
    private static final class Lot {
        final LocalDate bought;
        long left;

        Lot(LocalDate bought, long quantity) {
            this.bought = bought;
            this.left = quantity;
        }
    }

    static final String[] LEVELS = {"Seedling", "Sapling", "Young tree", "Grove", "Forest"};
    static final int[] LEVEL_FROM = {0, 3, 6, 12, 24};

    private HabitEngine() {}

    public static Picture picture(List<Trade> trades, LocalDate today) {
        YearMonth now = YearMonth.from(today);
        TreeSet<YearMonth> invested = new TreeSet<>();
        Map<YearMonth, LocalDate> firstBuyIn = new HashMap<>();
        for (Trade t : trades) {
            if (t.investment()) {
                YearMonth m = YearMonth.from(t.date());
                invested.add(m);
                firstBuyIn.putIfAbsent(m, t.date());
            }
        }
        List<Badge> badges = new ArrayList<>();
        Map<String, Badge> earned = new LinkedHashMap<>();

        // streak, freezes, and the streak badges
        int streak = 0;
        int longest = 0;
        int freezes = 0;
        int sinceFreeze = 0;
        if (!invested.isEmpty()) {
            for (YearMonth m = invested.first(); !m.isAfter(now); m = m.plusMonths(1)) {
                if (invested.contains(m)) {
                    streak++;
                    sinceFreeze++;
                    if (sinceFreeze % FREEZE_EVERY == 0 && freezes < MAX_FREEZES) {
                        freezes++;
                    }
                    longest = Math.max(longest, streak);
                    for (int n : new int[] {3, 6, 12}) {
                        if (streak == n) {
                            earned.putIfAbsent("STREAK_" + n, new Badge("STREAK_" + n, n + " month streak", firstBuyIn.get(m)));
                        }
                    }
                } else if (m.equals(now)) {
                    break;   // this month isn't over: no purchase yet doesn't cost anything yet
                } else if (freezes > 0) {
                    freezes--;   // a freeze keeps the streak through a missed month
                } else {
                    streak = 0;
                    sinceFreeze = 0;
                }
            }
        }
        boolean atRisk = streak > 0 && !invested.contains(now) && today.getDayOfMonth() > AT_RISK_AFTER_DAY;

        // level
        int months = invested.size();
        int level = 0;
        for (int i = 0; i < LEVELS.length; i++) {
            if (months >= LEVEL_FROM[i]) {
                level = i;
            }
        }
        String next = level + 1 < LEVELS.length ? LEVELS[level + 1] : null;
        Integer toNext = next == null ? null : LEVEL_FROM[level + 1] - months;

        // the other badges, walking the trades in order
        Map<String, Long> held = new HashMap<>();
        Map<String, Deque<Lot>> lots = new HashMap<>();   // per share, oldest first
        for (Trade t : trades) {
            if (t.intraday()) {
                continue;
            }
            if (t.buy()) {
                earned.putIfAbsent("FIRST_INVESTMENT", new Badge("FIRST_INVESTMENT", "First investment", t.date()));
                if (t.tag() != null && t.tag().startsWith("sip:")) {
                    earned.putIfAbsent("FIRST_PLAN_INSTALMENT", new Badge("FIRST_PLAN_INSTALMENT", "First plan instalment", t.date()));
                }
                held.merge(t.symbol(), t.quantity(), Long::sum);
                lots.computeIfAbsent(t.symbol(), k -> new ArrayDeque<>()).addLast(new Lot(t.date(), t.quantity()));
                if (held.values().stream().filter(q -> q > 0).count() >= 5) {
                    earned.putIfAbsent("FIVE_SHARES", new Badge("FIVE_SHARES", "Five different shares", t.date()));
                }
            } else {
                held.merge(t.symbol(), -t.quantity(), Long::sum);
                long left = t.quantity();
                Deque<Lot> l = lots.getOrDefault(t.symbol(), new ArrayDeque<>());
                while (left > 0 && !l.isEmpty()) {
                    Lot lot = l.peekFirst();
                    if (ChronoUnit.DAYS.between(lot.bought, t.date()) >= 365) {
                        earned.putIfAbsent("HELD_A_YEAR", new Badge("HELD_A_YEAR", "Held for a year", lot.bought.plusDays(365)));
                    }
                    long used = Math.min(left, lot.left);
                    lot.left -= used;
                    left -= used;
                    if (lot.left == 0) {
                        l.pollFirst();
                    }
                }
            }
        }
        for (Deque<Lot> l : lots.values()) {
            for (Lot lot : l) {
                if (ChronoUnit.DAYS.between(lot.bought, today) >= 365) {
                    Badge b = new Badge("HELD_A_YEAR", "Held for a year", lot.bought.plusDays(365));
                    earned.merge("HELD_A_YEAR", b, (a, c) -> a.earnedOn().isBefore(c.earnedOn()) ? a : c);
                }
            }
        }
        badges.addAll(earned.values());
        badges.sort((a, b) -> a.earnedOn().compareTo(b.earnedOn()));

        // points: 100 for each month's first purchase, 50 for each plan instalment; vest if not sold within 30 days
        int vested = 0;
        int pending = 0;
        int forfeited = 0;
        Map<YearMonth, Boolean> monthPointGiven = new HashMap<>();
        for (Trade t : trades) {
            if (!t.investment()) {
                continue;
            }
            int points = 0;
            if (monthPointGiven.putIfAbsent(YearMonth.from(t.date()), true) == null) {
                points += POINTS_PER_MONTH;
            }
            if (t.tag() != null && t.tag().startsWith("sip:")) {
                points += POINTS_PER_INSTALMENT;
            }
            if (points == 0) {
                continue;
            }
            LocalDate vests = t.date().plusDays(VEST_DAYS);
            boolean soldSoon = trades.stream().anyMatch(s -> !s.buy() && !s.intraday() && s.symbol().equals(t.symbol())
                    && s.date().isAfter(t.date()) && !s.date().isAfter(vests));
            if (soldSoon) {
                forfeited += points;
            } else if (!today.isBefore(vests)) {
                vested += points;
            } else {
                pending += points;
            }
        }

        // a nudge, not a ban: lots of intraday or selling lately
        long busy = trades.stream().filter(t -> (t.intraday() || !t.buy()) && !t.date().isBefore(today.minusDays(OVERTRADING_DAYS))).count();

        int last12 = (int) invested.stream().filter(m -> !m.isBefore(now.minusMonths(11))).count();
        long net = 0;
        for (Trade t : trades) {
            if (!t.intraday()) {
                net += t.buy() ? t.valuePaise() : -t.valuePaise();
            }
        }
        return new Picture(streak, longest, freezes, atRisk, months, LEVELS[level], next, toNext, badges, vested, pending, forfeited,
                busy > OVERTRADING_TRADES, last12, Math.max(0, net));
    }

    /** A range for how much someone has invested, never the amount. */
    public static String range(long paise) {
        long rupees = paise / 100;
        if (rupees < 10_000) {
            return "under ₹10,000";
        } else if (rupees < 50_000) {
            return "₹10,000 to ₹50,000";
        } else if (rupees < 2_00_000) {
            return "₹50,000 to ₹2,00,000";
        } else if (rupees < 10_00_000) {
            return "₹2,00,000 to ₹10,00,000";
        }
        return "over ₹10,00,000";
    }
}
