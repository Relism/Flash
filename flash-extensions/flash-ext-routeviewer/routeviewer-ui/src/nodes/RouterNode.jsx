/**
 * Section header — no edges, purely positional grouping.
 * Styled as a slim label bar above the router's route group.
 */
export default function RouterNode({ data }) {
  return (
    <div style={{
      background: 'linear-gradient(90deg, #16122a 0%, #1a1d27 100%)',
      border: '1px solid #3d2f7a',
      borderLeft: '3px solid #7c6af7',
      borderRadius: 6,
      padding: '6px 14px',
      minWidth: 240,
      fontFamily: 'system-ui, sans-serif',
      display: 'flex',
      alignItems: 'center',
      gap: 12,
    }}>
      <span style={{ fontSize: 10, color: '#7c6af7', fontWeight: 700, letterSpacing: 1, flexShrink: 0 }}>
        ROUTER
      </span>
      <span style={{ fontSize: 13, fontWeight: 700, color: '#e2e8f0', fontFamily: 'monospace' }}>
        {data.namespace}
      </span>
      <span style={{ fontSize: 11, color: '#4a5568', marginLeft: 'auto' }}>
        {data.routerType} · {data.routeCount}
      </span>
    </div>
  )
}
