package com.musealgo.oj.problem.entity.vo;

import lombok.Getter;
import lombok.Setter;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemSample;

import java.util.List;

/** 题目详情：题目 + 多组公开样例 */
@Getter
@Setter
public class ProblemDetailVO extends Problem {

    private List<ProblemSample> samples;
}
