import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

from build_model_bundle import MANIFEST, SIGNATURE, artifact_inventory, build


@unittest.skipUnless(shutil.which("openssl"), "openssl is required")
class ModelBundleBuilderTest(unittest.TestCase):
    def test_bundle_inventory_and_signature_match_runtime_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            (source / "dialogue").mkdir(parents=True)
            (source / "dialogue" / "model.gguf").write_bytes(b"verified model")
            key, public, bundle = root / "key.pem", root / "public.pem", root / "bundle.zip"
            subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", key], check=True)
            subprocess.run(["openssl", "ec", "-in", key, "-pubout", "-out", public], check=True, capture_output=True)
            build(argparse.Namespace(source=source, output=bundle, signing_key=key, capability="dialogue", model_id="tokito-test", model_revision="1", runtime_revision="1", abi=["arm64-v8a"], license="apache-2.0"))
            with zipfile.ZipFile(bundle) as archive:
                self.assertEqual({MANIFEST, SIGNATURE, "dialogue/model.gguf"}, set(archive.namelist()))
                manifest = archive.read(MANIFEST)
                self.assertEqual(artifact_inventory(source), json.loads(manifest)["artifacts"])
                manifest_path, signature_path = root / MANIFEST, root / SIGNATURE
                manifest_path.write_bytes(manifest)
                signature_path.write_bytes(archive.read(SIGNATURE))
            subprocess.run(["openssl", "dgst", "-sha256", "-verify", public, "-signature", signature_path, manifest_path], check=True, capture_output=True)

    def test_rejects_empty_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "empty.bin").touch()
            with self.assertRaisesRegex(ValueError, "empty artifacts"):
                artifact_inventory(root)


if __name__ == "__main__":
    unittest.main()
