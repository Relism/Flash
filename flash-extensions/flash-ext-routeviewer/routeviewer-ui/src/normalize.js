/**
 * Canonical Graph Normalization.
 *
 * Transform raw route/handler nodes into a class-centric DAG where:
 * - Each handler class appears exactly ONCE
 * - Inheritance is the primary relationship
 * - Routes are attached to leaf (concrete) handlers
 * - Middleware is extracted and attached to handlers
 * - Lambda routes (no handler) become isolated leaf nodes
 */

export function normalizeGraph(rawNodes, rawEdges) {
  const byId = Object.fromEntries(rawNodes.map(n => [n.id, n]))
  const ofType = t => rawNodes.filter(n => n.type === t)

  const routeRaw = ofType('route')
  const handlerRaw = ofType('handler')

  // ── Extract metadata per route ──────────────────────────────────────────
  const routeMetadata = new Map() // routeId → {method, path, middleware: []}

  routeRaw.forEach(route => {
    routeMetadata.set(route.id, {
      method: route.data?.method || 'GET',
      path: route.data?.path || '/',
      middleware: [],
    })
  })

  // ── Extract middleware per route ───────────────────────────────────────
  const wrapsEdges = rawEdges.filter(e => e.edgeType === 'wraps')
  const wrapsOf = new Map() // wrappedNode → wrapperNode
  wrapsEdges.forEach(e => wrapsOf.set(e.target, e.source))

  routeRaw.forEach(route => {
    const middleware = []
    let cur = wrapsOf.get(route.id)
    while (cur && byId[cur]?.type === 'middleware') {
      middleware.unshift(byId[cur].data.name)
      cur = wrapsOf.get(cur)
    }
    if (middleware.length) {
      routeMetadata.get(route.id).middleware = middleware
    }
  })

  // ── Build inheritance map ──────────────────────────────────────────────
  // handlerClassName → parentClassName
  const inheritanceMap = new Map()
  const extendsEdges = rawEdges.filter(e => e.edgeType === 'extends')

  extendsEdges.forEach(e => {
    const childNode = byId[e.source]
    const parentNode = byId[e.target]
    if (childNode?.data?.name && parentNode?.data?.name) {
      inheritanceMap.set(childNode.data.name, parentNode.data.name)
    }
  })

  // ── Build handler chains per route ─────────────────────────────────────
  const handlesEdges = rawEdges.filter(e => e.edgeType === 'handles')
  const routeToChain = new Map() // routeId → [className, parentClassName, ...]

  handlesEdges.forEach(e => {
    const handlerNode = byId[e.target]
    if (handlerNode?.data?.name) {
      const chain = []
      let cur = handlerNode.data.name
      while (cur) {
        chain.push(cur)
        cur = inheritanceMap.get(cur)
      }
      routeToChain.set(e.source, chain)
    }
  })

  // Ensure ALL routes are in the chain map (routes without handlers get empty chain)
  routeRaw.forEach(route => {
    if (!routeToChain.has(route.id)) {
      routeToChain.set(route.id, [])
    }
  })

  // ── Collect all unique handler class names ──────────────────────────────
  const allClassNames = new Set()
  routeToChain.forEach(chain => chain.forEach(name => allClassNames.add(name)))

  // ── Create canonical handler nodes ────────────────────────────────────
  const canonicalHandlers = new Map() // class:ClassName → {id, name, isAbstract, routes[], parentId, isLambda}

  ;[...allClassNames].forEach(className => {
    const id = 'class:' + className
    canonicalHandlers.set(id, {
      id,
      name: className,
      isAbstract: true, // will be marked false if used as concrete
      routes: [],
      parentId: null,
      isLambda: false,
    })
  })

  // ── Wire up inheritance ────────────────────────────────────────────────
  inheritanceMap.forEach((parentClassName, childClassName) => {
    const childId = 'class:' + childClassName
    const parentId = 'class:' + parentClassName
    if (canonicalHandlers.has(childId) && canonicalHandlers.has(parentId)) {
      canonicalHandlers.get(childId).parentId = parentId
    }
  })

  // ── Attach routes to concrete handlers ──────────────────────────────────
  routeToChain.forEach((chain, routeId) => {
    if (chain.length === 0) {
      // Lambda: no handler
      const meta = routeMetadata.get(routeId)
      const lambdaId = 'class:Lambda:' + meta.method + ':' + encodeURIComponent(meta.path)
      canonicalHandlers.set(lambdaId, {
        id: lambdaId,
        name: 'Lambda Handler',
        isAbstract: false,
        isLambda: true,
        method: meta.method,
        path: meta.path,
        routes: [{
          method: meta.method,
          path: meta.path,
          middleware: meta.middleware,
        }],
        parentId: null,
      })
    } else {
      // Normal handler chain: mark concrete (first = depth 0)
      const concreteClassName = chain[0]
      const concreteId = 'class:' + concreteClassName
      if (canonicalHandlers.has(concreteId)) {
        const handler = canonicalHandlers.get(concreteId)
        handler.isAbstract = false
        const meta = routeMetadata.get(routeId)
        handler.routes.push({
          method: meta.method,
          path: meta.path,
          middleware: meta.middleware,
        })
      }
    }
  })

  // ── Build output nodes ────────────────────────────────────────────────
  const nodes = [...canonicalHandlers.values()].map(handler => ({
    id: handler.id,
    type: 'handler',
    data: {
      name: handler.name,
      isAbstract: handler.isAbstract,
      isLambda: handler.isLambda,
      method: handler.method,
      path: handler.path,
      routes: handler.routes,
      middleware: handler.routes.length > 0
        ? [...new Set(handler.routes.flatMap(r => r.middleware))]
        : [],
    },
  }))

  // ── Build output edges (deduped extends only) ──────────────────────────
  const edgeSet = new Set()
  const edges = []

  canonicalHandlers.forEach((handler, handlerId) => {
    if (handler.parentId) {
      const key = handler.parentId + '->' + handlerId
      if (!edgeSet.has(key)) {
        edgeSet.add(key)
        edges.push({
          id: key,
          source: handler.parentId,
          target: handlerId,
          edgeType: 'extends',
        })
      }
    }
  })

  return { nodes, edges }
}
