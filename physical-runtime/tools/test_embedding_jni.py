"""Host-only packaging contract checks; native builds/device tests are separate."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("embedding_jni", Path(__file__).with_name("prepare_embedding_jni.py"))
jni = importlib.util.module_from_spec(spec)
spec.loader.exec_module(jni)


class EmbeddingJniTests(unittest.TestCase):
    def test_sparse_source_selection(self):
        for path in ("java/src/main/native/ai_onnxruntime_OnnxRuntime.c",
                     "java/src/main/java/ai/onnxruntime/OnnxRuntime.java",
                     "include/onnxruntime/core/session/onnxruntime_c_api.h", "LICENSE"):
            self.assertTrue(jni.selected_source(path))
        for path in ("onnxruntime/test/test.cc", "java/src/test/java/Test.java", "model.onnx"):
            self.assertFalse(jni.selected_source(path))

    def test_bridge_normalization_does_not_hide_native_signatures(self):
        value = """  static int access$000();
    descriptor: ()I
  static native int access$001();
    descriptor: ()I
  public native long create(long);
    descriptor: (J)J
"""
        normalized = jni.canonical_descriptors(value)
        self.assertNotIn("access$000", normalized)
        self.assertIn("native int access$001", normalized)
        self.assertIn("descriptor: (J)J", normalized)

    def test_fixed_source_and_all_supported_abis(self):
        self.assertEqual(len(jni.COMMIT), 40)
        self.assertEqual(set(jni.TARGETS), {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"})
        self.assertTrue(all(target.endswith("27") for target in jni.TARGETS.values()))

    @staticmethod
    def elf(version="1.27.1", alignment="0x4000", extra=""):
        return "\n".join([
            "NEEDED Shared library: [libonnxruntime.so]",
            "Java_ai_onnxruntime_Test_call JNI_OnLoad",
            f"OrtGetApiBase@VERS_{version}",
            f"OrtSessionOptionsAppendExecutionProvider_CPU@VERS_{version}",
            f"OrtSessionOptionsAppendExecutionProvider_Nnapi@VERS_{version}",
            f"LOAD 0x0 0x0 0x0 0x100 0x100 R E {alignment}", extra,
        ])

    def test_matching_linkage_passes(self):
        jni.check_linkage(self.elf(), {"Java_ai_onnxruntime_Test_call", "JNI_OnLoad"})

    def test_old_version_is_rejected(self):
        with self.assertRaises(ValueError):
            jni.check_linkage(self.elf("1.27.0"), {"Java_ai_onnxruntime_Test_call", "JNI_OnLoad"})

    def test_small_pages_are_rejected(self):
        with self.assertRaises(ValueError):
            jni.check_linkage(self.elf(alignment="0x1000"), {"Java_ai_onnxruntime_Test_call", "JNI_OnLoad"})

    def test_changed_exports_are_rejected(self):
        with self.assertRaises(ValueError):
            jni.check_linkage(self.elf(extra="Java_ai_onnxruntime_Test_unexpected"), {"Java_ai_onnxruntime_Test_call", "JNI_OnLoad"})


if __name__ == "__main__":
    unittest.main()
