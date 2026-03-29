/**
 * Custom recursive tree layout — Left to Right.
 *
 * Each node is centred vertically relative to its children's block.
 * Siblings are packed tightly; different root trees are separated by ROOT_GAP.
 * Lambda handlers are placed in a compact grid below the main tree.
 *
 * This avoids Dagre's global ranking which puts all siblings in a single
 * long tower regardless of the number of nodes.
 */

// ── Constants ────────────────────────────────────────────────────────────────
const RANK_GAP    = 60   // horizontal gap between a node's right edge and its children's left edge
const SIBLING_GAP = 12   // vertical gap between siblings that belong to the same parent
const ROOT_GAP    = 48   // vertical gap between independent subtrees (different roots)
const MARGIN_X    = 60
const MARGIN_Y    = 60

const ABSTRACT_W  = 220
const ABSTRACT_H  = 50
const CONCRETE_W  = 260
const LAMBDA_W    = 230
const LAMBDA_H    = 80

// ── Node sizing ──────────────────────────────────────────────────────────────
function nodeWidth(node) {
  return node.data?.isAbstract ? ABSTRACT_W : CONCRETE_W
}

function nodeHeight(node) {
  if (node.data?.isAbstract) return ABSTRACT_H
  const routes   = node.data?.routes?.length  || 1
  const hasMw    = (node.data?.middleware?.length || 0) > 0
  // header(44) + divider(9) + routes*22 + [mw row 22] + padding(20)
  return 44 + 9 + routes * 22 + (hasMw ? 22 : 0) + 20
}

// ── Subtree height (recursive) ────────────────────────────────────────────────
function subtreeHeight(nodeId, childrenMap, nodeById) {
  const kids  = childrenMap.get(nodeId) || []
  const selfH = nodeHeight(nodeById[nodeId])
  if (!kids.length) return selfH

  const kidsH = kids.reduce((acc, id, i) => {
    return acc + subtreeHeight(id, childrenMap, nodeById) + (i > 0 ? SIBLING_GAP : 0)
  }, 0)

  return Math.max(selfH, kidsH)
}

// ── Place a node and its subtree ──────────────────────────────────────────────
function placeNode(nodeId, x, y, childrenMap, nodeById, positions) {
  const node    = nodeById[nodeId]
  const kids    = childrenMap.get(nodeId) || []
  const selfH   = nodeHeight(node)
  const selfW   = nodeWidth(node)
  const totalH  = subtreeHeight(nodeId, childrenMap, nodeById)

  // Centre this node within the height its subtree occupies
  positions.set(nodeId, { x, y: y + (totalH - selfH) / 2 })

  if (kids.length) {
    const childX = x + selfW + RANK_GAP
    let   childY = y
    kids.forEach(kidId => {
      const kidH = subtreeHeight(kidId, childrenMap, nodeById)
      placeNode(kidId, childX, childY, childrenMap, nodeById, positions)
      childY += kidH + SIBLING_GAP
    })
  }
}

// ── Main export ───────────────────────────────────────────────────────────────
export function layoutGraph(nodes, edges) {
  // Separate lambdas from class-based handlers
  const lambdas  = nodes.filter(n => n.data?.isLambda)
  const regulars = nodes.filter(n => !n.data?.isLambda)

  const nodeById    = Object.fromEntries(regulars.map(n => [n.id, n]))
  const childrenMap = new Map()   // parentId → [childId, ...]
  const parentSet   = new Set()   // ids that have a parent

  edges.forEach(e => {
    if (e.edgeType !== 'extends') return
    if (!childrenMap.has(e.source)) childrenMap.set(e.source, [])
    childrenMap.get(e.source).push(e.target)
    parentSet.add(e.target)
  })

  // Roots = regular nodes with no incoming extends edge
  const roots = regulars.filter(n => !parentSet.has(n.id))

  // Layout each root subtree
  const positions = new Map()
  let curY = MARGIN_Y

  roots.forEach(root => {
    const treeH = subtreeHeight(root.id, childrenMap, nodeById)
    placeNode(root.id, MARGIN_X, curY, childrenMap, nodeById, positions)
    curY += treeH + ROOT_GAP
  })

  // Apply positions
  let maxX = -Infinity
  let maxY = -Infinity
  let minY =  Infinity

  const layoutedNodes = regulars.map(node => {
    const pos = positions.get(node.id) || { x: MARGIN_X, y: MARGIN_Y }
    const r = pos.x + nodeWidth(node)
    const b = pos.y + nodeHeight(node)
    if (r > maxX) maxX = r
    if (b > maxY) maxY = b
    if (pos.y < minY) minY = pos.y
    return { ...node, position: pos }
  })

  // ── Lambda grid — centred below the main tree ────────────────────────────
  if (lambdas.length) {
    const treeWidth  = maxX - MARGIN_X
    const cols       = Math.min(4, Math.max(2, Math.ceil(Math.sqrt(lambdas.length))))
    const colWidth   = LAMBDA_W + 30
    const rowHeight  = LAMBDA_H + 16
    const gridWidth  = cols * colWidth - 30
    const gridStartX = MARGIN_X + Math.max(0, (treeWidth - gridWidth) / 2)
    const gridStartY = maxY + 80

    lambdas.forEach((node, i) => {
      layoutedNodes.push({
        ...node,
        position: {
          x: gridStartX + (i % cols) * colWidth,
          y: gridStartY + Math.floor(i / cols) * rowHeight,
        },
      })
    })
  }

  // ── Edges ────────────────────────────────────────────────────────────────
  const layoutedEdges = edges
    .filter(e => e.edgeType === 'extends')
    .map((edge, idx) => ({
      ...edge,
      id:          edge.id || `ext-${idx}`,
      type:        'bezier',
      pathOptions: { curvature: 0.35 },
      style:       { stroke: '#2e3347', strokeWidth: 1.5 },
      markerEnd:   { type: 'arrowclosed', width: 11, height: 11, color: '#2e3347' },
      animated:    false,
    }))

  return { nodes: layoutedNodes, edges: layoutedEdges }
}
