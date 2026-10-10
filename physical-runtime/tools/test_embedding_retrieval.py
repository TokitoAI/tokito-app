import copy
import json
import unittest

import evaluate_embedding_retrieval as evaluation


class EmbeddingRetrievalEvaluationTest(unittest.TestCase):
    def test_corpus_frozen_schema_and_coverage(self):
        document = json.loads(evaluation.FIXTURES.read_text(encoding="utf-8"))
        evaluation.validate_fixture(document)
        self.assertEqual(len(document["corpora"]), 4)
        self.assertEqual(sum(len(c["records"]) for c in document["corpora"]), 24)
        self.assertEqual(sum(len(c["queries"]) for c in document["corpora"]), 64)
        for corpus in document["corpora"]:
            categories = {q["category"] for q in corpus["queries"]}
            self.assertTrue({"paraphrase", "unrelated", "ambiguous", "guard_unknown_reference", "guard_ambiguous_pin"} <= categories)

    def test_thresholds_are_frozen_not_tuned_by_fixture(self):
        self.assertEqual(evaluation.MINIMUM_SIMILARITY, .60)
        self.assertEqual(evaluation.MINIMUM_MARGIN, .08)
        self.assertIsNone(evaluation.decide([{"record_id": "a", "similarity": .599}, {"record_id": "b", "similarity": .1}])[0])
        self.assertIsNone(evaluation.decide([{"record_id": "a", "similarity": .9}, {"record_id": "b", "similarity": .85}])[0])
        self.assertEqual(evaluation.decide([{"record_id": "a", "similarity": .8}, {"record_id": "b", "similarity": .5}])[0], "a")

    def test_false_matches_and_abstentions_not_hidden(self):
        self.assertEqual(evaluation.classify(None, "bad"), "false_match")
        self.assertEqual(evaluation.classify("a", "b"), "wrong_match")
        self.assertEqual(evaluation.classify("a", None), "positive_abstention")
        self.assertEqual(evaluation.classify(None, None), "correct_abstention")

    def test_broken_expected_record_rejected(self):
        document = json.loads(evaluation.FIXTURES.read_text(encoding="utf-8"))
        broken = copy.deepcopy(document)
        broken["corpora"][0]["queries"][0]["expected"] = "missing"
        with self.assertRaises(ValueError):
            evaluation.validate_fixture(broken)

    def test_invalid_scores_rejected(self):
        for scores in ([], [{"record_id": "a", "similarity": float("nan")} ]):
            with self.assertRaises(ValueError):
                evaluation.decide(scores)

    def test_empty_or_single_class_corpus_rejected(self):
        document = json.loads(evaluation.FIXTURES.read_text(encoding="utf-8"))
        for kind in ("empty", "positive_only", "negative_only"):
            broken = copy.deepcopy(document)
            if kind == "empty":
                broken["corpora"] = []
            else:
                broken["corpora"][0]["queries"] = [q for q in broken["corpora"][0]["queries"]
                    if (q["expected"] is not None) == (kind == "positive_only")]
            with self.assertRaises(ValueError):
                evaluation.validate_fixture(broken)

    def test_guard_category_cannot_hide_changed_labels(self):
        document = json.loads(evaluation.FIXTURES.read_text(encoding="utf-8"))
        broken = copy.deepcopy(document)
        query = next(q for q in broken["corpora"][0]["queries"] if q["category"] == "guard_unknown_reference")
        query["expected"] = broken["corpora"][0]["records"][0]["id"]
        with self.assertRaises(ValueError):
            evaluation.validate_fixture(broken)


if __name__ == "__main__":
    unittest.main()
