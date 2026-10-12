import copy
import unittest

from admit_optimized_twin import verify


def scene():
    return {
        'nodes': [{'name': 'ref:U1', 'mesh': 0}],
        'meshes': [{'primitives': [{'attributes': {'POSITION': 0}}]}],
        'accessors': [{'min': [0, 0, 0], 'max': [0.01, 0.01, 0.01]}],
        'scenes': [{'nodes': [0]}],
    }


class OptimizedTwinTest(unittest.TestCase):
    def test_component_subtrees_can_be_repacked(self):
        source = scene()
        target = copy.deepcopy(source)
        target['nodes'][0].pop('mesh')
        target['nodes'][0]['children'] = [1]
        target['nodes'].append({'mesh': 0})
        self.assertEqual({'component_references': 1, 'draw_primitives': 1}, verify(source, target, 'ref:'))

    def test_identity_and_placement_cannot_drift(self):
        for mutation in ('name', 'translation', 'mesh'):
            with self.subTest(mutation=mutation):
                source, target = scene(), scene()
                if mutation == 'name':
                    target['nodes'][0]['name'] = 'ref:U2'
                elif mutation == 'translation':
                    target['nodes'][0]['translation'] = [0.01, 0, 0]
                else:
                    target['nodes'][0].pop('mesh')
                with self.assertRaises(ValueError):
                    verify(source, target, 'ref:')


if __name__ == '__main__':
    unittest.main()
