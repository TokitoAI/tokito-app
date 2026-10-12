package ai.tokito.physical.ar

/** Resolves authored component roots to every descendant renderable, once at load.
 * Entity IDs are renderer-local. No detector class, filename or model answer can
 * create a reference designator. Ambiguous or corrupt hierarchies are rejected.
 */
class ComponentEntityIndex private constructor(
    val groups: Map<String, Set<Int>>,
    val referencesByRenderable: Map<Int, String>,
) {
    data class Node(val entity: Int, val parent: Int, val name: String?)

    fun resolve(references: Set<String>): Set<Int>? {
        if (references.isEmpty() || references.any { groups[it].isNullOrEmpty() }) return null
        return references.flatMapTo(linkedSetOf()) { groups.getValue(it) }
    }

    companion object {
        fun build(nodes: List<Node>, renderables: Set<Int>, prefix: String): ComponentEntityIndex {
            require(prefix.isNotBlank() && prefix.length <= 32)
            require(nodes.size <= 100_000 && renderables.size <= 100_000)
            val byId = nodes.associateBy(Node::entity)
            require(byId.size == nodes.size && 0 !in byId)
            require(renderables.all(byId::containsKey))
            val roots = linkedMapOf<Int, String>()
            val seenReferences = hashSetOf<String>()
            for (node in nodes) {
                require(node.parent == 0 || node.parent in byId)
                if (node.name?.startsWith(prefix) == true) {
                    val reference = node.name.removePrefix(prefix)
                    require(reference.isNotBlank() && reference.length <= 128 && reference.none(Char::isISOControl))
                    require(seenReferences.add(reference)) { "Duplicate component selector" }
                    roots[node.entity] = reference
                }
            }
            val owners = hashMapOf<Int, String?>()
            val depths = hashMapOf<Int, Int>()
            for (node in nodes) {
                val path = linkedSetOf<Int>()
                var current = node.entity
                while (current != 0 && current !in owners) {
                    require(path.add(current) && path.size <= 256) { "Invalid component hierarchy" }
                    current = byId.getValue(current).parent
                }
                var owner = owners[current]
                var depth = depths[current] ?: 0
                for (entity in path.toList().asReversed()) {
                    require(++depth <= 256) { "Component hierarchy exceeds depth budget" }
                    roots[entity]?.let { reference ->
                        require(owner == null) { "Nested component selectors" }
                        owner = reference
                    }
                    owners[entity] = owner
                    depths[entity] = depth
                }
            }
            val groups = seenReferences.associateWith { linkedSetOf<Int>() }
            val reverse = linkedMapOf<Int, String>()
            for (entity in renderables) {
                owners[entity]?.let { reference ->
                    groups.getValue(reference).add(entity)
                    reverse[entity] = reference
                }
            }
            return ComponentEntityIndex(groups.mapValues { it.value.toSet() }, reverse.toMap())
        }
    }
}
