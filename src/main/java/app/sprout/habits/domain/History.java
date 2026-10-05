package app.sprout.habits.domain;

import app.sprout.habits.config.HabitsProperties;
import app.sprout.habits.domain.HabitEngine.Trade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * A customer's trading history, read from the order service (a year at a time, from the day their
 * account opened), and the market's session (trade dates are sessions, so "today" is too).
 */
@Component
public class History {

    static final Duration DEADLINE = Duration.ofSeconds(5);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final HabitsProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    public History(HabitsProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    public LocalDate today() {
        return LocalDate.parse(get(props.marketdataUrl() + "/v1/market", false).path("sessionDate").asText());
    }

    /** The day the customer's account opened, or NO_ACCOUNT. */
    public LocalDate opened(UUID user) {
        JsonNode a = get(props.accountsUrl() + "/internal/v1/accounts/" + user, true);
        if (a == null) {
            throw new ApiException(ErrorCode.NO_ACCOUNT, "Open a Sprout account first.");
        }
        return OffsetDateTime.parse(a.path("openedAt").asText()).atZoneSameInstant(IST).toLocalDate();
    }

    public List<Trade> trades(UUID user, LocalDate from, LocalDate to) {
        List<Trade> out = new ArrayList<>();
        for (LocalDate start = from.isAfter(to) ? to : from; !start.isAfter(to); start = start.plusYears(1).plusDays(1)) {
            LocalDate end = start.plusYears(1).isAfter(to) ? to : start.plusYears(1);
            for (JsonNode e : get(props.omsUrl() + "/internal/v1/executions?from=" + start + "&to=" + end + "&userId=" + user, true)
                    .path("executions")) {
                out.add(new Trade(LocalDate.parse(e.path("tradeDate").asText()), e.path("symbol").asText(), e.path("side").asText().equals("BUY"),
                        e.path("product").asText().equals("MIS"), e.path("quantity").asLong(), Money.paise(e.path("value").asText()),
                        e.hasNonNull("tag") ? e.path("tag").asText() : null));
            }
        }
        return out;
    }

    /** GETs JSON; with {@code withKey}, as a Sprout service. A 404 is null; anything else unexpected is a 503. */
    private JsonNode get(String url, boolean withKey) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(DEADLINE).GET();
            if (withKey) {
                req.header("X-Service-Key", props.serviceKey());
            }
            HttpResponse<String> res = http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (res.statusCode() == 404) {
                return null;
            }
            if (res.statusCode() != 200) {
                throw unavailable();
            }
            return json.readTree(res.body());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw unavailable();
        }
    }

    static ApiException unavailable() {
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Your history can't be read right now. Try again shortly.", 5, Map.of());
    }
}
