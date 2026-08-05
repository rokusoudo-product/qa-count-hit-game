"""
人数当てゲーム - 質問マスタ同期テスト（Issue #16）

shared/questions.json（正本）と、そこから生成される Kotlin / JavaScript(Web) /
Python(Cloud Functions) の3実装が一致していることを検証する。
Firestoreへの接続なしで完結する（test_logic.py と同様）。

実行方法:
  python3 backend/test_questions_sync.py
"""
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "scripts"))
sys.path.insert(0, str(REPO_ROOT / "backend" / "functions"))

import generate_questions as gen  # noqa: E402

errors: list[str] = []


def check(label: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"  ✅ {label}" + (f"  ({detail})" if detail else ""))
    else:
        msg = f"  ❌ {label}" + (f"  ({detail})" if detail else "")
        print(msg)
        errors.append(msg)


EXPECTED_COUNT = 36
KNOWN_TAGS = {"friend", "party", "deep"}


def normalize(q: dict) -> tuple:
    return (
        q["questionId"],
        q["text"],
        tuple(q["options"]),
        q["answerSeconds"],
        q["predictSeconds"],
        tuple(q["tags"]),
    )


def extract_kotlin_questions(text: str) -> list[dict]:
    pattern = re.compile(
        r'Question\("(?P<id>[^"]+)",\s*"(?P<text>[^"]*)",\s*'
        r"listOf\((?P<opts>[^)]*)\),\s*"
        r"(?P<answer_s>\d+),\s*(?P<predict_s>\d+),\s*"
        r"listOf\((?P<tags>[^)]*)\)\),"
    )
    results = []
    for m in pattern.finditer(text):
        results.append(
            {
                "questionId": m.group("id"),
                "text": m.group("text"),
                "options": [o.strip().strip('"') for o in m.group("opts").split(",")],
                "answerSeconds": int(m.group("answer_s")),
                "predictSeconds": int(m.group("predict_s")),
                "tags": [t.strip().strip('"') for t in m.group("tags").split(",")],
            }
        )
    return results


def extract_js_questions(text: str) -> list[dict]:
    block_match = re.search(
        re.escape(gen.WEB_START_MARKER) + r"(.*?)" + re.escape(gen.WEB_END_MARKER),
        text,
        re.DOTALL,
    )
    assert block_match, "GENERATED:QUESTIONS block not found in index.html"
    block = block_match.group(1)
    pattern = re.compile(
        r"questionId:'(?P<id>[^']*)',\s*text:'(?P<text>[^']*)',\s*"
        r"options:\[(?P<opts>[^\]]*)\],\s*"
        r"answerSeconds:(?P<answer_s>\d+),\s*predictSeconds:(?P<predict_s>\d+),\s*"
        r"tags:\[(?P<tags>[^\]]*)\]"
    )
    results = []
    for m in pattern.finditer(block):
        results.append(
            {
                "questionId": m.group("id"),
                "text": m.group("text"),
                "options": [o.strip().strip("'") for o in m.group("opts").split(",")],
                "answerSeconds": int(m.group("answer_s")),
                "predictSeconds": int(m.group("predict_s")),
                "tags": [t.strip().strip("'") for t in m.group("tags").split(",")],
            }
        )
    return results


print("=" * 55)
print("人数当てゲーム 質問マスタ同期テスト")
print("=" * 55)

# ─── 正本の妥当性 ───────────────────────────────────────────
master = gen.load_master()
check(f"正本の質問数は{EXPECTED_COUNT}問", len(master) == EXPECTED_COUNT, f"実際={len(master)}")
check(
    "全質問のタグが既知の値（friend/party/deep）",
    all(set(q["tags"]) <= KNOWN_TAGS and q["tags"] for q in master),
)
ids = [q["questionId"] for q in master]
check("questionId に重複がない", len(ids) == len(set(ids)))

master_set = {normalize(q) for q in master}

# ─── 生成コマンドが正本と一致した出力を作れるか ─────────────
result = subprocess.run(
    [sys.executable, str(REPO_ROOT / "scripts" / "generate_questions.py"), "--check"],
    cwd=REPO_ROOT,
    capture_output=True,
    text=True,
)
check(
    "generate_questions.py --check が3ファイルとも同期済みと判定する",
    result.returncode == 0,
    result.stdout.strip() or result.stderr.strip(),
)

# ─── Kotlin 実装が正本と一致するか（独自の正規表現で抽出して照合） ───
kt_text = gen.KOTLIN_TARGET.read_text(encoding="utf-8")
kt_questions = extract_kotlin_questions(kt_text)
check(f"Kotlin から{EXPECTED_COUNT}問を抽出できた", len(kt_questions) == EXPECTED_COUNT, f"実際={len(kt_questions)}")
kt_set = {normalize(q) for q in kt_questions}
check("Kotlin の質問内容が正本と一致する", kt_set == master_set)

# ─── Web(JS) 実装が正本と一致するか ─────────────────────────
web_text = gen.WEB_TARGET.read_text(encoding="utf-8")
js_questions = extract_js_questions(web_text)
check(f"Web(JS) から{EXPECTED_COUNT}問を抽出できた", len(js_questions) == EXPECTED_COUNT, f"実際={len(js_questions)}")
js_set = {normalize(q) for q in js_questions}
check("Web(JS) の質問内容が正本と一致する", js_set == master_set)

# ─── Python(Cloud Functions) 実装が正本と一致するか ──────────
import questions as py_questions_module  # noqa: E402

check(
    f"Python(functions) から{EXPECTED_COUNT}問を抽出できた",
    len(py_questions_module.QUESTIONS) == EXPECTED_COUNT,
    f"実際={len(py_questions_module.QUESTIONS)}",
)
py_set = {normalize(q) for q in py_questions_module.QUESTIONS}
check("Python(functions) の質問内容が正本と一致する", py_set == master_set)

# ─── 結果サマリー ─────────────────────────────────────────────
print("\n" + "=" * 55)
if errors:
    print(f"⚠️  {len(errors)}件の失敗:")
    for e in errors:
        print(e)
    sys.exit(1)
else:
    print(f"✅ 全テスト合格！質問マスタ（{EXPECTED_COUNT}問）は3実装すべてで同期しています")
print("=" * 55)
