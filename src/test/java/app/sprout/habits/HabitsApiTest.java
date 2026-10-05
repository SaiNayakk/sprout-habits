package app.sprout.habits;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Habits on a real Postgres, against stand-ins for accounts, market data and each customer's trading history. */
@Testcontainers
@SpringBootTest(properties = "spring.config.name=habits")
@AutoConfigureMockMvc
class HabitsApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, List<Map<String, Object>>> HISTORY = new ConcurrentHashMap<>();   // userId -> executions
    static final HttpServer STANDINS = standIns();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + STANDINS.getAddress().getPort();
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=habits");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("sprout.habits.marketdata-url", () -> base);
        r.add("sprout.habits.oms-url", () -> base);
        r.add("sprout.habits.accounts-url", () -> base);
        r.add("sprout.habits.squad-size", () -> 3);
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-07T05:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.HABITS_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;

    /** A customer with an account who bought delivery shares in each of these months (day 5), for this much each. */
    static UUID investor(long rupeesEach, String... months) {
        UUID u = UUID.randomUUID();
        List<Map<String, Object>> trades = new ArrayList<>();
        for (String m : months) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("tradeDate", m + "-05");
            e.put("symbol", "HARBOR");
            e.put("side", "BUY");
            e.put("product", "CNC");
            e.put("quantity", 1);
            e.put("value", rupeesEach + ".00");
            trades.add(e);
        }
        HISTORY.put(u.toString(), trades);
        return u;
    }

    ResultActions as(UUID u, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("X-User-Id", u.toString()));
    }

    JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void myHabitsAreWorkedOutFromWhatIDid() throws Exception {
        UUID u = investor(5000, "2026-07", "2026-08", "2026-09", "2026-10");
        JsonNode h = body(as(u, get("/v1/habits/me")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(h.path("streak").path("months").asInt()).isEqualTo(4);
        assertThat(h.path("level").path("name").asText()).isEqualTo("Sapling");
        assertThat(h.path("badges").findValuesAsText("code")).containsExactly("FIRST_INVESTMENT", "STREAK_3");
        assertThat(h.path("points").path("vested").asInt()).isEqualTo(300);   // July to September; October's is pending
        assertThat(h.path("points").path("pending").asInt()).isEqualTo(100);
        assertThat(h.has("nudge")).isFalse();
        as(UUID.randomUUID(), get("/v1/habits/me")).andExpect(status().isNotFound()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
        mvc.perform(get("/v1/habits/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void thisMonthsChallengesAndTheYearWrapped() throws Exception {
        UUID u = investor(5000, "2026-07", "2026-08", "2026-09", "2026-10");
        JsonNode c = body(as(u, get("/v1/challenges")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(c.path("month").asText()).as("the market's month").isEqualTo("2026-10");
        assertThat(c.path("challenges").findValuesAsText("code")).containsExactly("INVEST_TWO_DAYS", "PLAN_ON_TRACK", "GROW_A_POT", "THREE_SHARES");
        assertThat(c.path("challenges").get(0).path("progress").asInt()).as("one day so far").isEqualTo(1);
        assertThat(c.path("challenges").findValues("completed")).allMatch(n -> !n.asBoolean());
        JsonNode w = body(as(u, get("/v1/wrapped")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(w.path("year").asInt()).isEqualTo(2026);
        assertThat(w.path("monthsInvested").asInt()).isEqualTo(4);
        assertThat(w.path("longestStreak").asInt()).isEqualTo(4);
        assertThat(w.path("invested").asText()).isEqualTo("20000.00");
        assertThat(w.path("topShare").path("symbol").asText()).isEqualTo("HARBOR");
        assertThat(w.path("title").asText()).isEqualTo("First Shoots");
        as(u, get("/v1/wrapped?year=2031")).andExpect(status().isBadRequest());
    }

    @Test
    void squadsRankByTheHabitNeverByMoneyAndShowRangesOnlyByChoice() throws Exception {
        UUID rich = investor(9_00_000, "2026-10");                                  // a lot of money, one month
        UUID steady = investor(1_000, "2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10");
        JsonNode squad = body(as(rich, post("/v1/squads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Monsoon savers\",\"nickname\":\"Ravi\"}")).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT));
        String code = squad.path("inviteCode").asText();
        assertThat(code).matches("[A-Z2-9]{8}");
        as(steady, post("/v1/squads/join").contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteCode\":\"" + code.toLowerCase() + "\",\"nickname\":\"Meera\"}")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT);
        as(steady, put("/v1/habits/me/privacy").contentType(MediaType.APPLICATION_JSON).content("{\"showInvestedRange\":true}"))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT);
        JsonNode board = body(as(rich, get("/v1/squads/" + squad.path("id").asText())).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT))
                .path("members");
        assertThat(board.get(0).path("nickname").asText()).as("six months of habit beats one big month").isEqualTo("Meera");
        assertThat(board.get(0).path("investedRange").asText()).isEqualTo("under ₹10,000");
        assertThat(board.get(1).has("investedRange")).as("Ravi didn't choose to show it").isFalse();
        assertThat(board.get(1).path("you").asBoolean()).isTrue();
        as(investor(1, "2026-10"), get("/v1/squads/" + squad.path("id").asText())).andExpect(status().isNotFound());
    }

    @Test
    void aSquadIsLimitedInSizeAndAnyoneCanLeave() throws Exception {
        UUID a = investor(100, "2026-10");
        JsonNode s = body(as(a, post("/v1/squads").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Trio\",\"nickname\":\"Asha\"}")));
        String join = "{\"inviteCode\":\"" + s.path("inviteCode").asText() + "\",\"nickname\":\"Friend\"}";
        as(investor(100, "2026-10"), post("/v1/squads/join").contentType(MediaType.APPLICATION_JSON).content(join)).andExpect(status().isOk());
        UUID third = investor(100, "2026-10");
        as(third, post("/v1/squads/join").contentType(MediaType.APPLICATION_JSON).content(join)).andExpect(status().isOk());
        as(third, post("/v1/squads/join").contentType(MediaType.APPLICATION_JSON).content(join)).andExpect(status().isOk());   // already in
        as(investor(100, "2026-10"), post("/v1/squads/join").contentType(MediaType.APPLICATION_JSON).content(join))
                .andExpect(status().isConflict()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("SQUAD_FULL"));   // 3 in this test
        as(third, post("/v1/squads/" + s.path("id").asText() + "/leave")).andExpect(status().isNoContent());
        as(third, post("/v1/squads/" + s.path("id").asText() + "/leave")).andExpect(status().isNotFound());
        as(a, post("/v1/squads/join").contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":\"NOSUCHCD\",\"nickname\":\"Asha\"}"))
                .andExpect(status().isNotFound());
        assertThat(body(as(a, get("/v1/squads")).andExpect(MATCHES_CONTRACT)).path("squads").size()).isEqualTo(1);
    }

    @Test
    void readinessGivesPlainAdviceAndRemembersIt() throws Exception {
        UUID u = UUID.randomUUID();
        as(u, get("/v1/readiness")).andExpect(status().isNotFound());
        JsonNode r = body(as(u, put("/v1/readiness").contentType(MediaType.APPLICATION_JSON)
                .content("{\"emergencyFundMonths\":2,\"highInterestDebt\":true,\"horizonYears\":10}")).andExpect(status().isOk())
                .andExpect(MATCHES_CONTRACT));
        assertThat(r.path("ready").asBoolean()).isFalse();
        assertThat(r.path("advice").size()).isEqualTo(2);
        as(u, put("/v1/readiness").contentType(MediaType.APPLICATION_JSON)
                .content("{\"emergencyFundMonths\":6,\"highInterestDebt\":false,\"horizonYears\":10}"));
        assertThat(body(as(u, get("/v1/readiness")).andExpect(MATCHES_CONTRACT)).path("ready").asBoolean()).isTrue();
    }

    @Test
    void futureYouCompoundsAMonthlyAmount() throws Exception {
        JsonNode f = body(as(UUID.randomUUID(), get("/v1/future").param("monthly", "1000").param("years", "10")).andExpect(status().isOk())
                .andExpect(MATCHES_CONTRACT));
        assertThat(f.path("invested").asText()).isEqualTo("120000.00");
        JsonNode twelve = f.path("rates").get(2);
        assertThat(twelve.path("yearlyRatePercent").asInt()).isEqualTo(12);
        assertThat(twelve.path("byYear").size()).isEqualTo(10);
        // ₹1,000 a month for 10 years at 1% a month: 1000 x ((1.01^120 - 1) / 0.01) = 2,30,038.69
        assertThat(twelve.path("finalValue").asText()).isEqualTo("230038.69");
        as(UUID.randomUUID(), get("/v1/future").param("monthly", "1000").param("years", "50")).andExpect(status().isBadRequest());
    }

    // ── the stand-ins ────────────────────────────────────────────────────────

    static HttpServer standIns() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/v1/market", ex -> reply(ex, 200, Map.of("state", "OPEN", "sessionDate", "2026-10-07")));
            s.createContext("/internal/v1/accounts/", ex -> {
                String id = ex.getRequestURI().getPath().substring("/internal/v1/accounts/".length());
                reply(ex, HISTORY.containsKey(id) ? 200 : 404, Map.of("userId", id, "openedAt", "2026-01-01T00:00:00Z"));
            });
            s.createContext("/internal/v1/executions", ex -> {
                Map<String, String> q = new LinkedHashMap<>();
                for (String kv : ex.getRequestURI().getQuery().split("&")) {
                    String[] p = kv.split("=", 2);
                    q.put(p[0], p[1]);
                }
                List<Map<String, Object>> in = HISTORY.getOrDefault(q.get("userId"), List.of()).stream()
                        .filter(e -> ((String) e.get("tradeDate")).compareTo(q.get("from")) >= 0 && ((String) e.get("tradeDate")).compareTo(q.get("to")) <= 0)
                        .toList();
                reply(ex, 200, Map.of("executions", in));
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
