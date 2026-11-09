package com.musealgo.oj.api.judge;
/** 仅记录资源元数据，不记录代码或样例正文。统计失败不影响教学。 */
public interface RunUsageSink {
    void add(String metric, Long userId, long amount);
}
