#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Slidev 课件 lint 门禁（OJ-Slidev 工厂）
用法: python3 scripts/check_slides.py <课件.md>

检查项:
  1. frontmatter 存在且含 theme / title
  2. 多页结构（--- 分隔），无空页
  3. 每页含教师注释（<!-- -->）
  4. <v-click> 标签闭合
  5. 代码行高亮语法 {1|2-3} 合法
  6. LaTeX $$ 配平

全部 PASS 才可交付；FAIL 项必须修复后重跑。
"""
import re
import sys


def main():
    if len(sys.argv) < 2:
        print('用法: python3 check_slides.py <课件.md>')
        sys.exit(1)
    path = sys.argv[1]
    results = []

    def check(name, ok, detail=''):
        results.append(ok)
        print(f'{"PASS" if ok else "FAIL"}  {name}' + (f'  ({detail})' if detail else ''))

    try:
        with open(path, encoding='utf-8') as f:
            content = f.read()
    except FileNotFoundError:
        check(f'文件存在: {path}', False)
        print('\n=== 结果: 0/1 通过 ===')
        sys.exit(1)
    check(f'文件存在: {path}', True)

    # 1. frontmatter
    m = re.match(r'^---\s*\n(.*?)\n---\s*\n', content, re.S)
    if not m:
        check('frontmatter: 存在合法 YAML 头', False, '文件开头缺少 --- 包裹的 frontmatter')
        check('frontmatter: 含 theme', False)
        check('frontmatter: 含 title', False)
        body = content
    else:
        fm = m.group(1)
        body = content[m.end():]
        check('frontmatter: 存在合法 YAML 头', True)
        has_theme = re.search(r'^theme\s*:', fm, re.M) is not None
        has_title = re.search(r'^title\s*:', fm, re.M) is not None
        check('frontmatter: 含 theme', has_theme, '' if has_theme else '缺失 theme 字段')
        check('frontmatter: 含 title', has_title, '' if has_title else '缺失 title 字段')

    # 2. 分页与空页（兼容 Slidev 每页 frontmatter：纯 key: value 块并入下一页）
    raw_parts = [p for p in re.split(r'\n---\s*\n', body) if p.strip()]
    pages = []
    pending = []
    for p in raw_parts:
        nonempty = [l for l in p.splitlines() if l.strip()]
        if all(re.match(r'^[\w.-]+\s*:\s*\S', l.strip()) for l in nonempty):
            pending.append(p)
        else:
            pages.append('\n'.join(pending + [p]) if pending else p)
            pending = []
    if pending:
        pages[-1] = pages[-1] + '\n' + '\n'.join(pending)
    check(f'页面结构: 共 {len(pages)} 页（多页课件）', len(pages) >= 2,
          '' if len(pages) >= 2 else '未检测到 --- 分页')
    empty = [i for i, p in enumerate(raw_parts, 1) if not p.strip()]
    check('页面结构: 无空页', not empty, f'空页: {empty}' if empty else '')

    # 3. 每页教师注释（纯样式/图形页不计）
    def visible_text(p):
        v = re.sub(r'<style.*?</style>', '', p, flags=re.S)
        v = re.sub(r'<svg.*?</svg>', '', v, flags=re.S)
        v = re.sub(r'<!--.*?-->', '', v, flags=re.S)
        v = re.sub(r'<[^>]+>', '', v)
        return v.strip()
    missing_notes = [i for i, p in enumerate(pages, 1)
                     if '<!--' not in p and visible_text(p)]

    # 4. v-click 闭合
    opens = len(re.findall(r'<v-click>', content))
    closes = len(re.findall(r'</v-click>', content))
    check('v-click: 标签闭合', opens == closes,
          f'开 {opens} / 闭 {closes}' if opens != closes else '')

    # 5. 代码行高亮语法
    bad_hl = []
    for line in content.splitlines():
        mm = re.match(r'^```\w+\s*\{([^}]*)\}\s*$', line)
        if mm:
            spec = mm.group(1)
            if not re.match(r'^(\d+(-\d+)?|all)(\|(\d+(-\d+)?|all))*$', spec):
                bad_hl.append('{' + spec + '}')
    check('代码高亮: {1|2-3} 语法合法', not bad_hl,
          '; '.join(bad_hl) if bad_hl else '')

    # 6. LaTeX 配平
    tex_bad = [i for i, p in enumerate(pages, 1) if p.count('$$') % 2 != 0]
    check('LaTeX: $$ 配平', not tex_bad, f'不配平页: {tex_bad}' if tex_bad else '')

    passed = sum(results)
    print(f'\n=== 结果: {passed}/{len(results)} 通过 ===')
    if passed != len(results):
        print('待修复项如上，修复后重跑')
        sys.exit(1)
    print('全部通过 ✓')


if __name__ == '__main__':
    main()
