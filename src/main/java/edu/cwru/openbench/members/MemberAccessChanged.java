package edu.cwru.openbench.members;

import edu.cwru.openbench.common.MemberId;

/**
 * Published when an Administrator changes what a Member may do: deactivates them, grants or revokes
 * one of their roles (a new Manager's predecessor included), or moves them to another Team. Their
 * open sessions still carry the old roles and Team, so {@code security} ends them once the change
 * commits (design Section 7, "Authentication and Sessions"); the Member signs in again to pick up the
 * new ones. An event, because Members sits below {@code security} and may not call it.
 */
public record MemberAccessChanged(MemberId member) {}
