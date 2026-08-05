#!/usr/bin/env python3
"""
質問マスタ生成スクリプト

正本 `shared/questions.json` から、Kotlin（Android）/ JavaScript（Web）/
Python（Cloud Functions）の各実装を生成する。

Issue #16: 質問マスタをKotlin/JS/Pythonの3箇所重複から単一正本に統合し、
各実装へ同期する仕組みを導入する。

使い方:
    python3 scripts/generate_questions.py            # 3ファイルを再生成して上書きする
    python3 scripts/generate_questions.py --check     # 再生成した内容が現在のファイルと
                                                        # 一致するか検証する（差分があれば非0終了・上書きしない）

質問を追加・変更する場合は shared/questions.json を編集してから、このスクリプトを
実行して3実装に反映すること。生成対象のファイルを直接編集しても、次回の
再生成で上書きされるため意味がない。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MASTER_PATH = REPO_ROOT / "shared" / "questions.json"

KOTLIN_TARGET = (
    REPO_ROOT
    / "android/app/src/main/java/com/rokusoudo/hitokazu/data/questions/Questions.kt"
)
PYTHON_TARGET = REPO_ROOT / "backend/functions/questions.py"
WEB_TARGET = REPO_ROOT / "backend/web/index.html"

REQUIRED_FIELDS = (
    "questionId",
    "text",
    "options",
    "answerSeconds",
    "predictSeconds",
    "tags",
)

CATEGORY_LABELS = {
    "friend": "friend: アイスブレーキング",
    "party": "party: パーティー向け",
    "deep": "deep: 仲が良い関係性向け・成人向け",
}

WEB_START_MARKER = (
    "  // GENERATED:QUESTIONS:START "
    "(edit shared/questions.json, then run `python3 scripts/generate_questions.py`)"
)
WEB_END_MARKER = "  // GENERATED:QUESTIONS:END"


def load_master() -> list[dict]:
    if not MASTER_PATH.exists():
        raise FileNotFoundError(f"master file not found: {MASTER_PATH}")
    data = json.loads(MASTER_PATH.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError("shared/questions.json must be a JSON array")
    seen_ids: set[str] = set()
    for i, q in enumerate(data):
        for key in REQUIRED_FIELDS:
            if key not in q:
                raise ValueError(f"questions[{i}] is missing required field: {key}")
        qid = q["questionId"]
        if qid in seen_ids:
            raise ValueError(f"duplicate questionId: {qid}")
        seen_ids.add(qid)
    return data


def _group_by_category(questions: list[dict]) -> list[tuple[str, list[dict]]]:
    """先頭タグが同じ質問が連続している区間ごとにグルーピングする（コメント見出し用）。"""
    groups: list[tuple[str, list[dict]]] = []
    for q in questions:
        cat = q["tags"][0] if q["tags"] else "other"
        if groups and groups[-1][0] == cat:
            groups[-1][1].append(q)
        else:
            groups.append((cat, [q]))
    return groups


def _kt_str(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _js_str(s: str) -> str:
    return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"


def _py_str(s: str) -> str:
    return json.dumps(s, ensure_ascii=False)


def render_kotlin(questions: list[dict]) -> str:
    lines: list[str] = []
    lines.append("package com.rokusoudo.hitokazu.data.questions")
    lines.append("")
    lines.append("import com.rokusoudo.hitokazu.data.model.Question")
    lines.append("")
    lines.append("// ⚠️ このファイルは自動生成されています。直接編集しないこと。")
    lines.append("// 正本: shared/questions.json")
    lines.append("// 再生成: python3 scripts/generate_questions.py")
    lines.append("// (Issue #16: 質問マスタの単一正本化)")
    lines.append("")
    lines.append("val QUESTIONS: List<Question> = listOf(")
    for cat, items in _group_by_category(questions):
        label = CATEGORY_LABELS.get(cat, cat)
        lines.append(f"    // ── {label} " + "─" * 24)
        for q in items:
            opts = ", ".join(_kt_str(o) for o in q["options"])
            tags = ", ".join(_kt_str(t) for t in q["tags"])
            lines.append(
                f'    Question({_kt_str(q["questionId"])}, {_kt_str(q["text"])}, '
                f"listOf({opts}), {q['answerSeconds']}, {q['predictSeconds']}, "
                f"listOf({tags})),"
            )
    lines.append(")")
    lines.append("")
    return "\n".join(lines)


def render_python(questions: list[dict]) -> str:
    lines: list[str] = []
    lines.append('"""')
    lines.append("質問マスタ（自動生成）")
    lines.append("")
    lines.append("このファイルは自動生成されています。直接編集しないこと。")
    lines.append("正本: shared/questions.json")
    lines.append("再生成: python3 scripts/generate_questions.py")
    lines.append("(Issue #16: 質問マスタの単一正本化)")
    lines.append('"""')
    lines.append("")
    lines.append("QUESTIONS = [")
    for cat, items in _group_by_category(questions):
        label = CATEGORY_LABELS.get(cat, cat)
        lines.append(f"    # ── {label} " + "─" * 24)
        for q in items:
            lines.append("    {")
            lines.append(f'        "questionId": {_py_str(q["questionId"])},')
            lines.append(f'        "text": {_py_str(q["text"])},')
            opts = ", ".join(_py_str(o) for o in q["options"])
            lines.append(f'        "options": [{opts}],')
            lines.append(f'        "answerSeconds": {q["answerSeconds"]},')
            lines.append(f'        "predictSeconds": {q["predictSeconds"]},')
            tags = ", ".join(_py_str(t) for t in q["tags"])
            lines.append(f'        "tags": [{tags}],')
            lines.append("    },")
    lines.append("]")
    lines.append("")
    return "\n".join(lines)


