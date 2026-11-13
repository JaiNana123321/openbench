package edu.cwru.openbench.allocation;

/** What a part of a Resource's timeline is (SRS Inventory.View.Holder): free, on Hold, or allocated. */
public enum HoldingState {
    FREE,
    HELD,
    ALLOCATED
}
