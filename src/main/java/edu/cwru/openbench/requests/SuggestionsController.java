package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.ConcurrencyRetry;
import edu.cwru.openbench.common.PageResponse;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.SuggestionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/suggestions")
class SuggestionsController {
    private final SuggestionService suggestions;

    SuggestionsController(SuggestionService suggestions) { this.suggestions = suggestions; }

    record Create(@NotNull @Size(min = 1, max = 1) List<@NotNull @Valid NewLine> lines, String note) {}
    record Accept(RequestId requestId, RequestLineId lineId) {}
    record Decline(String reason) {}

    @PostMapping
    ResponseEntity<Suggestion> create(Actor actor, @Valid @RequestBody Create body) {
        Suggestion result = suggestions.create(actor, body.lines().getFirst(), body.note());
        return ResponseEntity.created(URI.create("/api/suggestions/" + result.id().value())).body(result);
    }

    @GetMapping("/mine")
    PageResponse<Suggestion> mine(Actor actor, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return PageResponse.of(suggestions.mine(actor, page, size));
    }

    @GetMapping("/team")
    PageResponse<Suggestion> team(Actor actor, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return PageResponse.of(suggestions.forManager(actor, page, size));
    }

    @GetMapping("/{id}")
    Suggestion get(Actor actor, @PathVariable SuggestionId id) { return suggestions.get(actor, id); }

    @PostMapping("/{id}/accept")
    Suggestion accept(Actor actor, @PathVariable SuggestionId id, @RequestBody Accept body) {
        if ((body.requestId() == null) == (body.lineId() == null)) {
            throw new RuleViolation("VALIDATION_FAILED", "Choose one Draft Request or one Draft line.");
        }
        return ConcurrencyRetry.once(() -> body.requestId() != null
                ? suggestions.acceptIntoNewLine(actor, id, body.requestId())
                : suggestions.acceptIntoLine(actor, id, body.lineId()));
    }

    @PostMapping("/{id}/decline")
    Suggestion decline(Actor actor, @PathVariable SuggestionId id, @RequestBody Decline body) {
        return ConcurrencyRetry.once(() -> suggestions.decline(actor, id, body.reason()));
    }

    @GetMapping("/request/{id}")
    List<Suggestion> forRequest(Actor actor, @PathVariable RequestId id) {
        return suggestions.forRequest(actor, id);
    }
}
