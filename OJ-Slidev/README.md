# OJ-Slidev 课件工厂

一个 Slidev 项目 = 一套主题与配置，渲染**任意位置**的课件 Markdown。所有信息学课件（GESP / CSP-J / CSP-S）共用本工厂预览、构建、导出与网页发布。

## 依赖与版本

- Slidev **52.x**（package.json 锁定，勿随意大版本升级；升级前先用一节课件做兼容验证）
- Node ≥ 18；包管理 npm（项目内有 package-lock.json）
- 导出 PDF/PPTX 依赖 playwright-chromium（devDependency，已装）

## 日常命令（在工厂目录下执行）

| 操作 | 命令 |
| --- | --- |
| 本地预览 | `npm run dev -- <课件md路径>` |
| 语法质检（门禁） | `python3 scripts/check_slides.py <课件md路径>` |
| 构建网页版 | `npm run build -- <课件md路径> --out dist` |
| 导出 PDF | `npm run export -- <课件md路径> --format pdf --output <课程包目录>/课件.pdf` |
| 导出 PPTX | `npm run export -- <课件md路径> --format pptx --output <课程包目录>/课件.pptx` |

课件路径可为相对或绝对路径，课件 md 可存放在任意目录，例如：

```
slides.md                    # 仓库自带的示例 deck
../my-courses/01-入门.md      # 放在本工程之外的课程目录
```

`--output` 是**输出文件路径**（非目录），指向课程包目录内即可让课程包自包含（md 源 + 可编辑 PPTX + 可打印 PDF）。

## 路径解析约定（重要）

Slidev 52 会从**课件文件所在目录**向上查找 `node_modules` 来解析主题（与执行命令的目录无关）。

- 课件放在本工厂目录内：直接可用。
- 课件放在本工厂目录**之外**：需保证从课件目录向上能找到 `node_modules`。最简单的方式是在课件目录树的某个上级目录，建一个指向本工厂 `node_modules` 的符号链接：

  ```bash
  ln -s /path/to/OJ-Slidev/node_modules /path/to/course-tree/node_modules
  ```

- 工厂执行 `npm install` 后该链接自动生效（指向同一目录），无需重建。
- 若渲染时报「主题未找到」，说明从课件目录向上解析不到 `node_modules`，按上面的方式补一个链接即可。

## 故障排查

- **导出报 `Unable to find a random port on host ...`**：shell 环境里 `HOST` 变量被设成了非主机名（如 `arm64-apple-darwin20.0.0`）。用 `env -u HOST npm run export -- <课件> --format pdf` 即可。
- **导出后课件目录残留 `.vite/` 缓存**：Slidev 的临时缓存，删掉即可（`rm -rf <课件目录>/.vite`），不影响课件。
- **导出时 KaTeX 字体警告**：不影响导出结果，可忽略。

## 交付门禁

课件交付前必须通过 `check_slides.py`（frontmatter、分页、每页教师注释、v-click 闭合、行高亮语法、LaTeX 配平），不通过即不合格；随后跑一次 build 确认可渲染。

## 两种课件模式

- **轻量模式**：`theme: default`，课件 md 自带内容即可渲染。
- **精致模式**：定制 CSS 以 `<style>` 块**内联在课件 md 内**（课件自洽，不依赖工厂全局样式，避免与其他 deck 互相污染）。

## 发布到网页

`npm run build -- <课件md路径> --out dist` 后按 netlify/vercel 配置部署 dist。netlify 默认构建命令 `npm run build` 会构建根目录 slides.md；发布其他课件时改 build 命令为 `npm run build -- <课件路径> --out dist`。多节课同时上线需要配置路由/base 路径，再做对应调整。

## 目录说明

- `slides.md` — 默认 deck(《C++ 新手第一课》)
- `scripts/check_slides.py` — 课件 lint 门禁
- `dist/` — 构建输出
- `netlify.toml` / `vercel.json` — 网页部署配置
