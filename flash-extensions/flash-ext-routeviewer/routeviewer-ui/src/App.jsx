import { useEffect, useState, useCallback, useMemo, useRef } from 'react'
import {
  ReactFlow, Background, Controls, MiniMap, ReactFlowProvider,
  useNodesState, useEdgesState, MarkerType, useReactFlow,
  getNodesBounds, getViewportForBounds,
} from '@xyflow/react'
import { toPng } from 'html-to-image'
import '@xyflow/react/dist/style.css'
import { normalizeGraph } from './normalize.js'
import { layoutGraph }    from './layout.js'
import HandlerNode        from './nodes/HandlerNode.jsx'
import './App.css'

const nodeTypes = { handler: HandlerNode }

// ── Export helper ─────────────────────────────────────────────────────────────
function useExport(exportRef) {
  const { getNodes } = useReactFlow()

  useEffect(() => {
    exportRef.current = () => {
      const nodes = getNodes()
      if (!nodes.length) return

      const bounds  = getNodesBounds(nodes)
      const pad     = 60
      const imgW    = Math.max(1920, bounds.width  + pad * 2)
      const imgH    = Math.max(1080, bounds.height + pad * 2)
      const vp      = getViewportForBounds(bounds, imgW, imgH, 0.1, 4, pad)

      toPng(document.querySelector('.react-flow__viewport'), {
        backgroundColor: '#0f1117',
        width:  imgW,
        height: imgH,
        style:  {
          width:     imgW + 'px',
          height:    imgH + 'px',
          transform: `translate(${vp.x}px,${vp.y}px) scale(${vp.zoom})`,
        },
      }).then(url => {
        const a = document.createElement('a')
        a.download = 'flash-routes.png'
        a.href = url
        a.click()
      }).catch(console.error)
    }
  }, [getNodes, exportRef])
}

// ── FlowContent ───────────────────────────────────────────────────────────────
function FlowContent({
  styledNodes, styledEdges, onNodesChange, onEdgesChange,
  onNodeMouseEnter, onNodeMouseLeave, showLambdas, searchQuery, exportRef,
}) {
  const { fitView } = useReactFlow()
  useExport(exportRef)

  useEffect(() => {
    setTimeout(() => fitView({ padding: 0.15, duration: 300 }), 50)
  }, [showLambdas, searchQuery, fitView])

  return (
    <ReactFlow
      nodes={styledNodes}
      edges={styledEdges}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      onNodeMouseEnter={onNodeMouseEnter}
      onNodeMouseLeave={onNodeMouseLeave}
      nodeTypes={nodeTypes}
      fitView
      fitViewOptions={{ padding: 0.15 }}
      colorMode="dark"
      minZoom={0.03}
      maxZoom={2}
      panOnDrag={true}
      panOnScroll={true}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable={false}
      zoomOnDoubleClick={true}
    >
      <Background color="#161822" gap={32} size={1} />
      <Controls showInteractive={false}
        style={{ background: '#12141c', border: '1px solid #1e2235' }} />
      <MiniMap
        style={{ background: '#12141c', border: '1px solid #1e2235' }}
        maskColor="rgba(0,0,0,0.5)"
        nodeColor={n => n.data?.isAbstract ? '#ef444499' : '#3b82f666'}
      />
    </ReactFlow>
  )
}

