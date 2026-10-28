"""Model quality gates, shared by the test suite and the Docker build.

The Docker image trains its own model; running these gates right after training means the
model that actually ships is checked, not just the smaller one trained in the tests.
Run: python -m fraud_service.gates <model_dir>   (exits non-zero on failure)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def check(metadata: dict) -> list[str]:
    """Return a list of failed gates (empty means the model passes)."""
    m = metadata["metrics"]
    review, block, rule = m["at_review_threshold"], m["at_block_threshold"], m["baseline_rule"]
    failures = []

    def gate(ok: bool, message: str) -> None:
        if not ok:
            failures.append(message)

    gate(m["roc_auc"] >= 0.97, f"ROC AUC {m['roc_auc']} < 0.97")
    gate(m["pr_auc"] >= 0.80, f"PR AUC {m['pr_auc']} < 0.80")
    gate(review["false_positive_rate"] <= 0.015, f"REVIEW false positive rate {review['false_positive_rate']} > 1.5%")
    gate(block["precision"] >= 0.85, f"BLOCK precision {block['precision']} < 85%")
    gate(block["false_positive_rate"] <= 0.003, f"BLOCK false positive rate {block['false_positive_rate']} > 0.3%")
    gate(review["recall"] > rule["recall"] + 0.3,
         f"REVIEW recall {review['recall']} does not beat the hand-written rule ({rule['recall']}) by 30 points")
    for pattern, stats in m["by_pattern"].items():
        gate(stats["recall_at_review"] >= 0.8, f"{pattern} recall at REVIEW {stats['recall_at_review']} < 80%")
    gate(metadata["thresholds"]["block"] >= metadata["thresholds"]["review"], "BLOCK threshold below REVIEW threshold")
    return failures


if __name__ == "__main__":
    meta = json.loads((Path(sys.argv[1]) / "metadata.json").read_text())
    failed = check(meta)
    for f in failed:
        print("QUALITY GATE FAILED:", f)
    if failed:
        sys.exit(1)
    print(f"All quality gates passed for {meta['model_version']}")
