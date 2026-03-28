import { Handle, Position } from '@xyflow/react'

const METHOD_COLORS = {
  GET:     { bg: '#0d4429', color: '#4ade80' },
  POST:    { bg: '#172554', color: '#60a5fa' },
  PUT:     { bg: '#451a03', color: '#fb923c' },
  PATCH:   { bg: '#2e1065', color: '#c084fc' },
  DELETE:  { bg: '#450a0a', color: '#f87171' },
  OPTIONS: { bg: '#1c1917', color: '#a8a29e' },
  HEAD:    { bg: '#1c1917', color: '#a8a29e' },
}

/**
 * Combined entry node — shows the concrete handler name above
 * and the HTTP method + path below. Replaces the old split
 * Route → Handler[0] pair.
 */
export default function RouteNode({ data }) {
  const m    = METHOD_COLORS[data.method] ?? METHOD_COLORS.OPTIONS
  const path = data.path.replace(/\{([^}]+)\}/g, '<span style="color:#a78bfa">{$1}</span>')
  const isSimple = !data.handlerName || data.handlerName === 'Simple Handler'

  return (
    <div style={{
      background: '#1a1d27',
      border: '1px solid #3b82f6',
      borderRadius: 8,
      padding: '8px 14px',
      minWidth: 200,
      fontFamily: 'system-ui, sans-serif',
    }}>
      {/* receives MW → entry "applies" edges */}
      <Handle id="in-left"   type="target" position={Position.Left}
              style={{ background: '#2e3347' }} />
      {/* sends extends edge to abstract parent chain */}
      <Handle id="out-bottom" type="source" position={Position.Bottom}
              style={{ background: '#2e3347' }} />

      {/* Handler class name */}
      <div style={{ marginBottom: 6 }}>
        <span style={{ fontSize: 10, color: '#60a5fa', fontWeight: 700, letterSpacing: 0.5 }}>
          {isSimple ? 'HANDLER' : 'HANDLER'}
        </span>
        <div style={{
          fontSize: 13, fontWeight: 700, color: '#e2e8f0',
          fontFamily: 'monospace', marginTop: 1,
        }}>
          {isSimple ? 'Simple Handler' : data.handlerName}
        </div>
      </div>

      {/* Divider */}
      <div style={{ height: 1, background: '#2e3347', marginBottom: 6 }} />

      {/* Method badge + path */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{
          background: m.bg, color: m.color,
          fontSize: 10, fontWeight: 700, padding: '2px 7px',
          borderRadius: 4, fontFamily: 'monospace', flexShrink: 0,
        }}>{data.method}</span>
        <span
          style={{ fontSize: 12, fontFamily: 'monospace', color: '#8892a4' }}
          dangerouslySetInnerHTML={{ __html: path }}
        />
      </div>
    </div>
  )
}
