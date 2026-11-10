/**
 * 题面公开样例归一化。
 *
 * 后端优先返回多组 samples；早期导入的题目只有 inputSample/outputSample 两个字段。
 * 题面渲染与自测面板必须基于同一份归一化结果初始化，否则会出现"题面有 2 组、
 * 自测只有 1 组"的错位。
 *
 * 只读取字段，不修改入参；样例内容一律当纯文本处理，后续不做 Markdown/HTML 渲染。
 */
export function normalizeProblemSamples(problem) {
  const samples = Array.isArray(problem?.samples) ? problem.samples : []
  const normalized = samples
    .filter(sample => sample && typeof sample === 'object')
    .map(sample => ({
      input: sample.input == null ? '' : String(sample.input),
      output: sample.output == null ? '' : String(sample.output),
      // hasOutput 区分"输出为空字符串"与"根本没有输出字段"，自测比较需要这个区别。
      hasOutput: sample.output !== undefined && sample.output !== null
    }))

  if (normalized.length) return normalized

  if (problem?.inputSample != null || problem?.outputSample != null) {
    return [{
      input: problem.inputSample ?? '',
      output: problem.outputSample ?? '',
      hasOutput: problem.outputSample !== undefined && problem.outputSample !== null
    }]
  }

  return []
}
