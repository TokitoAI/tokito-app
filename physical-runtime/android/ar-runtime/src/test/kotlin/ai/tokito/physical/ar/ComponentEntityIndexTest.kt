package ai.tokito.physical.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ComponentEntityIndexTest {
    private fun node(id: Int, parent: Int = 0, name: String? = null) = ComponentEntityIndex.Node(id, parent, name)

    @Test fun `all meshes in one authored group are selectable`() {
        val index = ComponentEntityIndex.build(listOf(node(1), node(2, 1, "ref:U7"),
            node(3, 2), node(4, 2), node(5, 1, "ref:C4"), node(6, 1, "substrate")),
            setOf(3, 4, 5, 6), "ref:")
        assertEquals(setOf(3, 4), index.resolve(setOf("U7")))
        assertEquals(setOf(3, 4, 5), index.resolve(setOf("U7", "C4")))
        assertEquals("U7", index.referencesByRenderable[4])
        assertNull(index.referencesByRenderable[6])
    }

    @Test fun `renderable root remains compatible`() {
        val index = ComponentEntityIndex.build(listOf(node(1, name = "part:R2")), setOf(1), "part:")
        assertEquals(setOf(1), index.resolve(setOf("R2")))
        assertNull(index.resolve(setOf("R2", "missing")))
        assertNull(index.resolve(emptySet()))
    }

    @Test fun `renderer owned outer transform does not become a component`() {
        val index = ComponentEntityIndex.build(listOf(node(99), node(1, 99),
            node(2, 1, "ref:U1"), node(3, 2)), setOf(3), "ref:")
        assertEquals(setOf(3), index.resolve(setOf("U1")))
        assertEquals(setOf("U1"), index.groups.keys)
        assertNull(index.referencesByRenderable[99])
    }

    @Test fun `geometry missing is not selectable`() {
        val index = ComponentEntityIndex.build(listOf(node(1, name = "ref:J8")), emptySet(), "ref:")
        assertNull(index.resolve(setOf("J8")))
    }

    @Test fun `duplicate and nested component identities are rejected`() {
        for (nodes in listOf(listOf(node(1, name = "ref:R2"), node(2, name = "ref:R2")),
            listOf(node(1, name = "ref:R2"), node(2, 1, "ref:U2")))) {
            assertFailsWith<IllegalArgumentException> { ComponentEntityIndex.build(nodes, setOf(2), "ref:") }
        }
    }

    @Test fun `cycles detached parents and duplicate entities are rejected`() {
        for (nodes in listOf(listOf(node(1, 2), node(2, 1)), listOf(node(1, 99)),
            listOf(node(1), node(1)))) {
            assertFailsWith<IllegalArgumentException> { ComponentEntityIndex.build(nodes, setOf(1), "ref:") }
        }
    }

    @Test fun `input order cannot change ownership`() {
        val nodes = listOf(node(1), node(2, 1, "ref:J3"), node(3, 2), node(4, 3))
        assertEquals(ComponentEntityIndex.build(nodes, setOf(4), "ref:").groups,
            ComponentEntityIndex.build(nodes.reversed(), setOf(4), "ref:").groups)
    }

    @Test fun `depth budget cannot be bypassed by memoized parents`() {
        val nodes = (1..257).map { node(it, it - 1) }
        for (ordered in listOf(nodes, nodes.reversed())) {
            assertFailsWith<IllegalArgumentException> { ComponentEntityIndex.build(ordered, setOf(257), "ref:") }
        }
    }
}
