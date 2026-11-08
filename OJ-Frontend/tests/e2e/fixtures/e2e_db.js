// 仅用于隔离测试栈的合成数据准备：通过 tests/run_suite.py 导出的 Compose 命令执行 SQL。
// 不连接任何既有业务库；缺少 OJ_TEST_COMPOSE_JSON 时直接失败，避免误连本机数据库。
import { spawnSync } from 'node:child_process'

function composeCommand() {
  const raw = process.env.OJ_TEST_COMPOSE_JSON
  if (!raw) throw new Error('缺少 OJ_TEST_COMPOSE_JSON：DB 型 e2e 必须由 tests/run_suite.py 或等价隔离栈启动')
  return JSON.parse(raw)
}

export function sql(query) {
  const [docker, ...compose] = composeCommand()
  const result = spawnSync(
    docker,
    [
      ...compose,
      'exec',
      '-T',
      'mysql',
      'sh',
      '-c',
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B --default-character-set=utf8mb4 oj_db',
    ],
    { input: query, encoding: 'utf8', timeout: 30000 }
  )
  if (result.status !== 0) {
    throw new Error('测试栈 SQL 失败: ' + (result.stderr || result.stdout || 'exit ' + result.status))
  }
  return (result.stdout || '').trim()
}
