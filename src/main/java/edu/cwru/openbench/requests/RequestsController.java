package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.ConcurrencyRetry;
import edu.cwru.openbench.common.PageResponse;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.TeamId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Requests endpoints (design Table "Endpoints by module"): Drafts, the Request detail, and the
 * submit and withdraw transitions. A transition answers 200 with the Request in its new state; it
 * runs once more after a deadlock or lock timeout, and a second failure answers 503.
 */
@RestController
@RequestMapping("/api/requests")
class RequestsController {

    private final RequestService requests;
    private final RequestLifecycleService lifecycle;

    RequestsController(RequestService requests, RequestLifecycleService lifecycle) {
        this.requests = requests;
        this.lifecycle = lifecycle;
    }

    @GetMapping("/{id}")
    RequestDetail detail(Actor actor, @PathVariable RequestId id) {
        return lifecycle.detail(actor, id);
    }

    @PostMapping("/{id}/submit")
    Request submit(Actor actor, @PathVariable RequestId id) {
        return ConcurrencyRetry.once(() -> lifecycle.submit(actor, id));
    }

    @PostMapping("/{id}/withdraw")
    Request withdraw(Actor actor, @PathVariable RequestId id) {
        return ConcurrencyRetry.once(() -> lifecycle.withdraw(actor, id));
    }

    /** The lines with these ids that the caller may see, for the Statement's links to Requests. */
    @GetMapping("/lines")
    List<RequestLine> lines(Actor actor, @RequestParam @Size(max = 500) List<RequestLineId> ids) {
        return requests.lines(actor, ids);
    }

    @GetMapping
    PageResponse<RequestSummary> list(
            Actor actor,
            @RequestParam(required = false) TeamId teamId,
            @RequestParam(required = false) RequestState state,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return PageResponse.of(requests.list(actor, teamId, state, page, size));
    }

    @PostMapping
    ResponseEntity<Request> create(Actor actor) {
        Request request = requests.create(actor);
        return ResponseEntity.created(URI.create("/api/requests/" + request.id().value())).body(request);
    }

    @GetMapping("/{id}/draft")
    DraftView draft(Actor actor, @PathVariable RequestId id) {
        return requests.view(actor, id);
    }

    @PostMapping("/{id}/lines")
    ResponseEntity<RequestLine> addLine(Actor actor, @PathVariable RequestId id, @Valid @RequestBody NewLine line) {
        return created(requests.addLine(actor, id, line));
    }

    @PutMapping("/{id}/lines/{lineId}")
    RequestLine editLine(Actor actor, @PathVariable RequestId id, @PathVariable RequestLineId lineId,
            @Valid @RequestBody LineEdit edit) {
        return requests.editLine(actor, id, lineId, edit);
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    ResponseEntity<Void> removeLine(Actor actor, @PathVariable RequestId id, @PathVariable RequestLineId lineId) {
        requests.removeLine(actor, id, lineId);
        return ResponseEntity.noContent().build();
    }

    /** Answers 201 with the new line. */
    @PostMapping("/{id}/lines/{lineId}/split")
    ResponseEntity<RequestLine> splitLine(Actor actor, @PathVariable RequestId id, @PathVariable RequestLineId lineId,
            @Valid @RequestBody Split split) {
        return created(requests.splitLine(actor, id, lineId, split.quantity()));
    }

    @PutMapping("/{id}/lines/{lineId}/required")
    RequestLine setRequired(Actor actor, @PathVariable RequestId id, @PathVariable RequestLineId lineId,
            @Valid @RequestBody RequiredMark mark) {
        return requests.setRequired(actor, id, lineId, mark.required());
    }

    private static ResponseEntity<RequestLine> created(RequestLine line) {
        URI location = URI.create("/api/requests/" + line.requestId().value() + "/lines/" + line.id().value());
        return ResponseEntity.created(location).body(line);
    }

    /** The body of POST .../split: {@code {"quantity": 2}}, the instances or units of the new line. */
    record Split(@NotNull Integer quantity) {}

    /** The body of PUT .../required: {@code {"required": false}}. */
    record RequiredMark(@NotNull Boolean required) {}
}
