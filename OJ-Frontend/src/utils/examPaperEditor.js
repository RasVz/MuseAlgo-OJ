const OPTION_KEYS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'

export function nextOptionKey(options = []) {
  const used = new Set(options.map((option) => String(option?.key || '').trim().toUpperCase()))
  return [...OPTION_KEYS].find((key) => !used.has(key)) || ''
}

function normalizeJudgeAnswer(value) {
  const answer = String(value || '').trim().toUpperCase()
  if (['T', 'TRUE', '对', '√', '是'].includes(answer)) return 'T'
  if (['F', 'FALSE', '错', '×', '否'].includes(answer)) return 'F'
  return ''
}

function parseOptions(raw, lineNumber) {
  const options = String(raw || '').split(';').map((part, index) => {
    const match = part.trim().match(/^([A-Z])[.、．]?\s*(.+)$/i)
    if (!match) throw new Error(`第 ${lineNumber} 行：选项 ${index + 1} 格式应为 A.内容`)
    return { key: match[1].toUpperCase(), text: match[2].trim() }
  })
  const keys = options.map((option) => option.key)
  if (new Set(keys).size !== keys.length) throw new Error(`第 ${lineNumber} 行：选项编号不能重复`)
  if (options.length < 2) throw new Error(`第 ${lineNumber} 行：至少需要两个选项`)
  return options
}

/** Parse the documented pipe-delimited format atomically; one malformed line rejects the batch. */
export function parseBatchQuestions(text) {
  const lines = String(text || '').split(/\r?\n/).map((line, index) => ({ line: line.trim(), number: index + 1 })).filter(({ line }) => line)
  if (!lines.length) return { questions: [], errors: ['请先粘贴题目'] }
  const questions = []
  const errors = []

  for (const { line, number } of lines) {
    try {
      const parts = line.split('|').map((part) => part.trim())
      const type = String(parts[0] || '').toUpperCase()
      if (!['SINGLE', 'MULTIPLE', 'JUDGE'].includes(type)) throw new Error(`第 ${number} 行：题型应为 SINGLE、MULTIPLE 或 JUDGE`)
      const statement = parts[1] || ''
      if (!statement) throw new Error(`第 ${number} 行：题干不能为空`)

      let answer
      let score
      let options = []
      if (type === 'JUDGE') {
        // JUDGE|题干|对/错|分值；兼容带空选项列的五段格式。
        if (parts.length !== 4 && parts.length !== 5) throw new Error(`第 ${number} 行：判断题格式应为 JUDGE|题干|对/错|分值`)
        if (parts.length === 5 && parts[2]) throw new Error(`第 ${number} 行：判断题不能填写选项列`)
        if (!parts[parts.length - 1]) throw new Error(`第 ${number} 行：请填写题目分值`)
        const compact = parts.length === 4
        answer = normalizeJudgeAnswer(parts[compact ? 2 : 3])
        score = Number(parts[compact ? 3 : 4])
        if (!answer) throw new Error(`第 ${number} 行：判断答案应为 对/错、T/F、true/false`)
      } else {
        if (parts.length !== 5) throw new Error(`第 ${number} 行：格式应为 题型|题干|选项|答案|分值`)
        options = parseOptions(parts[2], number)
        const keys = new Set(options.map((option) => option.key))
        answer = parts[3].toUpperCase().replace(/[\s,，、]/g, '')
        if (!answer || [...answer].some((key) => !keys.has(key))) throw new Error(`第 ${number} 行：答案只能使用已定义的选项编号`)
        if (type === 'SINGLE' && answer.length !== 1) throw new Error(`第 ${number} 行：单选答案只能有一个选项`)
        if (new Set(answer).size !== answer.length) throw new Error(`第 ${number} 行：答案中不能重复选项`)
        score = Number(parts[4])
      }
      if (type !== 'JUDGE' && !parts[4]) throw new Error(`第 ${number} 行：请填写题目分值`)
      if (!Number.isInteger(score) || score < 0 || score > 100) throw new Error(`第 ${number} 行：分值须为 0–100 的整数`)
      questions.push({ questionType: type, statement, options, answer, explanation: '', score })
    } catch (error) {
      errors.push(error.message)
    }
  }
  return { questions: errors.length ? [] : questions, errors }
}

export function normalizeQuestion(question) {
  return {
    questionType: question.questionType,
    statement: String(question.statement || '').trim(),
    options: question.questionType === 'JUDGE'
      ? []
      : (question.options || []).map((option) => ({ key: String(option.key || '').trim().toUpperCase(), text: String(option.text || '').trim() })),
    answer: question.questionType === 'JUDGE' ? normalizeJudgeAnswer(question.answer) : String(question.answer || '').trim().toUpperCase().replace(/[\s,，、]/g, ''),
    explanation: String(question.explanation || '').trim(),
    score: Number(question.score ?? 0)
  }
}

export function getPaperTotals(problems = [], questions = []) {
  return {
    problemCount: problems.length,
    questionCount: questions.length,
    problemScore: problems.reduce((sum, item) => sum + (Number(item.score) || 0), 0),
    objectiveScore: questions.reduce((sum, item) => sum + (Number(item.score) || 0), 0)
  }
}

/** Return field-specific validation messages for both draft and published papers. */
export function validatePaperQuestions(questions = []) {
  const errors = []
  questions.forEach((raw, index) => {
    const q = normalizeQuestion(raw)
    const label = `第 ${index + 1} 题`
    if (!['SINGLE', 'MULTIPLE', 'JUDGE'].includes(q.questionType)) errors.push(`${label}：题型无效`)
    if (!q.statement) errors.push(`${label}：题干不能为空`)
    if (!Number.isInteger(q.score) || q.score < 0 || q.score > 100) errors.push(`${label}：分值须为 0–100 的整数`)
    if (!q.answer) errors.push(`${label}：标准答案不合法`)
    if (q.questionType !== 'JUDGE') {
      const keys = q.options.map((option) => option.key)
      if (q.options.length < 2 || q.options.some((option) => !option.text) || keys.some((key) => !/^[A-Z]$/.test(key)) || new Set(keys).size !== keys.length) {
        errors.push(`${label}：选项至少两项，编号须唯一且内容不能为空`)
      } else if ([...q.answer].some((key) => !keys.includes(key)) || new Set(q.answer).size !== q.answer.length || (q.questionType === 'SINGLE' && q.answer.length !== 1)) {
        errors.push(`${label}：标准答案与选项不匹配`)
      }
    }
  })
  return errors
}
