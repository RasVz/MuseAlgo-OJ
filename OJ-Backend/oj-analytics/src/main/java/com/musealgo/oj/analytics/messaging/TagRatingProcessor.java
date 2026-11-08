package com.musealgo.oj.analytics.messaging;

import com.musealgo.oj.analytics.application.TagRatingService;
import com.musealgo.oj.api.submission.SubmissionJudgedProcessor;
import com.musealgo.oj.api.submission.SubmissionQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Local outbox and optional notifications converge on the authoritative AC history. */
@Service
@RequiredArgsConstructor
public class TagRatingProcessor implements SubmissionJudgedProcessor {
    private final SubmissionQuery submissions;
    private final TagRatingService ratings;

    @Override
    public void process(Long submissionId) {
        var submission = submissions.findById(submissionId).orElse(null);
        if (submission == null || submission.userId() == null) return;
        ratings.rebuildForUser(submission.userId());
    }
}
