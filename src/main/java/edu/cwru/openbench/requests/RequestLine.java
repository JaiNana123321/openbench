package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.members.Skill;
import java.util.List;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;

/**
 * A Request Line as the API sees it. {@code quantity} counts instances of an Exclusive type and
 * units of a Metered type. {@code quote} is the line's price: in a Draft, computed now at the current
 * settings; once submitted, the locked Quote. It is null when the type has no pricing settings (a
 * Draft line counts 0.00 in the sums then). {@code namedResource} and {@code namedResourceName} are
 * null unless an Exclusive line names one Resource.
 *
 * <p>A Person (Matched) line asks for {@code quantity} Persons over whole days: {@code days} holds
 * them, inclusive at both ends ({@code {"from": "2026-11-25", "to": "2026-11-26"}}), and its
 * {@code window} runs from midnight of the first day to midnight after the last, company time. It
 * either names one Person ({@code namedMember}, {@code namedMemberName}; quantity 1) or asks for
 * {@code skills}. It has no Quote (ADR-0004). These four are null, or empty, on other lines.
 */
public record RequestLine(
        RequestLineId id,
        RequestId requestId,
        ResourceTypeId typeId,
        String typeName,
        AllocationClass allocationClass,
        int quantity,
        TimeWindow window,
        ResourceId namedResource,
        String namedResourceName,
        boolean required,
        RequestLineState state,
        Credits quote,
        MemberId namedMember,
        String namedMemberName,
        List<Skill> skills,
        DateRange days) {}
