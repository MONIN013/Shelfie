# Shelfie

「変わりたくない自分は、本棚に委託しろ」。実寸の本を1区画の棚に並べ、「みんなの棚」から次の一冊に出会うAndroidアプリです。

棚の大きさ・素材、背表紙・表紙・平積み、実測・書誌・ページ数による厚みを扱います。書籍検索、積読、お気に入りはアカウントなしで使え、棚の公開とクラウド保存にだけアカウントを使います。

## 保守方針

最新版だけを保守します。保存形式は **schema 4のみ**。後方互換とデータ移行は行いません。非対応のデータは読み込まず、必要ならアプリの初期化または開発用データの作り直しを行います。方針は [AGENTS.md](AGENTS.md) に記載しています。

## 構成

| 場所 | 役割 |
| --- | --- |
| `core` | 棚・書籍・実寸・配置制約・履歴・JSON |
| `renderer-api` | シーン、カメラ、選択判定、画像読み込み |
| `renderer-filament` | FilamentによるGPU描画と描画資源の管理 |
| `app` | Compose UI、操作、端末保存、APIクライアント |
| `assets` | 本・棚板・小物・配置マーカーのモデル |
| `server` | Node.js / TypeScript / SQLiteのAPI |
| `tools` | ビルド、検証、素材の再生成 |

## ビルドと検証

JDK 17、Android SDK Platform 37、Build Tools 36.0.0を用意し、SDK位置を`local.properties`の`sdk.dir`へ設定します。Gradle Wrapperと既存の依存指定を使用してください。

```sh
./gradlew :core:test :renderer-api:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python tools/assets/validate_assets.py
cd server
npm ci
npm run typecheck
npm test
```

サーバーはNode.js 24.14.0以上の24系、npm 11.9.0を使用します。npmとGradleの依存管理は独立しています。

WSL上のソースをWindows SDKでビルドする場合:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -JavaHome 'C:/path/to/jdk-17'
```

毎回空の専用ステージへコピーし、削除済みファイルがビルドへ混ざることを防ぎます。`-Stage`を指定する場合も空のディレクトリにしてください。APKとレポートは`artifacts/`へ出力します。ステージを直接編集しないでください。

端末テストは [ローカルAPIとの検証手順](docs/community.md#ローカル検証) に従い、検証用アプリIDかエミュレータで実行します。テストは端末内の棚を置換します。通常版は`net.monindev.shelfie.lab.filament`、検証用は`net.monindev.shelfie.validation.filament`です。

## 操作と仕様

画面は「本棚」「みんなの棚」「積読」の3タブです。新規インストールは空の棚から始まり、「＋」から書名・著者・ISBNの検索か手入力で本を追加します。

本をタップすると下のパネルで向き（背表紙・表紙・平積み）、移動、詳細、外すを選べます。移動は本をドラッグするか、置きたい場所をタップして「ここに置く」で確定します。二本指と右上のボタンで拡大・縮小します。棚に収まらない変更は拒否し、本や隣の配置を自動調整しません。棚がいっぱいなら、外す本を選んで入れ替えます。確定した変更は端末へ原子的に保存し、保存失敗時は再試行できます。

上部の「元に戻す」「やり直す」は現在の編集セッション内で動作し、外す・入れ替えの直後は通知からも元に戻せます。「棚の設定」で幅・高さと素材を変えます。「公開」から棚の公開とクラウド保存を行い、ここで初めてログインを求めます。

- [企画](docs/project_proposal.md)
- [物理モデル](docs/architecture.md)
- [アカウント・共有・検証](docs/community.md)
- [サーバー運用](server/README.md)
- [素材の再生成](assets/README.md)

`tools/check-idle.py --serial <端末ID>`は静止時とバックグラウンドの描画を調べます。`tools/measure-android.ps1 -Serial <端末ID>`はPerfetto・メモリ・温度を記録します。計測値の取得だけで消費電力や実機性能を断定しません。
