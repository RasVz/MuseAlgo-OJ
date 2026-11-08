package com.musealgo.oj.api.report;

/** Report-owned current authorization and terminal-state seam for notice dispatch. */
public interface ReportNoticeContextQuery {

    /**
     * Lock the report job in the caller's short notification transaction and return fresh
     * terminal, recipient authorization, and retention facts. The caller must acquire locks in
     * report-job then notice-intent order.
     */
    ReportNoticeContextSnapshot lockCurrentContext(
            Long jobId,
            String eventKind,
            Long generationNo,
            Long terminalRevision,
            Long recipientId);
}
