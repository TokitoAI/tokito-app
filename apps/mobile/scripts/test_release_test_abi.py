"""Parser/linkage policy fixtures, not device acceptance evidence."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("abi", Path(__file__).with_name("release-test-abi.py"))
abi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(abi)


class ReleaseAbiTest(unittest.TestCase):
    def test_missing_and_merged_target_classes_are_exports(self):
        refs = {"La;", "Lkotlin/LazyKt;", "Landroid/app/Application;", "Ltest/Runner;"}
        mapping = "dev.tokito.Entry -> a:\nandroid.app.Application -> android.app.Application:\n"
        usage = "kotlin.LazyKt:\n    public static void unused()\n"
        self.assertEqual({"Ldev/tokito/Entry;", "Lkotlin/LazyKt;"},
                         abi.shared_exports(refs, {"Ltest/Runner;"}, mapping, usage))

    def test_arrays_resolve_their_element_boundary(self):
        self.assertEqual({"Ldev/tokito/Entry;"}, abi.shared_exports(
            {"[La;"}, set(), "dev.tokito.Entry -> a:\n", ""))

    def test_inherited_facade_method_is_resolved(self):
        method = ("Lkotlin/LazyKt;", "lazy", "Lkotlin/Lazy;", ("Lkotlin/jvm/functions/Function0;",))
        delegate = ("Lkotlin/LazyKt__LazyJVMKt;", *method[1:])
        self.assertTrue(abi.method_exists(method, {delegate}, {
            method[0]: [delegate[0]], delegate[0]: ["Ljava/lang/Object;"]}))

    def test_removed_or_reordered_method_is_not_hidden_by_object_parent(self):
        method = ("Ldev/tokito/Entry;", "open", "V", ("Ljava/lang/String;", "I"))
        reordered = (method[0], "open", "V", ("I", "Ljava/lang/String;"))
        self.assertFalse(abi.method_exists(method, {reordered}, {method[0]: ["Ljava/lang/Object;"]}))

    def test_constructor_cannot_be_inherited(self):
        method = ("Lchild;", "<init>", "V", ())
        self.assertFalse(abi.method_exists(method, {("Lparent;", "<init>", "V", ())},
                                          {"Lchild;": ["Lparent;"]}))

    def test_uleb_boundary(self):
        self.assertEqual((128, 2), abi.uleb(b"\x80\x01", 0))
        with self.assertRaises(ValueError):
            abi.uleb(b"\x80" * 6, 0)


if __name__ == "__main__":
    unittest.main()
