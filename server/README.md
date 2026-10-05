# Shelfieサービス

Androidの「みんなの棚」、アカウント、書誌検索、非公開のクラウド保存、通報を提供する。積読とお気に入りは端末に保存するため、サーバーは扱わない。[仕様](../docs/community.md)を参照。

schema 4だけを受け付け、クライアントのバージョンによる応答変換は行わない。Androidとサーバーを同じ現行仕様で運用する。棚の本はすべて文書内の`books`で定義し、見本書籍は持たない。ISBN検索はopenBDの該当版、補完はOpen LibraryのISBN版。実測と書誌由来の厚みを区別して保持する（[厚みの自動入力](../docs/architecture.md#厚みの自動入力)）。

## nogikuへの配置

このサービスは既存サービスとは別のCompose project `shelfie`、localhostポート8787、専用volume `shelfie_shelfie-data`を使う。公開URLは`https://monindev.net/shelfie`。既存証明書を使うためDNSや証明書の追加は不要。

1. `server/`を`/home/monin/ShelfieService/`へ配置する。`node_modules/`、テスト用DB、認証情報は転送しない。
2. そのディレクトリで`docker compose build`を実行する。
3. 初回起動・更新の承認範囲を確認し、`docker compose up -d --wait`を実行する。`curl -f http://127.0.0.1:8787/health`で疎通を確認する。
4. `deploy/shelfie.nginx.conf`を確認後、操作者が`sudo python3 deploy/configure-nginx.py`を実行する。既存HTTPS serverへのinclude追加だけを行い、バックアップ・`nginx -t`成功後にreloadする。sudo認証情報をチャットへ貼らない。
5. 外部から`https://monindev.net/shelfie/health`と`/v1/feed`を確認する。

```sh
docker compose ps
docker compose logs --since=10m --tail=100 api
curl -fsS http://127.0.0.1:8787/health
```

ログにはリクエスト本文、パスワード、トークンを出さない。nginxのこのlocationのaccess_logは無効にして、検索語を共用ログに残さない。TLS終端はnginxに限定し、APIポートは外部へ直接公開しない。`TRUST_PROXY=1`はnginxが`X-Real-IP`を上書きする構成でのみ使用する。

## 保存・バックアップ

SQLiteはWALと外部キーを有効化する。アカウント削除時はセッション・クラウド棚・公開棚・通報を外部キーで削除する。セッションは30日で失効し、各アカウント最大5件。パスワードはランダムsalt付きscrypt、トークンはSHA-256のみを保存する。

稼働中DBを通常の`cp`だけでコピーしない。NodeのSQLite `backup`で整合したバックアップを作る。以下は専用volume内に日時付きバックアップを作る例。バックアップにも個人情報とパスワードハッシュが含まれるため、管理者だけがアクセスできる場所へ保管する。

```sh
docker compose exec api node --input-type=module -e 'import {DatabaseSync,backup} from "node:sqlite";const db=new DatabaseSync("/data/shelfie.sqlite");await backup(db,"/data/backup-"+Date.now()+".sqlite");db.close();'
```

コードを更新する際はこのバックアップを先に取る。`docker compose down -v`はデータを削除するため実行しない。異なる保存形式への移行・ロールバックは保証しない。現行仕様と合わない開発データは作り直す。本番データの削除は別途承認を得る。今回のDBスキーマは`user_version=1`。積読・お気に入りの表（`reading`、`favorites`）は作成しない。以前の版で作成済みのDBに残る2表は使われないため、承認を得てから削除する。

## 通報への対応

通報は`reports`テーブルに保存し、公開APIへは出さない。管理者は`id, post_id, reason, created_at`だけを取得して内容を確認する。ユーザー名や認証ハッシュを調査ログに含めない。対応で棚を非公開にする場合は、対象を確認してから`posts`の該当IDだけを削除する。自動BANや通報数による自動削除は行わない。管理画面・メール通知はこの版には含めない。

## 制約

単一インスタンス・小規模試用向け。メールでの本人確認とパスワード再発行、分散構成、プッシュ通知、DM、フォローは未実装。登録画面でパスワードを保管するよう案内する。Open Libraryの書誌・書影は欠ける場合があり、日本語検索も完全ではない。手入力と文字表紙を併用する。
