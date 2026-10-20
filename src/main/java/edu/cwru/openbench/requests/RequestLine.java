package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Credits;
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
        Credits quote) {}
