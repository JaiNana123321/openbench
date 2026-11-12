package edu.cwru.openbench.members;

/** What an entry of the Administrator's change record ({@link AdminChange}) records. */
public enum AdminChangeKind {
    MEMBER_CREATED,
    MEMBER_DEACTIVATED,
    MEMBER_MOVED,
    ROLE_GRANTED,
    ROLE_REVOKED,
    TEAM_CREATED,
    TEAM_RENAMED,
    TEAM_RETIRED
}
