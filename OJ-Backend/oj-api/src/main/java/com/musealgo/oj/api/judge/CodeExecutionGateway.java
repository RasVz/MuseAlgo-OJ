package com.musealgo.oj.api.judge;

import java.util.List;

/** 普通练习试跑及可靠运行证据，不产生正式提交。调用方在请求线程完成身份/题目权限校验。 */
public interface CodeExecutionGateway {
    record Request(Long userId, Long problemId, String code, String language, String input, String origin) { }
    record Observation(String runId, String input, String stdout, String stderr, String compileOutput,
                       int status, String time, Integer memory, boolean truncated, String message) {
        public Observation(String runId,String input,String stdout,String stderr,String compileOutput,int status,String time,Integer memory,boolean truncated) {
            this(runId,input,stdout,stderr,compileOutput,status,time,memory,truncated,"");
        }
    }
    record Verification(Observation run, String expectedOutput, String expectationSource,
                        boolean confirmedFailure, String note) { }
    Observation run(Request request);
    List<Observation> reuse(Long userId, Long problemId, String code, String language, List<String> runIds);
    Verification verify(Request request, String suggestedExpected);
    default Verification verify(Request request, String suggestedExpected, List<String> runIds) { return verify(request,suggestedExpected); }
}
