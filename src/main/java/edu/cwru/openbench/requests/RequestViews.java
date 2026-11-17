package edu.cwru.openbench.requests;

import edu.cwru.openbench.allocation.AllocationQueries;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.ServiceTierId;
import edu.cwru.openbench.common.SkillId;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.ledger.LineSpec;
import edu.cwru.openbench.ledger.Pricing;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.TeamSummary;
import edu.cwru.openbench.settings.PlannerSettings;
import edu.cwru.openbench.settings.ServiceTier;
import edu.cwru.openbench.settings.SettingsService;
import edu.cwru.openbench.settings.TypePricing;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns rows into the module's DTOs and prices lines. A Draft line is quoted now, at the current
 * settings and the type's fill in its window (the demand surcharge), with {@link Pricing#quote}; a
 * submitted line shows its locked Quote. A type without pricing settings has no Quote. Shared by the
 * services of this package.
 */
@Component
class RequestViews {

    private final MemberService members;
    private final InventoryQueries inventory;
    private final SettingsService settings;
    private final Clock clock;
    private final AllocationQueries allocation;

    RequestViews(MemberService members, InventoryQueries inventory, SettingsService settings, Clock clock,
            AllocationQueries allocation) {
        this.members = members;
        this.inventory = inventory;
        this.settings = settings;
        this.clock = clock;
        this.allocation = allocation;
    }

    /** Starts a set of conversions that reads the settings, names, and Teams at most once each. */
    Session session() {
        return new Session();
    }

    /**
     * The Quote of a line at these settings and the type's fill now, or empty when its type has no
     * pricing settings. Submission locks it.
     */
    Optional<Credits> currentQuote(RequestLineRow line, PlannerSettings current) {
        return currentPrice(line, current).map(CurrentPrice::quote);
    }

    /**
     * A line's Quote now and the demand surcharge multiplier in it (SRS Ledger.Surcharge): the fill
     * is read only when the type's surcharge is on, and the line's own rows never count, since a
     * line being quoted has none.
     */
    record CurrentPrice(Credits quote, BigDecimal surcharge) {}

    Optional<CurrentPrice> currentPrice(RequestLineRow line, PlannerSettings current) {
        return current.pricingOf(new ResourceTypeId(line.typeId())).map(pricing -> {
            List<TimeWindow> occurrences = line.occurrences(clock.getZone());
            BigDecimal fill = pricing.surchargeRate().signum() == 0 || line.allocationClass() == AllocationClass.MATCHED
                    ? BigDecimal.ZERO
                    : allocation.peakFill(new ResourceTypeId(line.typeId()), occurrences);
            Credits quote = Pricing.quote(
                    new LineSpec(line.amount(), occurrences, line.priority(), clock.getZone(),
                            tierOf(line, pricing).map(ServiceTier::premium).orElse(BigDecimal.ONE)),
                    pricing,
                    current.priorityPremium(),
                    fill).amount();
            return new CurrentPrice(quote, Pricing.surchargeMultiplier(pricing, fill));
        });
    }

    /** The current premium of the line's service tier, or empty when it has none or its type is unpriced. */
    Optional<BigDecimal> currentTierPremium(RequestLineRow line, PlannerSettings current) {
        return current.pricingOf(new ResourceTypeId(line.typeId()))
                .flatMap(pricing -> tierOf(line, pricing))
                .map(ServiceTier::premium);
    }

    private static Optional<ServiceTier> tierOf(RequestLineRow line, TypePricing pricing) {
        return line.tierId() == null ? Optional.empty() : pricing.tier(new ServiceTierId(line.tierId()));
    }

    static TimeWindow window(RequestLineRow line) {
        return new TimeWindow(line.startsAt(), line.endsAt());
    }

    /** The whole days of a Person line, inclusive at both ends, company time. */
    DateRange days(RequestLineRow line) {
        return new DateRange(LocalDate.ofInstant(line.startsAt(), clock.getZone()),
                LocalDate.ofInstant(line.endsAt().minusNanos(1), clock.getZone()));
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
        private final Map<Long, String> memberNames = new HashMap<>();

        Request request(RequestRow row) {
            return new Request(new RequestId(row.id()), team(row.teamId()), row.state(), row.createdAt(), row.submittedAt(),
                    row.windowEndsAt());
        }

        List<RequestLine> lines(RequestRow request, List<RequestLineRow> rows) {
            return rows.stream().map(row -> line(request, row)).toList();
        }

        RequestLine line(RequestRow request, RequestLineRow row) {
            Credits quote;
            BigDecimal surcharge = null;
            if (request.isDraft()) {
                Optional<CurrentPrice> price = currentPrice(row, settings());
                quote = price.map(CurrentPrice::quote).orElse(null);
                surcharge = price.map(CurrentPrice::surcharge).filter(multiplier -> multiplier.compareTo(BigDecimal.ONE) != 0)
                        .orElse(null);
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
                    quote,
                    row.recurrence(),
                    row.priority(),
                    row.namedMemberId() == null ? null : new MemberId(row.namedMemberId()),
                    row.namedMemberId() == null ? null : memberNames.computeIfAbsent(row.namedMemberId(),
                            id -> members.summary(new MemberId(id)).name()),
                    row.skills().isEmpty() ? List.of()
                            : members.skillsById(row.skills().stream().map(SkillId::new).toList()),
                    row.allocationClass() == AllocationClass.MATCHED ? days(row) : null,
                    row.tierId() == null ? null : new ServiceTierId(row.tierId()),
                    row.tierId() == null ? null : settings().pricingOf(new ResourceTypeId(row.typeId()))
                            .flatMap(pricing -> tierOf(row, pricing)).map(ServiceTier::name).orElse(null),
                    surcharge);
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
