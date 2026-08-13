"""
人数当てゲーム - ルーム保持期限・自動削除 統合テスト（Issue #34）
Firebase Emulator (Firestore) を使って、backend/functions/main.py の
_sweep_expired_rooms()（delete_expired_rooms のコア処理）をローカルで検証する。

実行方法:
  1. エミュレーター起動（別ターミナル）:
     cd backend && firebase emulators:start --only firestore
  2. このスクリプト実行:
     cd backend && FIRESTORE_EMULATOR_HOST=localhost:8080 python3 test_room_expiry.py

  もしくは一括実行:
     cd backend && firebase emulators:exec --only firestore \
       "FIRESTORE_EMULATOR_HOST=localhost:8080 python3 test_room_expiry.py"
"""

import os
import sys
import uuid
from datetime import datetime, timedelta, timezone

# Firestore エミュレーター向け設定。main.py の initialize_app() が実行される前に
# 設定しておく必要がある（GOOGLE_CLOUD_PROJECT がないと initialize_app() が
# プロジェクトIDを解決できず失敗する）。
os.environ.setdefault("FIRESTORE_EMULATOR_HOST", "localhost:8080")
os.environ.setdefault("GOOGLE_CLOUD_PROJECT", "hitokazu-game")

# main.py（および main.py が読み込む firebase_admin / firebase_functions / questions.py）
# を解決できるようにする。
sys.path.insert(0, "functions/venv/lib/python3.12/site-packages")
sys.path.insert(0, "functions")

import main  # noqa: E402  (sys.path 設定後に import する必要があるため)
from firebase_admin import firestore  # noqa: E402

db = firestore.client()

PASS = "✅"
FAIL = "❌"
errors: list[str] = []


def check(label: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"  {PASS} {label}" + (f"  ({detail})" if detail else ""))
    else:
        msg = f"  {FAIL} {label}" + (f"  ({detail})" if detail else "")
        print(msg)
        errors.append(msg)


def make_room_with_subcollections(room_id: str, expire_at, created_at) -> None:
    """rooms/{room_id} と players（2件）・rounds/1_1/answers（2件）を作る。
    実際のルーム（FINISHED・players・rounds/*/answers）を模して、削除時に
    サブコレクションが孤児として残らないことを検証できる形にする。"""
    room_ref = db.collection("rooms").document(room_id)
    data = {
        "hostName": "テストホスト",
        "status": "FINISHED",
        "createdAt": created_at,
    }
    if expire_at is not None:
        data["expireAt"] = expire_at
    room_ref.set(data)

    for pid, name in [("p1", "アリス"), ("p2", "ボブ")]:
        room_ref.collection("players").document(pid).set({
            "nickname": name,
            "isHost": pid == "p1",
            "joinedAt": created_at,
        })

    round_ref = room_ref.collection("rounds").document("1_1")
    round_ref.set({"placeholder": True})
    for pid in ["p1", "p2"]:
        round_ref.collection("answers").document(pid).set({
            "answer": "はい",
            "answeredAt": created_at,
        })


def count_all_docs(room_id: str) -> dict:
    room_ref = db.collection("rooms").document(room_id)
    return {
        "room": 1 if room_ref.get().exists else 0,
        "players": len(room_ref.collection("players").get()),
        "rounds": len(room_ref.collection("rounds").get()),
        "answers": len(room_ref.collection("rounds").document("1_1").collection("answers").get()),
    }


def cleanup(room_id: str) -> None:
    """テスト失敗時に孤児データが残らないようにする（削除できていればどのみち no-op）。"""
    room_ref = db.collection("rooms").document(room_id)
    for r in room_ref.collection("rounds").get():
        for a in r.reference.collection("answers").get():
            a.reference.delete()
        r.reference.delete()
    for p in room_ref.collection("players").get():
        p.reference.delete()
    room_ref.delete()


