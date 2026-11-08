// The report runs with an opaque origin. No credentials are sent to the frame.
const policy = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"

export function reportDocument(html) {
  const bridge = `<script>window.addEventListener('message',function(e){if(e.source!==parent||!e.data||e.data.kind!=='muse-report-request')return;if(e.data.action==='print'){window.print();return}if(e.data.action==='snapshot'){var m=document.getElementById('__charts_ready__');if(!m||getComputedStyle(m).display!=='block')return;parent.postMessage({kind:'muse-report-snapshot',request:e.data.request,html:document.documentElement.outerHTML},'*')}});</script>`
  return `<meta http-equiv="Content-Security-Policy" content="${policy}">${bridge}${html || ''}`
}

// Only plain report text, layout nodes and SVG geometry cross into the parent. Do not copy
// scripts, event handlers, URLs, arbitrary CSS, nested frames, forms or SVG foreignObject.
const tags = new Set('div span p h1 h2 h3 h4 table thead tbody tfoot tr th td ul ol li strong b em i br hr svg g polygon polyline line rect circle text'.split(' '))
const attributes = new Set('class id viewBox width height x y x1 x2 y1 y2 cx cy r rx ry points d fill stroke stroke-width stroke-linejoin font-size font-weight text-anchor dominant-baseline role aria-label'.split(' '))
const discarded = new Set('script style iframe object embed form input button link meta base image img foreignobject'.split(' '))

export function safeReportSnapshot(html) {
  if (typeof html !== 'string' || html.length > 4_000_000) throw new Error('报告内容过大或无效')
  const template = document.createElement('template')
  template.innerHTML = html
  function copy(node) {
    if (node.nodeType === Node.TEXT_NODE) return document.createTextNode(node.data)
    const fragment = document.createDocumentFragment()
    if (node.nodeType !== Node.ELEMENT_NODE) return fragment
    const name = node.localName
    if (discarded.has(name.toLowerCase())) return fragment
    if (!tags.has(name)) {
      for (const child of node.childNodes) fragment.appendChild(copy(child))
      return fragment
    }
    const svg = node.namespaceURI === 'http://www.w3.org/2000/svg'
    const result = svg ? document.createElementNS(node.namespaceURI, name) : document.createElement(name)
    for (const attr of node.attributes) {
      if (!attributes.has(attr.name) || attr.value.length > 2000) continue
      if (!/^[\w\s#.,%()+-]*$/.test(attr.value) || /url\s*\(/i.test(attr.value)) continue
      result.setAttribute(attr.name, attr.value)
    }
    for (const child of node.childNodes) result.appendChild(copy(child))
    return result
  }
  const root = document.createElement('div')
  for (const child of template.content.childNodes) root.appendChild(copy(child))
  return root
}
