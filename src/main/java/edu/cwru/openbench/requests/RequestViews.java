package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.ledger.LineSpec;
import edu.cwru.openbench.ledger.Pricing;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.TeamSummary;
import edu.cwru.openbench.settings.PlannerSettings;
import edu.cwru.openbench.settings.SettingsService;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns rows into the module's DTOs and prices lines. A Draft line is quoted now, at the current
 * settings, with {@link Pricing#quote}; a submitted line shows its locked Quote. A type without
 * pricing settings has no Quote. Shared by the services of this package.
 */
@Component
class RequestViews {

    private final MemberService members;
    private final InventoryQueries inventory;
    private final SettingsService settings;
    private final Clock clock;

    RequestViews(MemberService members, InventoryQueries inventory, SettingsService settings, Clock clock) {
        this.members = members;
        this.inventory = inventory;
        this.settings = settings;
        this.clock = clock;
    }

    /** Starts a set of conversions that reads the settings, names, and Teams at most once each. */
    Session session() {
        return new Session();
    }

    /** The Quote of a line at these settings, or empty when its type has no pricing settings. */
    Optional<Credits> currentQuote(RequestLineRow line, PlannerSettings current) {
        return current.pricingOf(new ResourceTypeId(line.typeId())).map(pricing -> Pricing.quote(
                new LineSpec(line.amount(), List.of(window(line)), line.priority(), clock.getZone()),
                pricing,
                current.priorityPremium()).amount());
    }

    static TimeWindow window(RequestLineRow line) {
        return new TimeWindow(line.startsAt(), line.endsAt());
    }

    /** The sum of the Quotes of the lines with this Required mark; a line without a Quote counts 0.00. */
    static Credits sum(List<RequestLine> lines, boolean required) {
        return lines.stream()
                .filter(line -> line.required() == required && line.quote() != null)
                .map(RequestLine::quote)
                .reduce(Credits.ZERO, Credits::plus);
    }

    /** One conversion pass, caching what several rows share. Not thread-safe; use within one call. */
    final class Session {

        private PlannerSettings current;
        private final Map<Long, TeamSummary> teams = new HashMap<>();
        private final Map<Long, String> typeNames = new HashMap<>();
        private final Map<Long, String> resourceNames = new HashMap<>();

        Request request(RequestRow row) {
            return new Request(new RequestId(row.id()), team(row.teamId()), row.state(), row.createdAt(), row.submittedAt());
        }

        List<RequestLine> lines(RequestRow request, List<RequestLineRow> rows) {
            return rows.stream().map(row -> line(request, row)).toList();
        }

        RequestLine line(RequestRow request, RequestLineRow row) {
            Credits quote;
            if (request.isDraft()) {
                quote = currentQuote(row, settings()).orElse(null);
            } else {
                quote = row.lockedQuote() == null ? null : new Credits(row.lockedQuote());
            }
            Long named = row.namedResourceId();
            return new RequestLine(
                    new RequestLineId(row.id()),
                    new RequestId(row.requestId()),
                    new ResourceTypeId(row.typeId()),
                    typeNames.computeIfAbsent(row.typeId(), id -> inventory.type(new ResourceTypeId(id)).name()),
                    row.allocationClass(),
                    row.amount(),
                    window(row),
                    named == null ? null : new ResourceId(named),
                    named == null ? null
                            : resourceNames.computeIfAbsent(named, id -> inventory.resource(new ResourceId(id)).name()),
                    row.required(),
                    row.state(),
                    quote);
        }

        private TeamSummary team(Long id) {
            return teams.computeIfAbsent(id, team -> members.team(new TeamId(team)));
        }

        private PlannerSettings settings() {
            if (current == null) {
                current = settings.current();
            }
            return current;
        }
    }
}
