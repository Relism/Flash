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
 * Concrete handler node — represents a handler that is bound to one or more routes.
 * Shows handler name, HTTP method + path, and middleware stack as inline badges.
 */
export default function ConcreteHandlerNode({ data }) {
  const method = data.methods?.[0] || ''
  const path = data.paths?.[0] || ''
  const m = METHOD_COLORS[method] || METHOD_COLORS.OPTIONS
  const pathHtml = path.replace(/\{([^}]+)\}/g, '<span style="color:#a78bfa">{$1}</span>')

  return (
    <div style={{
      background: '#1a1d27',
      border: '1px solid #3b82f6',
      borderRadius: 8,
      padding: '10px 14px',
      minWidth: 240,
      fontFamily: 'system-ui, sans-serif',
    }}>
      {/* Extends from parent handler */}
      <Handle type="target" position={Position.Top}
              style={{ background: '#2e3347' }} />
      {/* Extends to child handler (if any) */}
      <Handle type="source" position={Position.Bottom}
              style={{ background: '#2e3347' }} />

      {/* Handler class name */}
      <div style={{ marginBottom: 6 }}>
        <span style={{
          fontSize: 10, color: '#60a5fa', fontWeight: 700, letterSpacing: 0.5,
        }}>
          HANDLER
        </span>
        <div style={{
          fontSize: 13, fontWeight: 700, color: '#e2e8f0',
          fontFamily: 'monospace', marginTop: 2,
        }}>
          {data.handlerName}
        </div>
      </div>

      {/* Divider */}
      <div style={{ height: 1, background: '#2e3347', marginBottom: 6 }} />

      {/* Method badge + path */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <span style={{
          background: m.bg, color: m.color,
          fontSize: 10, fontWeight: 700, padding: '2px 7px',
          borderRadius: 4, fontFamily: 'monospace', flexShrink: 0,
        }}>
          {method}
        </span>
        <span
          style={{ fontSize: 11, fontFamily: 'monospace', color: '#8892a4' }}
          dangerouslySetInnerHTML={{ __html: pathHtml }}
        />
      </div>

      {/* Middleware badges */}
      {data.middleware && data.middleware.length > 0 && (
        <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
          {data.middleware.map(mw => (
            <span key={mw} style={{
              background: '#1f1a0e',
              color: '#f6ad55',
              fontSize: 9, fontWeight: 600, padding: '2px 6px',
              borderRadius: 3, fontFamily: 'monospace', whiteSpace: 'nowrap',
            }}>
              {mw}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}
