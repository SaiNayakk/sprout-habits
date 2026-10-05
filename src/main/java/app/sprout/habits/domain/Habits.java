package app.sprout.habits.domain;

import app.sprout.habits.config.HabitsProperties;
import app.sprout.habits.domain.HabitEngine.Picture;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** The habits API's work: pictures, privacy, Future You, readiness and squads. */
@Service
public class Habits {

    public record Member(UUID userId, String nickname, int rank, int streak, int last12, String range, boolean you) {}

    public record Squad(UUID id, String name, String inviteCode, List<Member> members) {}

    public record Readiness(boolean ready, List<String> advice, Instant checkedAt) {}

    static final int[] RATES = {6, 10, 12};
    static final String CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no 0/O or 1/I to misread

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final History history;
    private final HabitsProperties props;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public Habits(JdbcClient db, TransactionTemplate tx, Clock clock, History history, HabitsProperties props, ObjectMapper json) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.history = history;
        this.props = props;
        this.json = json;
    }

    // ── the picture ──────────────────────────────────────────────────────────

    public Picture picture(UUID user) {
        LocalDate opened = history.opened(user);
        LocalDate today = history.today();
        return HabitEngine.picture(history.trades(user, opened, today), today);
    }

    public boolean setPrivacy(UUID user, boolean show) {
        db.sql("INSERT INTO privacy (user_id, show_invested_range) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET show_invested_range = ?")
                .params(user, show, show).update();
        return show;
    }

    // ── Future You ───────────────────────────────────────────────────────────

    /** Monthly contributions compounded monthly: value at the end of each year, at each illustrative rate. */
    public Map<String, Object> future(String monthlyInput, int years) {
        if (years < 1 || years > 40) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "years is 1 to 40.");
        }
        long monthly = Money.paise(monthlyInput);
        if (monthly < 100_00 || monthly > 10_00_000_00L) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "monthly is ₹100 to ₹10,00,000.");
        }
        List<Map<String, Object>> rates = new ArrayList<>();
        for (int rate : RATES) {
            BigDecimal r = BigDecimal.valueOf(rate).divide(BigDecimal.valueOf(1200), MathContext.DECIMAL64);
            BigDecimal value = BigDecimal.ZERO;
            List<String> byYear = new ArrayList<>();
            for (int month = 1; month <= years * 12; month++) {
                value = value.multiply(BigDecimal.ONE.add(r)).add(BigDecimal.valueOf(monthly));   // each month: growth, then the new amount
                if (month % 12 == 0) {
                    byYear.add(Money.rupees(value.setScale(0, RoundingMode.HALF_UP).longValueExact()));
                }
            }
            rates.add(Map.of("yearlyRatePercent", rate, "finalValue", byYear.get(byYear.size() - 1), "byYear", byYear));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("monthly", Money.rupees(monthly));
        m.put("years", years);
        m.put("invested", Money.rupees(monthly * 12 * years));
        m.put("rates", rates);
        return m;
    }

    // ── readiness ────────────────────────────────────────────────────────────

    public Readiness checkReadiness(UUID user, Integer emergencyMonths, Boolean debt, Integer horizon) {
        if (emergencyMonths == null || emergencyMonths < 0 || emergencyMonths > 120 || debt == null || horizon == null || horizon < 0 || horizon > 60) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "emergencyFundMonths (0 to 120), highInterestDebt and horizonYears (0 to 60) are required.");
        }
        List<String> advice = new ArrayList<>();
        if (emergencyMonths < 6) {
            advice.add("Build an emergency fund first: six months of expenses, somewhere you can reach it any day.");
        }
        if (debt) {
            advice.add("Pay off credit card and personal loan debt first: it usually costs more than investing earns.");
        }
        if (horizon < 3) {
            advice.add("Money you'll need within three years is safer kept out of shares: prices can fall when you need to sell.");
        }
        boolean ready = advice.isEmpty();
        if (ready) {
            advice.add("You're ready. Start small and regular: a monthly plan builds the habit.");
        }
        Instant now = clock.instant();
        db.sql("""
                        INSERT INTO readiness (user_id, emergency_fund_months, high_interest_debt, horizon_years, ready, advice, checked_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (user_id) DO UPDATE SET emergency_fund_months = EXCLUDED.emergency_fund_months,
                            high_interest_debt = EXCLUDED.high_interest_debt, horizon_years = EXCLUDED.horizon_years, ready = EXCLUDED.ready,
                            advice = EXCLUDED.advice, checked_at = EXCLUDED.checked_at""")
                .params(user, emergencyMonths, debt, horizon, ready, write(advice), Timestamp.from(now)).update();
        return new Readiness(ready, advice, now);
    }

    public Readiness readiness(UUID user) {
        return db.sql("SELECT ready, advice, checked_at FROM readiness WHERE user_id = ?").param(user)
                .query((rs, n) -> new Readiness(rs.getBoolean(1), read(rs.getString(2)), rs.getTimestamp(3).toInstant())).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "You haven't checked your readiness yet."));
    }

    // ── squads ───────────────────────────────────────────────────────────────

    public Squad createSquad(UUID user, String name, String nickname) {
        String n = clean(name, 2, 40, "name");
        String nick = clean(nickname, 2, 24, "nickname");
        history.opened(user);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        for (int attempt = 0; ; attempt++) {
            String code = code();
            try {
                tx.executeWithoutResult(s -> {
                    db.sql("INSERT INTO squads (id, name, invite_code, created_by, created_at) VALUES (?, ?, ?, ?, ?)")
                            .params(id, n, code, user, Timestamp.from(now)).update();
                    db.sql("INSERT INTO members (squad_id, user_id, nickname, joined_at) VALUES (?, ?, ?, ?)")
                            .params(id, user, nick, Timestamp.from(now)).update();
                });
                return new Squad(id, n, code, List.of());
            } catch (DuplicateKeyException e) {
                if (attempt == 5) {
                    throw e;
                }
            }
        }
    }

    public Squad join(UUID user, String inviteCode, String nickname) {
        String nick = clean(nickname, 2, 24, "nickname");
        history.opened(user);
        String code = inviteCode == null ? "" : inviteCode.trim().toUpperCase();
        Squad s = db.sql("SELECT id, name, invite_code FROM squads WHERE invite_code = ?").param(code)
                .query((rs, n) -> new Squad(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), List.of())).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No squad has that invite code."));
        tx.executeWithoutResult(t -> {
            db.sql("SELECT id FROM squads WHERE id = ? FOR UPDATE").param(s.id()).query(UUID.class).single();   // one join at a time per squad
            if (isMember(s.id(), user)) {
                return;
            }
            int size = db.sql("SELECT COUNT(*) FROM members WHERE squad_id = ?").param(s.id()).query(Integer.class).single();
            if (size >= props.squadSize()) {
                throw new ApiException(ErrorCode.SQUAD_FULL, "This squad already has " + size + " members, the most there can be.");
            }
            db.sql("INSERT INTO members (squad_id, user_id, nickname, joined_at) VALUES (?, ?, ?, ?)")
                    .params(s.id(), user, nick, Timestamp.from(clock.instant())).update();
        });
        return s;
    }

    public List<Squad> mySquads(UUID user) {
        return db.sql("SELECT s.id, s.name, s.invite_code FROM squads s JOIN members m ON m.squad_id = s.id WHERE m.user_id = ? ORDER BY s.created_at")
                .param(user).query((rs, n) -> new Squad(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), List.of())).list();
    }

    /** The squad's board: ranked by the habit (streak, then months invested in the last 12), never by money. */
    public Squad board(UUID user, UUID squadId) {
        if (!isMember(squadId, user)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No such squad of yours.");
        }
        Squad s = db.sql("SELECT id, name, invite_code FROM squads WHERE id = ?").param(squadId)
                .query((rs, n) -> new Squad(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), List.of())).single();
        List<Object[]> members = db.sql("""
                        SELECT m.user_id, m.nickname, COALESCE(p.show_invested_range, false) FROM members m
                        LEFT JOIN privacy p ON p.user_id = m.user_id WHERE m.squad_id = ?""")
                .param(squadId).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3)}).list();
        List<Member> ranked = new ArrayList<>();
        for (Object[] m : members) {
            UUID who = (UUID) m[0];
            Picture p = picture(who);
            ranked.add(new Member(who, (String) m[1], 0, p.streak(), p.monthsInvestedLast12(),
                    (Boolean) m[2] ? HabitEngine.range(p.investedPaise()) : null, who.equals(user)));
        }
        ranked.sort(Comparator.comparingInt(Member::streak).reversed().thenComparing(Comparator.comparingInt(Member::last12).reversed())
                .thenComparing(Member::nickname));
        List<Member> out = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Member m = ranked.get(i);
            out.add(new Member(m.userId(), m.nickname(), i + 1, m.streak(), m.last12(), m.range(), m.you()));
        }
        return new Squad(s.id(), s.name(), s.inviteCode(), out);
    }

    public void leave(UUID user, UUID squadId) {
        if (db.sql("DELETE FROM members WHERE squad_id = ? AND user_id = ?").params(squadId, user).update() == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No such squad of yours.");
        }
    }

    private boolean isMember(UUID squadId, UUID user) {
        return db.sql("SELECT 1 FROM members WHERE squad_id = ? AND user_id = ?").params(squadId, user).query(Integer.class).optional().isPresent();
    }

    private String code() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            b.append(CODE_LETTERS.charAt(random.nextInt(CODE_LETTERS.length())));
        }
        return b.toString();
    }

    private static String clean(String value, int min, int max, String field) {
        String v = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (v.length() < min || v.length() > max) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " is " + min + " to " + max + " characters.");
        }
        return v;
    }

    private String write(List<String> list) {
        try {
            return json.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> read(String s) {
        try {
            return json.readValue(s, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