# ─────────────────────────────────────────────────────────────
print("=" * 55)
print("ルーム保持期限・自動削除 統合テスト (Firestore Emulator)")
print("=" * 55)

now = datetime.now(timezone.utc)
room_ids: list[str] = []

# ── TEST 1: expireAt を過ぎたルームはサブコレクションごと削除される ──
print("\n[TEST 1] 期限切れルームの再帰削除（孤児サブコレクションが残らないこと）")
expired_room_id = "TEST_EXPIRED_" + str(uuid.uuid4())[:6].upper()
room_ids.append(expired_room_id)
make_room_with_subcollections(
    expired_room_id,
    expire_at=now - timedelta(hours=1),
    created_at=now - timedelta(hours=30),
)
before = count_all_docs(expired_room_id)
check("削除前: players 2件", before["players"] == 2)
check("削除前: rounds 1件", before["rounds"] == 1)
check("削除前: answers 2件", before["answers"] == 2)

deleted_1 = main._sweep_expired_rooms(db)

after = count_all_docs(expired_room_id)
check("削除後: room ドキュメントが消えている", after["room"] == 0)
check("削除後: players が0件（孤児が残っていない）", after["players"] == 0)
check("削除後: rounds が0件（孤児が残っていない）", after["rounds"] == 0)
check("削除後: rounds/*/answers が0件（孤児が残っていない）", after["answers"] == 0)
check("sweep が1件以上削除したと報告する", deleted_1 >= 1, f"deleted={deleted_1}")

# ── TEST 2: 期限内のルームは削除されない ──
print("\n[TEST 2] 期限内ルームは削除されない")
active_room_id = "TEST_ACTIVE_" + str(uuid.uuid4())[:6].upper()
room_ids.append(active_room_id)
make_room_with_subcollections(
    active_room_id,
    expire_at=now + timedelta(hours=5),
    created_at=now - timedelta(hours=1),
)
main._sweep_expired_rooms(db)
after_active = count_all_docs(active_room_id)
check("期限内ルームの room ドキュメントは残る", after_active["room"] == 1)
check("期限内ルームの players も残る", after_active["players"] == 2)
check("期限内ルームの answers も残る", after_active["answers"] == 2)

# ── TEST 3: expireAt が無い旧ルーム（本Issue導入前）のフォールバック ──
print("\n[TEST 3] expireAt 未設定の旧ルーム（レガシーフォールバック）")
legacy_old_id = "TEST_LEGACY_OLD_" + str(uuid.uuid4())[:6].upper()
room_ids.append(legacy_old_id)
make_room_with_subcollections(
    legacy_old_id,
    expire_at=None,
    created_at=now - timedelta(hours=25),
)
legacy_new_id = "TEST_LEGACY_NEW_" + str(uuid.uuid4())[:6].upper()
room_ids.append(legacy_new_id)
make_room_with_subcollections(
    legacy_new_id,
    expire_at=None,
    created_at=now - timedelta(hours=1),
)
main._sweep_expired_rooms(db)
check(
    "createdAt から24時間超のexpireAt未設定ルームは削除される",
    count_all_docs(legacy_old_id)["room"] == 0,
)
check(
    "createdAt から24時間以内のexpireAt未設定ルームは残る",
    count_all_docs(legacy_new_id)["room"] == 1,
)

# ── クリーンアップ（残存分があれば削除。TEST 2 のルームは意図的に残っている）──
print("\n[CLEANUP] テストデータ削除...")
for rid in room_ids:
    cleanup(rid)
print(f"  {PASS} クリーンアップ完了")

# ─── 結果サマリー ──────────────────────────────────────────────
print("\n" + "=" * 55)
if errors:
    print(f"⚠️  {len(errors)}件の失敗:")
    for e in errors:
        print(e)
    sys.exit(1)
else:
    print("✅ 全テスト合格！ルーム保持期限・自動削除は正常に動作しています")
print("=" * 55)
