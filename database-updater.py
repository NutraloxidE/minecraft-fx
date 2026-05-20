#!/usr/bin/env python3
"""
database-updater.py
===================
storage.json の executions データを H2 データベースに移行するスクリプト。

使い方:
    python database-updater.py [--storage <path>] [--db <path>] [--dry-run]

デフォルトパス:
    --storage : ./paper-server/plugins/GekiyabaFX/storage.json
    --db      : ./paper-server/plugins/GekiyabaFX/executions  (H2 ファイル DB のプレフィックス)

前提条件:
    pip install h2 jaydebeapi JPype1
    ※ Windows 環境では JPype1 のインストールに JDK が必要。
    ※ JVM が起動できない場合は --jdbc-jar オプションで h2-*.jar のパスを指定する。

注意:
    - Minecraft サーバーを停止した状態で実行すること（H2 ファイルの排他ロック競合を避けるため）。
    - 既に H2 に登録済みのレコードは (pair_id, ts, price, amount) の重複チェックで
      スキップされる（MERGE 相当の処理）。
"""

import argparse
import json
import os
import sys


# ─────────────────────────────────────────────────────────────────────────────
#  引数解析
# ─────────────────────────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(description="storage.json の約定履歴を H2 DB へ移行")
    p.add_argument(
        "--storage",
        default=os.path.join("paper-server", "plugins", "GekiyabaFX", "storage.json"),
        help="storage.json のパス（デフォルト: paper-server/plugins/GekiyabaFX/storage.json）",
    )
    p.add_argument(
        "--db",
        default=os.path.join("paper-server", "plugins", "GekiyabaFX", "executions"),
        help="H2 DB ファイルのプレフィックス（.mv.db は H2 が自動付与）",
    )
    p.add_argument(
        "--jdbc-jar",
        default=None,
        help="h2-*.jar の絶対パス（未指定時は Maven ローカルキャッシュから自動検索）",
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="INSERT を実行せずに件数だけ表示する",
    )
    return p.parse_args()


# ─────────────────────────────────────────────────────────────────────────────
#  H2 jar の自動検索
# ─────────────────────────────────────────────────────────────────────────────

def find_h2_jar(hint: str | None) -> str:
    if hint:
        return hint

    # Gradle キャッシュから探す
    import glob, pathlib
    home = pathlib.Path.home()
    patterns = [
        home / ".gradle" / "caches" / "modules-*" / "files-*" / "com.h2database" / "h2" / "*" / "*" / "h2-*.jar",
        home / ".m2" / "repository" / "com" / "h2database" / "h2" / "*" / "h2-*.jar",
    ]
    for pattern in patterns:
        matches = sorted(glob.glob(str(pattern)), reverse=True)
        if matches:
            print(f"[INFO] H2 jar を発見: {matches[0]}")
            return matches[0]

    sys.exit(
        "[ERROR] h2-*.jar が見つかりません。\n"
        "        --jdbc-jar オプションで h2-*.jar のパスを指定するか、\n"
        "        Gradle ビルドを実行してキャッシュを作成してください。"
    )


# ─────────────────────────────────────────────────────────────────────────────
#  メイン処理
# ─────────────────────────────────────────────────────────────────────────────

def main():
    args = parse_args()

    # ── storage.json を読み込む ──────────────────────────────────────────────
    storage_path = os.path.abspath(args.storage)
    if not os.path.exists(storage_path):
        sys.exit(f"[ERROR] storage.json が見つかりません: {storage_path}")

    with open(storage_path, encoding="utf-8") as f:
        storage = json.load(f)

    pairs = storage.get("pairs", {})
    if not pairs:
        print("[INFO] storage.json に pairs データがありません。移行するデータはありません。")
        return

    # 全ペアの約定件数を集計
    total_executions = 0
    pair_exec_map: dict[str, list[dict]] = {}
    for pair_id, pair_data in pairs.items():
        execs = pair_data.get("executions", [])
        if execs:
            pair_exec_map[pair_id] = execs
            total_executions += len(execs)

    if total_executions == 0:
        print("[INFO] storage.json の executions はすでに空（または存在しない）です。移行するデータはありません。")
        return

    print(f"[INFO] 移行対象: {len(pair_exec_map)} ペア / 合計 {total_executions} 件")
    for pid, execs in pair_exec_map.items():
        print(f"       {pid}: {len(execs)} 件")

    if args.dry_run:
        print("[DRY-RUN] INSERT は実行しません。")
        return

    # ── H2 に接続して INSERT ────────────────────────────────────────────────
    h2_jar = find_h2_jar(args.jdbc_jar)

    try:
        import jaydebeapi
    except ImportError:
        sys.exit(
            "[ERROR] jaydebeapi が見つかりません。\n"
            "        pip install jaydebeapi JPype1 を実行してください。"
        )

    db_path = os.path.abspath(args.db)
    url = f"jdbc:h2:file:{db_path};AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=TRUE"

    print(f"[INFO] H2 に接続します: {url}")
    conn = jaydebeapi.connect(
        "org.h2.Driver",
        url,
        ["sa", ""],
        h2_jar,
    )

    try:
        curs = conn.cursor()

        # テーブルが存在しない場合は作成
        curs.execute("""
            CREATE TABLE IF NOT EXISTS executions (
              id      BIGINT AUTO_INCREMENT PRIMARY KEY,
              pair_id VARCHAR(128) NOT NULL,
              ts      BIGINT       NOT NULL,
              price   VARCHAR(32)  NOT NULL,
              amount  VARCHAR(32)  NOT NULL
            )
        """)
        curs.execute("""
            CREATE INDEX IF NOT EXISTS idx_exec_pair_ts ON executions(pair_id, ts)
        """)
        conn.commit()

        # 既存レコードを (pair_id, ts, price, amount) のセットとして取得（重複スキップ用）
        curs.execute("SELECT pair_id, ts, price, amount FROM executions")
        existing = set()
        for row in curs.fetchall():
            existing.add((str(row[0]), int(row[1]), str(row[2]), str(row[3])))

        print(f"[INFO] H2 に既存レコード {len(existing)} 件があります（重複はスキップ）。")

        inserted = 0
        skipped  = 0

        for pair_id, execs in pair_exec_map.items():
            for ex in execs:
                ts     = int(ex.get("timestamp", 0))
                price  = str(ex.get("price", "0"))
                amount = str(ex.get("amount", "0"))
                # BigDecimal 文字列の正規化（小数点以下4桁）
                try:
                    from decimal import Decimal, ROUND_HALF_UP
                    price  = str(Decimal(price).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP))
                    amount = str(Decimal(amount).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP))
                except Exception:
                    pass

                key = (pair_id, ts, price, amount)
                if key in existing:
                    skipped += 1
                    continue

                curs.execute(
                    "INSERT INTO executions (pair_id, ts, price, amount) VALUES (?, ?, ?, ?)",
                    [pair_id, ts, price, amount],
                )
                existing.add(key)
                inserted += 1

        conn.commit()
        print(f"[INFO] 移行完了: INSERT {inserted} 件 / スキップ {skipped} 件")

    finally:
        conn.close()

    print()
    print("─" * 60)
    print("移行が完了しました。")
    print("storage.json の executions フィールドはそのまま残っていますが、")
    print("プラグインは今後 H2 のみを参照します。")
    print("不要になった場合は storage.json の executions フィールドを手動で削除できます。")
    print("─" * 60)


if __name__ == "__main__":
    main()
