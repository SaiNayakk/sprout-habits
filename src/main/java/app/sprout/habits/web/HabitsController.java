package app.sprout.habits.web;

import app.sprout.habits.domain.ApiException;
import app.sprout.habits.domain.ErrorCode;
import app.sprout.habits.domain.HabitEngine.Picture;
import app.sprout.habits.domain.Habits;
import app.sprout.habits.domain.Habits.Readiness;
import app.sprout.habits.domain.Habits.Squad;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The habits API (habits-v1.yaml). */
@RestController
public class HabitsController {

    public record PrivacyRequest(Boolean showInvestedRange) {}

    public record ReadinessRequest(Integer emergencyFundMonths, Boolean highInterestDebt, Integer horizonYears) {}

    public record NewSquad(String name, String nickname) {}

    public record JoinRequest(String inviteCode, String nickname) {}

    static final String NUDGE = "You've traded a lot in the last few days. Investing grows with time in the market, not trips in and out: "
            + "consider a cooling-off day.";

    private final Habits habits;

    public HabitsController(Habits habits) {
        this.habits = habits;
    }

    @GetMapping("/v1/habits/me")
    public Map<String, Object> me(@RequestHeader(value = "X-User-Id", required = false) String user) {
        Picture p = habits.picture(userId(user));
        Map<String, Object> level = new LinkedHashMap<>();
        level.put("name", p.level());
        level.put("monthsInvested", p.monthsInvested());
        if (p.nextLevel() != null) {
            level.put("nextName", p.nextLevel());
            level.put("monthsToNext", p.monthsToNext());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("streak", Map.of("months", p.streak(), "longest", p.longest(), "freezes", p.freezes(), "atRisk", p.atRisk()));
        m.put("level", level);
        m.put("badges", p.badges().stream().map(b -> Map.of("code", b.code(), "name", b.name(), "earnedOn", b.earnedOn().toString())).toList());
        m.put("points", Map.of("vested", p.vested(), "pending", p.pending(), "forfeited", p.forfeited()));
        if (p.overtrading()) {
            m.put("nudge", Map.of("code", "OVERTRADING", "message", NUDGE));
        }
        return m;
    }

    @PutMapping("/v1/habits/me/privacy")
    public Map<String, Object> privacy(@RequestHeader(value = "X-User-Id", required = false) String user, @RequestBody PrivacyRequest req) {
        if (req.showInvestedRange() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "showInvestedRange is required.");
        }
        return Map.of("showInvestedRange", habits.setPrivacy(userId(user), req.showInvestedRange()));
    }

    @GetMapping("/v1/future")
    public Map<String, Object> future(@RequestHeader(value = "X-User-Id", required = false) String user, @RequestParam String monthly,
                                      @RequestParam int years) {
        userId(user);
        return habits.future(monthly, years);
    }

    @PutMapping("/v1/readiness")
    public Map<String, Object> checkReadiness(@RequestHeader(value = "X-User-Id", required = false) String user, @RequestBody ReadinessRequest req) {
        return readiness(habits.checkReadiness(userId(user), req.emergencyFundMonths(), req.highInterestDebt(), req.horizonYears()));
    }

    @GetMapping("/v1/readiness")
    public Map<String, Object> getReadiness(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return readiness(habits.readiness(userId(user)));
    }

    @PostMapping("/v1/squads")
    public ResponseEntity<Map<String, Object>> createSquad(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                           @RequestBody NewSquad req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(squad(habits.createSquad(userId(user), req.name(), req.nickname())));
    }

    @GetMapping("/v1/squads")
    public Map<String, Object> squads(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return Map.of("squads", habits.mySquads(userId(user)).stream().map(HabitsController::squad).toList());
    }

    @PostMapping("/v1/squads/join")
    public Map<String, Object> join(@RequestHeader(value = "X-User-Id", required = false) String user, @RequestBody JoinRequest req) {
        return squad(habits.join(userId(user), req.inviteCode(), req.nickname()));
    }

    @GetMapping("/v1/squads/{id}")
    public Map<String, Object> board(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return squad(habits.board(userId(user), id));
    }

    @PostMapping("/v1/squads/{id}/leave")
    public ResponseEntity<Void> leave(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        habits.leave(userId(user), id);
        return ResponseEntity.noContent().build();
    }

    static Map<String, Object> readiness(Readiness r) {
        return Map.of("ready", r.ready(), "advice", r.advice(), "checkedAt", r.checkedAt().toString());
    }

    static Map<String, Object> squad(Squad s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id().toString());
        m.put("name", s.name());
        m.put("inviteCode", s.inviteCode());
        m.put("members", s.members().stream().map(x -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rank", x.rank());
            out.put("nickname", x.nickname());
            out.put("streakMonths", x.streak());
            out.put("monthsInvestedLast12", x.last12());
            if (x.range() != null) {
                out.put("investedRange", x.range());
            }
            out.put("you", x.you());
            return out;
        }).toList());
        return m;
    }

    static UUID userId(String header) {
        try {
            return UUID.fromString(header);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in first.");
        }
    }
}