// ── Main ──────────────────────────────────────────────────────────────────────
export default function App() {
  const [allNodes, setAllNodes]          = useState([])
  const [allEdges, setAllEdges]          = useState([])
  const [nodes, setNodes, onNodesChange] = useNodesState([])
  const [edges, setEdges, onEdgesChange] = useEdgesState([])
  const [loading, setLoading]            = useState(true)
  const [error,   setError]              = useState(null)
  const [stats,   setStats]              = useState(null)
  const [hoveredNode, setHoveredNode]    = useState(null)
  const [highlighted, setHighlighted]    = useState({ nodes: new Set(), edges: new Set() })
  const [showLambdas, setShowLambdas]    = useState(false)
  const [searchQuery, setSearchQuery]    = useState('')
  const [exporting, setExporting]        = useState(false)
  const exportRef = useRef(null)

  // ── Load data ──────────────────────────────────────────────────────────────
  useEffect(() => {
    fetch('/routeviewer/data')
      .then(r => { if (!r.ok) throw new Error(r.statusText); return r.json() })
      .then(raw => {
        const routeCount = raw.nodes.filter(n => n.type === 'route').length
        const { nodes: n, edges: e } = normalizeGraph(raw.nodes, raw.edges)
        const { nodes: ln, edges: le } = layoutGraph(n, e)
        setAllNodes(ln)
        setAllEdges(le)
        setStats({ routes: routeCount })
        setLoading(false)
      })
      .catch(err => { setError(err.message); setLoading(false) })
  }, [])

  // ── Ancestor set builder ───────────────────────────────────────────────────
  const getAncestors = useMemo(() => {
    const parentOf = new Map()
    allEdges.forEach(e => { if (e.edgeType === 'extends') parentOf.set(e.target, e.source) })
    return (id) => {
      const set = new Set()
      let cur = parentOf.get(id)
      while (cur) { set.add(cur); cur = parentOf.get(cur) }
      return set
    }
  }, [allEdges])

  // ── Filter (lambda toggle + search) ───────────────────────────────────────
  useEffect(() => {
    const q = searchQuery.trim().toLowerCase()
    const lambdaIds = new Set(allNodes.filter(n => n.data?.isLambda).map(n => n.id))

    let visibleIds = new Set(allNodes.map(n => n.id))
    if (q) {
      const matched = new Set(
        allNodes.filter(n =>
          (n.data?.name || '').toLowerCase().includes(q) ||
          (n.data?.routes || []).some(r => r.path.toLowerCase().includes(q))
        ).map(n => n.id)
      )
      const withAncestors = new Set(matched)
      matched.forEach(id => getAncestors(id).forEach(a => withAncestors.add(a)))
      visibleIds = withAncestors
    }

    const filteredNodes = allNodes.filter(n => {
      if (lambdaIds.has(n.id) && !showLambdas) return false
      return visibleIds.has(n.id)
    })
    const filteredIds = new Set(filteredNodes.map(n => n.id))
    setNodes(filteredNodes)
    setEdges(allEdges.filter(e => filteredIds.has(e.source) && filteredIds.has(e.target)))
  }, [showLambdas, searchQuery, allNodes, allEdges, setNodes, setEdges, getAncestors])

  // ── Hover ──────────────────────────────────────────────────────────────────
  const onNodeMouseEnter = useCallback((_, node) => {
    const parentOf = new Map()
    edges.forEach(e => { if (e.edgeType === 'extends') parentOf.set(e.target, { pid: e.source, eid: e.id }) })
    const visited = new Set([node.id])
    const connEdges = new Set()
    const q = [node.id]
    while (q.length) {
      const cur = q.shift()
      const p = parentOf.get(cur)
      if (p && !visited.has(p.pid)) { connEdges.add(p.eid); visited.add(p.pid); q.push(p.pid) }
    }
    setHoveredNode(node.id)
    setHighlighted({ nodes: visited, edges: connEdges })
  }, [edges])

  const onNodeMouseLeave = useCallback(() => {
    setHoveredNode(null)
    setHighlighted({ nodes: new Set(), edges: new Set() })
  }, [])

  // ── Style pass ─────────────────────────────────────────────────────────────
  const styledNodes = nodes.map(n => ({
    ...n,
    style: { opacity: hoveredNode && !highlighted.nodes.has(n.id) ? 0.1 : 1, transition: 'opacity 0.15s' },
  }))

  const styledEdges = edges.map(e => {
    const base = { type: 'bezier', pathOptions: { curvature: 0.35 },
      style: { stroke: '#1e2235', strokeWidth: 1.5 },
      markerEnd: { type: MarkerType.ArrowClosed, width: 10, height: 10, color: '#1e2235' } }
    if (!hoveredNode) return { ...e, ...base }
    if (highlighted.edges.has(e.id)) return {
      ...e, type: 'bezier', pathOptions: { curvature: 0.35 },
      style: { stroke: '#60a5fa', strokeWidth: 2.5 },
      markerEnd: { type: MarkerType.ArrowClosed, width: 13, height: 13, color: '#60a5fa' },
    }
    return { ...e, ...base, style: { ...base.style, opacity: 0.04 } }
  })

  const lambdaCount  = allNodes.filter(n => n.data?.isLambda).length
  const handlerCount = allNodes.filter(n => !n.data?.isLambda).length

  const handleExport = useCallback(() => {
    setExporting(true)
    setTimeout(() => {
      exportRef.current?.()
      setTimeout(() => setExporting(false), 1200)
    }, 50)
  }, [])

  // ── Render ─────────────────────────────────────────────────────────────────
  return (
    <div style={{ display: 'flex', width: '100vw', height: '100vh', overflow: 'hidden', background: '#0f1117' }}>

      {/* ── Sidebar ──────────────────────────────────────────────────────── */}
      <aside style={{
        width: '240px', minWidth: '240px', flexShrink: 0,
        display: 'flex', flexDirection: 'column',
        background: '#0c0e15',
        borderRight: '1px solid #1a1d2a',
        fontFamily: 'system-ui, sans-serif',
        color: '#c8cfe0',
      }}>
        {/* Header */}
        <div style={{
          padding: '14px 16px 12px',
          borderBottom: '1px solid #1a1d2a',
          display: 'flex', alignItems: 'center', gap: 8,
        }}>
          <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: '-0.3px' }}>⚡ Route Viewer</span>
        </div>

        <div style={{ padding: '14px 14px', overflowY: 'auto', flex: 1 }}>

          {/* Search */}
          <Section label="SEARCH">
            <input
              type="text"
              placeholder="handler or path…"
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
              style={{
                width: '100%', boxSizing: 'border-box',
                background: '#12141e', border: '1px solid #1e2235',
                borderRadius: 5, padding: '6px 9px',
                color: '#c8cfe0', fontSize: 12, fontFamily: 'monospace',
                outline: 'none',
              }}
            />
            {searchQuery && (
              <div style={{ fontSize: 11, color: '#4a5370', marginTop: 5 }}>
                {nodes.length} node{nodes.length !== 1 ? 's' : ''} visible
              </div>
            )}
          </Section>

          {/* Lambda toggle */}
          <Section label="DISPLAY">
            <label style={{ display: 'flex', alignItems: 'center', gap: 9, cursor: 'pointer', fontSize: 12 }}>
              <input type="checkbox" checked={showLambdas}
                onChange={e => setShowLambdas(e.target.checked)}
                style={{ cursor: 'pointer', accentColor: '#3b82f6' }}
              />
              <span style={{ color: '#8892a4' }}>Show lambda handlers</span>
            </label>
            <div style={{ fontSize: 11, color: '#343b54', marginTop: 4, paddingLeft: 21 }}>
              {lambdaCount} lambda{lambdaCount !== 1 ? 's' : ''}
            </div>
          </Section>

          {/* Stats */}
          <Section label="STATS">
            {[
              ['Routes',   stats?.routes || 0],
              ['Handlers', handlerCount],
              ['Lambdas',  lambdaCount],
            ].map(([k, v]) => (
              <div key={k} style={{
                display: 'flex', justifyContent: 'space-between',
                fontSize: 12, marginBottom: 5,
              }}>
                <span style={{ color: '#4a5370' }}>{k}</span>
                <span style={{ color: '#e2e8f0', fontWeight: 600, fontFamily: 'monospace' }}>{v}</span>
              </div>
            ))}
          </Section>

          {/* Legend */}
          <Section label="LEGEND">
            {[
              { dot: '#3b82f6', border: '#3b82f6', label: 'Handler' },
              { dot: '#ef4444', border: '#ef4444', label: 'Abstract' },
              { dot: '#f6ad55', border: '#f6ad55', label: 'Middleware' },
            ].map(({ dot, label }) => (
              <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                <div style={{ width: 9, height: 9, borderRadius: 2, background: dot, flexShrink: 0, opacity: 0.8 }} />
                <span style={{ fontSize: 12, color: '#4a5370' }}>{label}</span>
              </div>
            ))}
          </Section>

          {/* Tips */}
          <div style={{ fontSize: 11, color: '#2d3347', lineHeight: 1.65, marginTop: 4 }}>
            Hover a node to trace its ancestry.<br />
            Search filters nodes + parents.
          </div>
        </div>

        {/* Export button */}
        <div style={{ padding: '12px 14px', borderTop: '1px solid #1a1d2a' }}>
          <button
            onClick={handleExport}
            disabled={exporting || loading}
            style={{
              width: '100%', padding: '8px 0',
              background: exporting ? '#1e2235' : '#12141e',
              border: '1px solid #1e2235',
              borderRadius: 6, color: exporting ? '#4a5370' : '#8892a4',
              fontSize: 12, cursor: exporting ? 'default' : 'pointer',
              fontFamily: 'system-ui, sans-serif',
              transition: 'all 0.15s',
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
            }}
          >
            {exporting ? '⏳ Exporting…' : '⬇ Export PNG'}
          </button>
        </div>
      </aside>

      {/* ── Graph area ────────────────────────────────────────────────────── */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', position: 'relative' }}>

        {/* Topbar */}
        <div style={{
          display: 'flex', alignItems: 'center',
          padding: '9px 18px',
          background: '#0c0e15',
          borderBottom: '1px solid #1a1d2a',
          flexShrink: 0, zIndex: 10,
        }}>
          <span style={{ fontSize: 13, fontWeight: 700, color: '#e2e8f0', letterSpacing: '-0.2px' }}>
            Flash Route Graph
          </span>
          <span style={{
            marginLeft: 'auto', display: 'flex', gap: 18,
            fontSize: 11, color: '#2d3347', fontFamily: 'system-ui',
          }}>
            {[['#3b82f6','handler'],['#ef4444','abstract'],['#f6ad55','middleware']].map(([c,l]) => (
              <span key={l} style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: c, display: 'inline-block', opacity: 0.8 }} />
                {l}
              </span>
            ))}
          </span>
        </div>

        {loading && <div className="center muted">Loading…</div>}
        {error   && <div className="center error">Error: {error}</div>}

        {!loading && !error && (
          <div style={{ flex: 1, position: 'relative' }}>
            <ReactFlowProvider>
              <FlowContent
                styledNodes={styledNodes}
                styledEdges={styledEdges}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onNodeMouseEnter={onNodeMouseEnter}
                onNodeMouseLeave={onNodeMouseLeave}
                showLambdas={showLambdas}
                searchQuery={searchQuery}
                exportRef={exportRef}
              />
            </ReactFlowProvider>
          </div>
        )}
      </div>
    </div>
  )
}

// ── Section helper ─────────────────────────────────────────────────────────────
function Section({ label, children }) {
  return (
    <div style={{ marginBottom: 18 }}>
      <div style={{
        fontSize: 9, fontWeight: 700, letterSpacing: 1,
        color: '#272d42', marginBottom: 8, fontFamily: 'monospace',
      }}>
        {label}
      </div>
      {children}
    </div>
  )
}
