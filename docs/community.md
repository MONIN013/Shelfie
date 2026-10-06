# タイムラインとアカウント

共有の主な入口は「みんなの棚」のタイムライン。現行の1区画の棚とschema 4を使用する。

## 操作と公開範囲

- 「本を追加」から書名・著者・ISBNで検索、または手入力で登録する。幅・高さ・ページ数を確認して棚に置くか、積読に追加する。寸法不明時は四六判128×188mm、ページ数不明時は240ページを仮置きし、画面で説明する。
- 実在する本の書誌情報を棚JSONの`books`に保存する。棚から外しても書誌情報は残り、「本を追加」の「棚から外した本」から再追加できる。見本の書籍や見本の棚は同梱しない。
- Open Libraryの書影が取得できれば表示し、欠けた場合は端末で生成した書名・著者の文字表紙を使う。外部ホストは固定し、画像サイズとキャッシュ容量を制限する。
- 「みんなの棚」は未ログインでも閲覧できる。一覧の各棚はFilamentでオフスクリーン描画した実際の棚の画像で表示し、棚を開くと元の配置を立体表示する。棚名・書名で検索でき、公開・更新日時の新しい順。人気順や読了数ランキングは設けない。
- アカウントはユーザー名・表示名・12文字以上のパスワードで登録する。メール送信・再発行機能はない。パスワードの保管について登録時に案内する。
- アカウントが必要なのは棚の公開・クラウド保存・通報だけ。ログインは本棚の「公開」画面と通報時のシートで行い、ログインだけを求める画面は設けない。
- 「公開」画面の「保存」は本人だけのクラウド保存。「公開する」「公開中の棚を更新」で確認した時点の棚だけを公開する。編集中の自動保存は端末内にとどまり、公開棚は自動更新しない。
- 公開対象は棚に置いた本、小物、棚名、ひとこと、表示名。棚に置いていない登録本、積読、お気に入り、写真から切り出した画像は送出しない。非公開にすると「みんなの棚」と共有URLから外れ、端末のお気に入りと積読の「見つけた棚」からも次に開いた時点で外れる。
- 「積読に追加」とお気に入り（ハート）は端末に保存し、ログインは不要。積読は書誌と見つけた棚のIDだけを持ち、元の棚名・棚主・紹介文は保存しない。
- アカウントを切り替えても端末の棚を自動で置換しない。「端末に読み込む」には確認を挟み、直前の棚へUndoできる。別端末とのクラウド保存競合は409で停止し、再読み込みを促す。
- 各人の公開棚は1つ。公開更新は同じ棚を更新し、一覧を同一ユーザーの投稿で埋めない。フォロー、いいね数、コメント、DMはこの版の対象外。
- 通報はログイン後に理由を添えて送信できる。運営による対応はサーバーの管理手順で行う。

## 責務

| 場所 | 役割 |
| --- | --- |
| `core/.../ShelfModel.kt`, `ShelfEditor.kt` | カスタム書誌、配置制約、履歴 |
| `app/.../ServiceClient.kt` | HTTP、DTO、Keystoreによるセッション暗号化 |
| `app/.../DiscoverScreen.kt` | みんなの棚、棚の表示、お気に入り、積読への追加、通報 |
| `app/.../PublishScreen.kt` | ログイン・登録、公開確認、クラウド保存、アカウント削除 |
| `app/.../LibraryStore.kt` | 端末の積読とお気に入りの原子的保存 |
| `app/.../ShelfThumbnails.kt` | 一覧用のオフスクリーン描画と画像キャッシュ |
| `renderer-api/.../BookCovers.kt` | 上限付きの外部書影キャッシュ |
| `renderer-filament/.../GeneratedBookAsset.kt` | 既存形状と新しい表紙・背表紙を組み合わせたGLB |
| `server/src/model.ts` | 入力検証、公開対象抽出、サーバーでの配置検証 |
| `server/src/database.ts` | SQLiteスキーマ、外部キー、永続化 |
| `server/src/books.ts` | Open Library検索、応答キャッシュと呼び出し頻度制限 |
| `server/src/app.ts` | 認証・認可、アカウント、クラウド保存、公開、通報API |

サーバーはNode.js 24.14.0、標準HTTPとSQLiteを使い、本番npm依存を追加しない。TypeScriptとNode型定義だけを開発依存にする。SQLiteのAPIはNode 24ではexperimentalであり、Nodeのメジャーバージョンを固定する。

## ローカル検証

`server/`は独立したnpm管理。AndroidのGradleと混ぜない。

```sh
cd server
npm ci
npm run typecheck
npm test
DATABASE_PATH=../artifacts/community-test.sqlite npm start
```

Androidの通常ビルドは`https://monindev.net/shelfie`へ接続する。開発用はGradleに`-PshelfieApiUrl=http://127.0.0.1:8787 -PshelfieValidation=true`を指定し、`adb reverse tcp:8787 tcp:8787`を設定する。検証用はapplication IDが`net.monindev.shelfie.validation.filament`になり、通常版の棚・ログイン情報から隔離される。HTTPの例外はdebug版の127.0.0.1だけ。

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -Command '& ./tools/build.ps1 -Tasks ":core:test",":renderer-api:testDebugUnitTest",":app:lintDebug",":app:assembleDebug",":app:assembleDebugAndroidTest","-PshelfieApiUrl=http://127.0.0.1:8787","-PshelfieValidation=true" -JavaHome "C:/path/to/jdk-17"'
adb -s emulator-5554 reverse tcp:8787 tcp:8787
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/test-android.ps1 -Serial emulator-5554 -ApplicationId net.monindev.shelfie.validation.filament -OutputDirectory artifacts/community
```

`CommunityFlowTest`はローカルAPIに接続し、`ranchu`エミュレータまたは検証用application IDでだけ実行する。テスト用アカウントは終了時に削除する。ほかのAndroidテストも棚を置換するため、実機では必ず検証用application IDを使い、通常版を指定しない。

本番用APKは上記の`-PshelfieApiUrl=...`と`-PshelfieValidation=true`を両方除いて再ビルドする。端末には現在の形式のデータを用意する。インストールだけで検証完了とせず、起動・画面・クラッシュログ・サービス疎通を確認する。終了後は検証用APKとADB reverseを取り除く。

## 実装上の注意

- Android 16では古いEspressoの`InputManager.getInstance`呼び出しが失敗した。テスト依存をRunner 1.7.0 / JUnit拡張1.3.0 / Espresso 3.7.0へ更新し、実機で確認した。
- Androidの`JSONObject`はMIME値の`/`をエスケープするが、Filamentの画像デコーダー選択でそのまま解釈された。生成GLBのJSONでは不要なスラッシュエスケープを外す。統合テストは画像デコーダーのエラーログも検査し、文字表紙・背表紙の実機スクリーンショットを残す。
- 画像キャッシュは原子的な書き込みに加えて、同じ本を棚とリストから同時に要求した場合の排他を行う。

