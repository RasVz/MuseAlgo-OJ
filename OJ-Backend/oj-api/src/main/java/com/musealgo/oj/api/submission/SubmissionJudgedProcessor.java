package com.musealgo.oj.api.submission;

/** Durable local projection of a committed judged event; repeated processing must be idempotent. */
public interface SubmissionJudgedProcessor {
    void process(Long submissionId);
}
