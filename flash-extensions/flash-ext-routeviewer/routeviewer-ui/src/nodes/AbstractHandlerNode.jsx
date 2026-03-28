import { Handle, Position } from '@xyflow/react'

/**
 * Abstract handler node — represents a handler in the inheritance chain
 * that is not directly bound to a route. Minimal styling, emphasizes the hierarchy.
 */
export default function AbstractHandlerNode({ data }) {
  return (
    <div style={{
      background: '#13161f',
      border: '1px solid #2a2f42',
      borderRadius: 8,
      padding: '8px 12px',
      minWidth: 160,
      fontFamily: 'system-ui, sans-serif',
      opacity: 0.8,
    }}>
      {/* Extends from parent handler */}
      <Handle type="target" position={Position.Top}
              style={{ background: '#2a2f42' }} />
      {/* Extends to child handler */}
      <Handle type="source" position={Position.Bottom}
              style={{ background: '#2a2f42' }} />

      <div style={{
        fontSize: 10, color: '#4a5568', fontWeight: 700, letterSpacing: 0.5,
        marginBottom: 3,
      }}>
        ABSTRACT
      </div>
      <div style={{
        fontSize: 12, fontWeight: 500, color: '#6b7694',
        fontFamily: 'monospace',
      }}>
        {data.name}
      </div>
    </div>
  )
}
