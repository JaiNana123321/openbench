package edu.cwru.openbench.ledger;

/** The four kinds of Transaction (SRS Ledger.Transaction.Kinds). JSON: the upper-case name. */
public enum TransactionKind {
    GRANT,
    CHARGE,
    EARNING,
    REFUND;

    /** +1 for a kind that adds its parts to the Account, -1 for a Charge, which takes them. */
    int sign() {
        return this == CHARGE ? -1 : 1;
    }
}
