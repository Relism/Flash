import { Handle, Position } from '@xyflow/react'

export default function MiddlewareNode({ data }) {
  return (
    <div style={{
      background: '#1f1a0e',
      border: '1px solid #92400e',
      borderRadius: 8,
      padding: '7px 14px',
      minWidth: 150,
      fontFamily: 'system-ui, sans-serif',
    }}>
      {/* shared node: sends applies edges to all routes that use this MW */}
      <Handle id="out-right" type="source" position={Position.Right}
              style={{ background: '#92400e' }} />

      <div style={{ fontSize: 10, color: '#f6ad55', fontWeight: 700, letterSpacing: 0.5, marginBottom: 2 }}>
        MIDDLEWARE
      </div>
      <div style={{ fontSize: 13, fontWeight: 600, color: '#fde68a', fontFamily: 'monospace' }}>
        {data.name}
      </div>
    </div>
  )
}