def render_js_block(questions: list[dict]) -> str:
    lines: list[str] = []
    lines.append(WEB_START_MARKER)
    lines.append("  const ALL_QUESTIONS = [")
    for cat, items in _group_by_category(questions):
        label = CATEGORY_LABELS.get(cat, cat)
        lines.append(f"    // {label}")
        for q in items:
            opts = ",".join(_js_str(o) for o in q["options"])
            tags = ",".join(_js_str(t) for t in q["tags"])
            lines.append(
                f"    {{ questionId:{_js_str(q['questionId'])}, "
                f"text:{_js_str(q['text'])}, options:[{opts}], "
                f"answerSeconds:{q['answerSeconds']}, predictSeconds:{q['predictSeconds']}, "
                f"tags:[{tags}] }},"
            )
    lines.append("  ];")
    lines.append(WEB_END_MARKER)
    return "\n".join(lines)


def build_web_content(questions: list[dict]) -> str:
    if not WEB_TARGET.exists():
        raise FileNotFoundError(f"web target not found: {WEB_TARGET}")
    content = WEB_TARGET.read_text(encoding="utf-8")
    pattern = re.compile(
        re.escape(WEB_START_MARKER) + r".*?" + re.escape(WEB_END_MARKER),
        re.DOTALL,
    )
    if not pattern.search(content):
        raise ValueError(
            "GENERATED:QUESTIONS markers not found in backend/web/index.html. "
            "Cannot safely regenerate the question list."
        )
    return pattern.sub(lambda _m: render_js_block(questions), content, count=1)


def build_targets(questions: list[dict]) -> dict[Path, str]:
    return {
        KOTLIN_TARGET: render_kotlin(questions),
        PYTHON_TARGET: render_python(questions),
        WEB_TARGET: build_web_content(questions),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="ファイルを書き換えず、現在の内容が正本と一致しているか検証する",
    )
    args = parser.parse_args()

    questions = load_master()
    if len(questions) != 36:
        print(
            f"warning: shared/questions.json has {len(questions)} questions (expected 36)",
            file=sys.stderr,
        )

    targets = build_targets(questions)

    if args.check:
        mismatched = []
        for path, expected in targets.items():
            current = path.read_text(encoding="utf-8") if path.exists() else None
            if current != expected:
                mismatched.append(path)
        if mismatched:
            print("次のファイルが shared/questions.json と同期していません:", file=sys.stderr)
            for path in mismatched:
                print(f"  - {path.relative_to(REPO_ROOT)}", file=sys.stderr)
            print(
                "\n`python3 scripts/generate_questions.py` を実行して再生成してください。",
                file=sys.stderr,
            )
            return 1
        print(f"OK: {len(questions)}問すべて同期済みです。")
        return 0

    for path, content in targets.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        print(f"wrote {path.relative_to(REPO_ROOT)}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
