package com.musealgo.oj.api.report;

import java.util.Optional;

/** Narrow, uncached lookup of the current account state used by report workers. */
public interface ReportActorQuery {

    /**
     * Resolve an account using the same legacy/normalized authority rules as request security.
     * A deleted account returns {@link Optional#empty()}; database failures must propagate so
     * callers can fail closed instead of treating an unknown account as an ordinary user.
     */
    Optional<ReportActorSnapshot> findCurrentActor(Long actorId);
}
