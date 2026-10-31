<#ftl output_format="HTML">
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<title>信奥学习周报</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif;
    color: #1e293b; background: #ffffff; padding: 8mm; font-size: 13px; line-height: 1.7;
  }
  .header {
    background: linear-gradient(135deg, #1e40af 0%, #3b82f6 100%);
    color: #fff; border-radius: 10px; padding: 22px 28px; margin-bottom: 18px;
  }
  .header h1 { font-size: 22px; letter-spacing: 2px; }
  .header .meta { margin-top: 8px; font-size: 13px; opacity: .92; }
  .section { margin-bottom: 18px; page-break-inside: avoid; }
  .section-title {
    font-size: 15px; font-weight: 600; color: #1e40af;
    border-left: 4px solid #3b82f6; padding-left: 10px; margin-bottom: 12px;
  }
  .cards { display: flex; gap: 12px; }
  .card {
    flex: 1; background: #f1f5f9; border-radius: 10px; padding: 14px 8px; text-align: center;
  }
  .card .num { font-size: 26px; font-weight: 700; color: #1e40af; }
  .card .label { font-size: 12px; color: #64748b; margin-top: 2px; }
  #radarChart { width: 100%; height: 340px; }
  #comparisonChart { width: 100%; height: 280px; }
  .chart-svg { display: block; width: 100%; height: 100%; overflow: visible; }
  .chart-empty { text-align: center; color: #94a3b8; padding: 40px; }
  table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
  th, td { padding: 7px 10px; text-align: center; border-bottom: 1px solid #e2e8f0; }
  th { background: #eff6ff; color: #1e40af; font-weight: 600; }
  td.left, th.left { text-align: left; }
  .badge { display: inline-block; padding: 1px 10px; border-radius: 999px; font-size: 12px; }
  .badge-excellent { background: #dcfce7; color: #15803d; }
  .badge-good { background: #dbeafe; color: #1d4ed8; }
  .badge-adequate { background: #fef9c3; color: #a16207; }
  .badge-weak { background: #fee2e2; color: #b91c1c; }
  .weak-list { margin-bottom: 10px; }
  .weak-item {
    display: inline-block; background: #fef2f2; border: 1px solid #fecaca;
    color: #b91c1c; border-radius: 8px; padding: 4px 12px; margin: 0 8px 6px 0; font-size: 12.5px;
  }
  .advice-box {
    background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 14px 18px;
    color: #334155;
  }
  .reco-list { list-style: none; }
  .reco-list li {
    padding: 8px 12px; border-bottom: 1px dashed #e2e8f0; display: flex; justify-content: space-between;
  }
  .reco-list li:last-child { border-bottom: none; }
  .reco-tag { color: #64748b; font-size: 12px; }
  .diff { font-size: 12px; padding: 1px 8px; border-radius: 4px; background: #e2e8f0; color: #475569; }
  .footer {
    text-align: center; color: #94a3b8; font-size: 11px; margin-top: 20px;
    border-top: 1px solid #e2e8f0; padding-top: 10px;
  }
  #__charts_ready__ { display: none; }
</style>
</head>
<body>

<!-- ===================== 头部 ===================== -->
<div class="header">
  <h1>信奥学习周报 · 第 ${weekNo} 周</h1>
  <div class="meta">
    学生：${studentName}
    <#if className?has_content> &nbsp;|&nbsp; 班级：${className}</#if>
    &nbsp;|&nbsp; 统计周期：${periodStart} ～ ${periodEnd}
  </div>
</div>

<!-- ===================== ① 本周概览 ===================== -->
<div class="section">
  <div class="section-title">本周概览</div>
  <div class="cards">
    <div class="card"><div class="num">${totalSubmissions}</div><div class="label">提交次数</div></div>
    <div class="card"><div class="num">${acceptedProblemCount}</div><div class="label">AC 题目数</div></div>
    <div class="card"><div class="num">${activeDays}</div><div class="label">活跃天数</div></div>
  </div>
</div>

<!-- ===================== ② 能力雷达图 ===================== -->
<div class="section">
  <div class="section-title">能力雷达</div>
  <div id="radarChart" style="width:100%;"></div>
</div>

<!-- ===================== ②½ 能力变化（本周增长） ===================== -->
<div class="section">
  <div class="section-title">本周能力变化</div>
  <div id="comparisonChart"></div>
</div>

<!-- ===================== ③ 知识点掌握明细 ===================== -->
<div class="section">
  <div class="section-title">知识点掌握明细</div>
  <table>
    <thead>
      <tr>
        <th class="left">知识点</th><th>尝试题数</th><th>通过题数</th><th>一次通过率</th><th>掌握度</th><th>评价</th>
      </tr>
    </thead>
    <tbody>
      <#list tagRows as row>
      <tr>
        <td class="left">${row.tagName}</td>
        <td>${row.attempted}</td>
        <td>${row.passed}</td>
        <td>${row.firstTryRate}%</td>
        <td>${row.mastery}</td>
        <td>
          <#assign badgeClass = "badge-weak">
          <#if row.levelLabel == "优秀"><#assign badgeClass = "badge-excellent">
          <#elseif row.levelLabel == "良好"><#assign badgeClass = "badge-good">
          <#elseif row.levelLabel == "及格"><#assign badgeClass = "badge-adequate">
          </#if>
          <span class="badge ${badgeClass}">${row.levelLabel}</span>
        </td>
      </tr>
      </#list>
      <#if tagRows?size == 0>
      <tr><td colspan="6">本周期内暂无刷题记录</td></tr>
      </#if>
    </tbody>
  </table>
</div>

<!-- ===================== ⑤ 薄弱环节与练习建议 ===================== -->
<div class="section">
  <div class="section-title">薄弱环节与练习建议</div>
  <#if weakPoints?size gt 0>
  <div class="weak-list">
    <#list weakPoints as w>
    <span class="weak-item">${w.tagName}（${w.mastery} 分）</span>
    </#list>
  </div>
  </#if>
  <div class="advice-box">${advice}</div>
</div>

<#if recommendations?size gt 0>
<!-- ===================== ⑥ 下周推荐题单 ===================== -->
<div class="section">
  <div class="section-title">下周推荐题单</div>
  <ul class="reco-list">
    <#list recommendations as r>
    <li>
      <span>${r.title} <span class="reco-tag">[${r.tagName}]</span></span>
      <span class="diff">${r.difficultyLabel}</span>
    </li>
    </#list>
  </ul>
</div>
</#if>

<div class="footer">
  本报告由系统自动生成，数据来源于学生的真实提交记录 · 掌握度综合通过率、题目难度、一次通过率计算
</div>

<span id="__charts_ready__"></span>

<script>
(function () {
  var SVG_NS = 'http://www.w3.org/2000/svg';

  function svgNode(name, attributes, content) {
    var node = document.createElementNS(SVG_NS, name);
    Object.keys(attributes || {}).forEach(function (key) { node.setAttribute(key, attributes[key]); });
    if (content != null) node.textContent = content;
    return node;
  }

  function score(value) {
    value = Number(value);
    return Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0;
  }

  function emptyChart(container) {
    container.innerHTML = '<p class="chart-empty">暂无能力数据</p>';
  }

  function renderRadar(option) {
    var container = document.getElementById('radarChart');
    var indicators = option && option.radar && option.radar.indicator;
    var values = option && option.series && option.series[0] && option.series[0].data
      && option.series[0].data[0] && option.series[0].data[0].value;
    if (!Array.isArray(indicators) || !indicators.length || !Array.isArray(values)) return emptyChart(container);

    var width = 720, height = 340, cx = width / 2, cy = 175, radius = 118, labelRadius = 150;
    var pointAt = function (index, distance) {
      var angle = -Math.PI / 2 + index * Math.PI * 2 / indicators.length;
      return [cx + Math.cos(angle) * distance, cy + Math.sin(angle) * distance];
    };
    var polygon = function (ratio) {
      return indicators.map(function (_, index) {
        var point = pointAt(index, radius * ratio);
        return point[0].toFixed(1) + ',' + point[1].toFixed(1);
      }).join(' ');
    };
    var svg = svgNode('svg', { viewBox: '0 0 ' + width + ' ' + height, class: 'chart-svg', role: 'img', 'aria-label': '六维能力雷达图' });
    for (var level = 1; level <= 5; level++) {
      svg.appendChild(svgNode('polygon', { points: polygon(level / 5), fill: level % 2 ? '#f8fafc' : '#eff6ff', stroke: '#cbd5e1', 'stroke-width': '1' }));
    }
    indicators.forEach(function (indicator, index) {
      var axis = pointAt(index, radius), label = pointAt(index, labelRadius);
      svg.appendChild(svgNode('line', { x1: cx, y1: cy, x2: axis[0], y2: axis[1], stroke: '#cbd5e1', 'stroke-width': '1' }));
      svg.appendChild(svgNode('text', { x: label[0], y: label[1], fill: '#475569', 'font-size': '13', 'text-anchor': 'middle', 'dominant-baseline': 'middle' }, indicator.name || '未命名维度'));
    });
    var dataPoints = indicators.map(function (_, index) {
      var point = pointAt(index, radius * score(values[index]) / 100);
      return point[0].toFixed(1) + ',' + point[1].toFixed(1);
    }).join(' ');
    svg.appendChild(svgNode('polygon', { points: dataPoints, fill: 'rgba(59,130,246,.24)', stroke: '#2563eb', 'stroke-width': '2.5', 'stroke-linejoin': 'round' }));
    indicators.forEach(function (_, index) {
      var point = pointAt(index, radius * score(values[index]) / 100);
      svg.appendChild(svgNode('circle', { cx: point[0], cy: point[1], r: '3.5', fill: '#2563eb', stroke: '#ffffff', 'stroke-width': '1.5' }));
    });
    container.replaceChildren(svg);
  }

  function renderComparison(option) {
    var container = document.getElementById('comparisonChart');
    var labels = option && option.yAxis && option.yAxis.data;
    var series = option && option.series;
    var previous = Array.isArray(series) && series.find(function (item) { return item.name === '上周'; });
    var growth = Array.isArray(series) && series.find(function (item) { return item.name === '本周增长'; });
    if (!Array.isArray(labels) || !labels.length || !previous || !growth || !Array.isArray(previous.data) || !Array.isArray(growth.data)) return emptyChart(container);

    var width = 720, height = 280, left = 135, right = 50, top = 42, bottom = 14;
    var chartWidth = width - left - right, rowHeight = (height - top - bottom) / labels.length;
    var barHeight = Math.min(22, rowHeight * .58), scale = function (value) { return chartWidth * score(value) / 100; };
    var svg = svgNode('svg', { viewBox: '0 0 ' + width + ' ' + height, class: 'chart-svg', role: 'img', 'aria-label': '本周能力变化横向柱状图' });
    svg.appendChild(svgNode('rect', { x: left, y: '10', width: '12', height: '12', rx: '2', fill: '#cbd5e1' }));
    svg.appendChild(svgNode('text', { x: left + 18, y: '20', fill: '#64748b', 'font-size': '12' }, '上周结束时'));
    svg.appendChild(svgNode('rect', { x: left + 104, y: '10', width: '12', height: '12', rx: '2', fill: '#3b82f6' }));
    svg.appendChild(svgNode('text', { x: left + 122, y: '20', fill: '#1d4ed8', 'font-size': '12' }, '本周增长'));
    [0, 25, 50, 75, 100].forEach(function (tick) {
      var x = left + scale(tick);
      svg.appendChild(svgNode('line', { x1: x, y1: top, x2: x, y2: height - bottom, stroke: '#e2e8f0', 'stroke-width': '1' }));
      svg.appendChild(svgNode('text', { x: x, y: height - 2, fill: '#94a3b8', 'font-size': '11', 'text-anchor': 'middle' }, tick));
    });
    labels.forEach(function (label, index) {
      var y = top + rowHeight * index + (rowHeight - barHeight) / 2;
      var base = score(previous.data[index]), increase = score(growth.data[index]), total = Math.min(100, base + increase);
      svg.appendChild(svgNode('text', { x: left - 12, y: y + barHeight / 2, fill: '#334155', 'font-size': '13', 'text-anchor': 'end', 'dominant-baseline': 'middle' }, label));
      svg.appendChild(svgNode('rect', { x: left, y: y, width: chartWidth, height: barHeight, rx: '4', fill: '#f1f5f9' }));
      if (base > 0) svg.appendChild(svgNode('rect', { x: left, y: y, width: scale(base), height: barHeight, rx: '4', fill: '#cbd5e1' }));
      if (increase > 0) svg.appendChild(svgNode('rect', { x: left + scale(base), y: y, width: scale(increase), height: barHeight, rx: '4', fill: '#3b82f6' }));
      svg.appendChild(svgNode('text', { x: left + scale(total) + 8, y: y + barHeight / 2, fill: '#1e40af', 'font-size': '12', 'font-weight': '600', 'dominant-baseline': 'middle' }, increase > 0 ? total + ' (+' + increase + ')' : String(total)));
    });
    container.replaceChildren(svg);
  }

  try {
    // JSON 已通过 js_string 安全编码；脚本内容不能再被 HTML 自动转义，
    // 否则引号会变成 &quot;，导致 JSON.parse 失败并落入空数据提示。
    renderRadar(JSON.parse('${radarOption?js_string?no_esc}'));
    renderComparison(JSON.parse('${comparisonOption?js_string?no_esc}'));
  } catch (error) {
    emptyChart(document.getElementById('radarChart'));
    emptyChart(document.getElementById('comparisonChart'));
  } finally {
    document.getElementById('__charts_ready__').style.display = 'block';
  }
})();
</script>
</body>
</html>
