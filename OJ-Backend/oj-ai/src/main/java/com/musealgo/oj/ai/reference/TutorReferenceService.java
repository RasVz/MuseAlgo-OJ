package com.musealgo.oj.ai.reference;

import com.musealgo.oj.ai.mapper.OfficialSolutionMapper;
import com.musealgo.oj.ai.diagnostics.DiagnosticStats;
import com.musealgo.oj.ai.error.AiErrorCode;
import com.musealgo.oj.api.system.SystemConfigQuery;
import com.musealgo.oj.api.system.SystemConfigCommand;
import com.musealgo.oj.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TutorReferenceService {
    public record Reference(Long postId,String title,String content,boolean truncated,int originalChars) { }
    private final OfficialSolutionMapper mapper;
    private final SystemConfigQuery config;
    private final SystemConfigCommand command;
    private final DiagnosticStats stats;
    public Reference resolve(Long problem, boolean count) {
        String raw=config.get("ai.tutor.reference."+problem,"");Long selected=null;
        if(raw!=null&&!raw.isBlank())try{selected=Long.valueOf(raw);}catch(NumberFormatException invalid){return null;}
        var post=mapper.findPreferred(problem,selected);
        if(count)stats.add(post==null?"reference.miss":"reference.hit",null,1);
        if(post==null)return null;
        String content=post.content()==null?"":post.content();boolean truncated=content.length()>8000;
        String text=truncated?content.substring(0,4000)+"\n【中段因预算省略，以下为结尾；不视为完整题解】\n"+content.substring(content.length()-4000):content;
        return new Reference(post.id(),post.title(),text,truncated,content.length());
    }
    public Map<String,Object> status(Long problem) {
        var ref=resolve(problem,false);Map<String,Object> result=new LinkedHashMap<>();result.put("problemId",problem);
        result.put("available",ref!=null);result.put("selectedPostId",config.get("ai.tutor.reference."+problem,""));
        result.put("postId",ref==null?null:ref.postId());result.put("title",ref==null?null:ref.title());
        result.put("truncated",ref!=null&&ref.truncated());result.put("originalChars",ref==null?0:ref.originalChars());
        result.put("candidates",mapper.listOfficial(problem));return result;
    }
    public void select(Long problem,Long post) {
        if(problem==null||problem<=0)throw new BusinessException(AiErrorCode.AI_INPUT_INVALID);
        if(post!=null&&mapper.findPreferred(problem,post)==null)throw new BusinessException(AiErrorCode.AI_INPUT_INVALID,"只能选择同题、公开、未删除的官方题解");
        command.set("ai.tutor.reference."+problem,post==null?"":post.toString());
    }
}
