#!/usr/bin/env python3
"""Content acceptance checks for local fascial study claims, not medical review."""
import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/content'))
from build_fascia import validate_evidence


class FasciaEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.content = json.loads((ROOT / 'app/src/main/assets/content/fascia.json').read_text())
        cls.groups = cls.content['connections']
        cls.by_id = {group['id']: group for group in cls.groups}

    def test_every_local_relation_has_resolvable_study_details(self):
        self.assertEqual(3, self.content['schemaVersion'])
        counts = validate_evidence(self.groups)
        self.assertEqual({'anatomical_continuity': 7, 'experimental_loading': 5,
            'human_indirect_observation': 2}, counts)
        for group in self.groups:
            self.assertTrue(group['evidenceReferences'])
            for evidence in group['evidenceReferences']:
                self.assertNotIn('strengthGrade', evidence)
                self.assertNotIn('forceMagnitude', evidence)

    def test_surface_auxiliary_lines_cannot_inherit_loading_studies(self):
        contexts = [p for g in self.groups for p in g['paths'] if p['kind'] == 'anatomical_context_schematic']
        self.assertEqual(32, len(contexts))
        self.assertTrue(all(p['meaningZh'] == '组织范围辅助线（非力线）' and not p['evidenceIds'] for p in contexts))
        groups = copy.deepcopy(self.groups)
        first = groups[0]
        context = next(p for p in first['paths'] if p['kind'] == 'anatomical_context_schematic')
        evidence = first['evidenceReferences'][0]
        evidence['relatedPathIds'].append(context['id'])
        context['evidenceIds'] = [evidence['id']]
        with self.assertRaises(AssertionError):
            validate_evidence(groups)

    def test_middle_layer_anatomy_does_not_prove_posterior_branch(self):
        group = self.by_id['transversus-abdominis-thoracolumbar-fascia']
        anatomy = next(e for e in group['evidenceReferences'] if e['kind'] == 'anatomical_continuity')
        self.assertEqual({'tra-tlf-left-middle', 'tra-tlf-right-middle'}, set(anatomy['relatedPathIds']))
        loading = next(e for e in group['evidenceReferences'] if e['kind'] == 'experimental_loading')
        self.assertEqual(4, len(loading['relatedPathIds']))
        self.assertIn('10 N', loading['conditionsZh'])
        self.assertIn('未赋给', loading['limitationsZh'])

    def test_human_hip_and_foot_observations_remain_indirect(self):
        indirect = [e for g in self.groups for e in g['evidenceReferences'] if e['kind'] == 'human_indirect_observation']
        self.assertEqual({'ld-tlf-human', 'heel-human-imaging'}, {e['id'] for e in indirect})
        hip = next(e for e in indirect if e['id'] == 'ld-tlf-human')
        self.assertIn('37名', hip['sampleZh'])
        self.assertIn('未直接测量', hip['limitationsZh'])
        foot = next(e for e in indirect if e['id'] == 'heel-human-imaging')
        self.assertIn('52例', foot['sampleZh'])
        self.assertIn('未测量传力', foot['measurementZh'])

    def test_study_refs_cannot_silently_point_to_an_unlisted_source(self):
        groups = copy.deepcopy(self.groups)
        groups[0]['evidenceReferences'][0]['sourceIds'] = ['not-in-this-connection']
        with self.assertRaises(AssertionError):
            validate_evidence(groups)

    def test_consensus_scope_distinguishes_terminology_proposal_and_discussion(self):
        basis = self.content['consensusBasis']
        self.assertEqual('anatomy_first_with_local_study_evidence', basis['approach'])
        refs = {ref['id']: ref for ref in basis['references']}
        self.assertEqual('terminology_consensus_process', refs['fnc2019']['role'])
        self.assertEqual('sports_medicine_consensus_statement', refs['connect2018']['role'])
        self.assertEqual('definition_proposal_not_universal_standard', refs['stecco2025proposal']['role'])
        self.assertEqual('critical_nomenclature_discussion', refs['neumann2026discussion']['role'])
        self.assertFalse(self.content['displayPolicy']['forceVectors'])
        self.assertFalse(self.content['displayPolicy']['forceMagnitudes'])


if __name__ == '__main__':
    unittest.main()
