"""Parser/linkage policy fixtures, not device acceptance evidence."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("abi", Path(__file__).with_name("release-test-abi.py"))
abi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(abi)


class ReleaseAbiTest(unittest.TestCase):
    def test_vendor_jni_renamed_private_field_and_class_are_rejected(self):
        mapping = "com.geniex.sdk.bean.LlmCreateInput -> com.geniex.sdk.bean.LlmCreateInput:\n    java.lang.String model_path -> b\ncom.geniex.sdk.bean.ModelConfig -> a:\n    int nCtx -> nCtx\n"
        self.assertEqual(2, len(abi.vendor_jni_mapping_errors(mapping)))

    def test_preserved_vendor_members_accept_line_mapping(self):
        mapping = "com.geniex.sdk.bean.ModelConfig -> com.geniex.sdk.bean.ModelConfig:\n    int nCtx -> nCtx\n    1:5:void <init>(int):4:4 -> <init>\n    1:2:int getNCtx():5:5 -> getNCtx\n    1:2:int com.geniex.sdk.bean.ModelConfig.hashCode():5:5 -> hashCode\n"
        self.assertEqual([], abi.vendor_jni_mapping_errors(mapping))

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

    def test_serializable_cannot_hide_optimized_regex_signature(self):
        requested = ("Lkotlin/text/Regex;", "g", "Ljava/util/List;", ("Ljava/lang/CharSequence;", "I"))
        rewritten = (requested[0], "g", "Ljava/util/List;", ("Ljava/lang/String;",))
        self.assertFalse(abi.method_exists(requested, {rewritten}, {
            requested[0]: ["Ljava/lang/Object;", "Ljava/io/Serializable;"]}))

    def test_uleb_boundary(self):
        self.assertEqual((128, 2), abi.uleb(b"\x80\x01", 0))
        with self.assertRaises(ValueError):
            abi.uleb(b"\x80" * 6, 0)

    def test_exact_qwen_reflection_methods_are_audited_when_suite_present(self):
        self.assertEqual(set(), abi.reflection_methods(set()))
        methods = abi.reflection_methods({abi.descriptor("dev.tokito.app.LocalQwenTokensDeviceTest")})
        self.assertEqual(5, len(methods))
        for method in methods:
            self.assertFalse(abi.method_exists(method, set(), {}))
            self.assertTrue(abi.method_exists(method, {method}, {}))


if __name__ == "__main__":
    unittest.main()
