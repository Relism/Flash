import { Handle, Position } from '@xyflow/react'

// Invisible handles — React Flow needs them as edge anchor points,
// but they must not appear as interactive dots in the read-only graph.
const HANDLE_STYLE = { opacity: 0, width: 6, height: 6, pointerEvents: 'none', border: 'none', background: 'transparent' }
const TARGET_HANDLE = <Handle type="target" position={Position.Left}  style={HANDLE_STYLE} />
const SOURCE_HANDLE = <Handle type="source" position={Position.Right} style={HANDLE_STYLE} />

const METHOD_COLORS = {
  GET:     { bg: '#0d4429', color: '#4ade80' },
  POST:    { bg: '#172554', color: '#60a5fa' },
  PUT:     { bg: '#451a03', color: '#fb923c' },
  PATCH:   { bg: '#2e1065', color: '#c084fc' },
  DELETE:  { bg: '#450a0a', color: '#f87171' },
  OPTIONS: { bg: '#1c1917', color: '#a8a29e' },
  HEAD:    { bg: '#1c1917', color: '#a8a29e' },
}

// Shared truncation style — applied to any single-line text that can overflow.
const TRUNCATE = {
  overflow:     'hidden',
  textOverflow: 'ellipsis',
  whiteSpace:   'nowrap',
}

/**
 * Unified handler node — three modes: ABSTRACT, CONCRETE, LAMBDA.
 * No connection handles: the graph is a read-only visualisation.
 * Text is clamped to the node width and truncated with an ellipsis.
 */
export default function HandlerNode({ data, selected }) {

  // ── ABSTRACT ──────────────────────────────────────────────────────────
  if (data.isAbstract) {
    return (
      <div style={{
        background: 'rgba(239,68,68,0.05)',
        border: selected ? '2px solid #60a5fa' : '1px solid #ef4444',
        borderRadius: 8,
        padding: '8px 14px',
        width: 220,
        overflow: 'hidden',
        boxSizing: 'border-box',
        fontFamily: 'system-ui, sans-serif',
      }}>
        {TARGET_HANDLE}
        {SOURCE_HANDLE}
        <div style={{ fontSize: 9, color: '#ef4444', fontWeight: 700, letterSpacing: 0.5, marginBottom: 3 }}>
          ABSTRACT
        </div>
        <div style={{ fontSize: 12, fontWeight: 600, color: '#e2e8f0', fontFamily: 'monospace', ...TRUNCATE }}>
          {data.name}
        </div>
      </div>
    )
  }

  // ── LAMBDA ────────────────────────────────────────────────────────────
  if (data.isLambda) {
    const method   = data.method || 'GET'
    const path     = data.path   || '/'
    const m        = METHOD_COLORS[method] || METHOD_COLORS.OPTIONS
    const pathHtml = path.replace(/\{([^}]+)\}/g, '<span style="color:#a78bfa">{$1}</span>')

    return (
      <div style={{
        background: '#1a1d27',
        border: selected ? '2px solid #60a5fa' : '1px solid #3b82f6',
        borderRadius: 8,
        padding: '10px 12px',
        width: 230,
        overflow: 'hidden',
        boxSizing: 'border-box',
        fontFamily: 'system-ui, sans-serif',
      }}>
        {TARGET_HANDLE}
        {SOURCE_HANDLE}
        <div style={{ fontSize: 9, color: '#60a5fa', fontWeight: 700, letterSpacing: 0.5, marginBottom: 7 }}>
          LAMBDA
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, overflow: 'hidden' }}>
          <span style={{
            background: m.bg, color: m.color,
            fontSize: 9, fontWeight: 700, padding: '2px 5px',
            borderRadius: 3, fontFamily: 'monospace', flexShrink: 0,
          }}>
            {method}
          </span>
          <span
            style={{ fontSize: 10, fontFamily: 'monospace', color: '#8892a4', ...TRUNCATE }}
            dangerouslySetInnerHTML={{ __html: pathHtml }}
          />
        </div>
        {data.middleware?.length > 0 && (
          <div style={{ display: 'flex', gap: 3, flexWrap: 'wrap', marginTop: 7 }}>
            {data.middleware.map(mw => (
              <span key={mw} style={{
                background: '#1f1a0e', color: '#f6ad55',
                fontSize: 8, fontWeight: 600, padding: '1px 4px',
                borderRadius: 2, fontFamily: 'monospace',
                ...TRUNCATE, maxWidth: '100%',
              }}>{mw}</span>
            ))}
          </div>
        )}
      </div>
    )
  }

  // ── CONCRETE ──────────────────────────────────────────────────────────
  return (
    <div style={{
      background: '#1a1d27',
      border: selected ? '2px solid #60a5fa' : '1px solid #3b82f6',
      borderRadius: 8,
      padding: '10px 12px',
      width: 260,
      overflow: 'hidden',
      boxSizing: 'border-box',
      fontFamily: 'system-ui, sans-serif',
    }}>
      {TARGET_HANDLE}
      {SOURCE_HANDLE}
      {/* Header */}
      <div style={{ marginBottom: 8 }}>
        <div style={{ fontSize: 9, color: '#60a5fa', fontWeight: 700, letterSpacing: 0.5, marginBottom: 2 }}>
          HANDLER
        </div>
        <div style={{ fontSize: 12, fontWeight: 700, color: '#e2e8f0', fontFamily: 'monospace', ...TRUNCATE }}>
          {data.name}
        </div>
      </div>

      <div style={{ height: 1, background: '#2e3347', marginBottom: 8 }} />

      {/* Routes */}
      <div style={{ marginBottom: data.middleware?.length > 0 ? 8 : 0 }}>
        {data.routes?.map((route, i) => {
          const m        = METHOD_COLORS[route.method] || METHOD_COLORS.OPTIONS
          const pathHtml = (route.path || '/').replace(
            /\{([^}]+)\}/g,
            '<span style="color:#a78bfa">{$1}</span>'
          )
          return (
            <div key={i} style={{
              display: 'flex', alignItems: 'center', gap: 6,
              marginBottom: i < data.routes.length - 1 ? 5 : 0,
              overflow: 'hidden',
            }}>
              <span style={{
                background: m.bg, color: m.color,
                fontSize: 8, fontWeight: 700, padding: '2px 5px',
                borderRadius: 3, fontFamily: 'monospace', flexShrink: 0,
              }}>
                {route.method}
              </span>
              <span
                style={{ fontSize: 10, fontFamily: 'monospace', color: '#8892a4', ...TRUNCATE }}
                dangerouslySetInnerHTML={{ __html: pathHtml }}
              />
            </div>
          )
        })}
      </div>

      {/* Middleware */}
      {data.middleware?.length > 0 && (
        <div style={{ display: 'flex', gap: 3, flexWrap: 'wrap' }}>
          {data.middleware.map(mw => (
            <span key={mw} style={{
              background: '#1f1a0e', color: '#f6ad55',
              fontSize: 8, fontWeight: 600, padding: '1px 4px',
              borderRadius: 2, fontFamily: 'monospace',
              ...TRUNCATE, maxWidth: '100%',
            }}>{mw}</span>
          ))}
        </div>
      )}
    </div>
  )
}
