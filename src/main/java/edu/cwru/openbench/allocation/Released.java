package edu.cwru.openbench.allocation;

import java.util.List;

/**
 * What a Release gave back, or would give back in a preview: the pieces, by occurrence start, and
 * whether the line has no allocated row left (then it is Released, otherwise PartlyReleased).
 */
public record Released(List<ReleasedPiece> pieces, boolean nothingLeft) {

    public Released {
        pieces = List.copyOf(pieces);
    }
}
