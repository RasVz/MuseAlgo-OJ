package com.musealgo.oj.ai.diagnostics;

import com.musealgo.oj.api.system.SystemConfigQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DiagnosisSettings {
    private final SystemConfigQuery config;
    public int maxCases(){return Math.max(0,Math.min(2,config.getInt("ai.diagnostics.max-cases",1)));}
    public int waitSeconds(){return Math.max(2,Math.min(30,config.getInt("ai.diagnostics.wait-seconds",12)));}
}
