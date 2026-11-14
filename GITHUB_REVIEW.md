# GitHub反映の確認用案

## 対象

ユーザーが作成した専用リポジトリ: `phocorin121/voice-recorder-easy-audio`

接続先から取得できたリポジトリ一覧とローカルの作業フォルダーには、このアプリの既存プロジェクトは見当たりませんでした。既存の別アプリやandroid-app-templateには変更していません。

## 反映内容

- Kotlin / Jetpack Composeの録音アプリ一式。
- WAV/M4A録音、Media3再生、整理、編集、日本語/英語、テスト広告。
- Gradle Wrapper、単体テスト、Android統合テスト。
- GitHub Actionsによるdebug APK生成とArtifacts保存。
- README、検証報告、実機チェックリスト。

## 反映しないもの

ローカルのSDK設定、Gradleキャッシュ、生成APK、録音データ、署名鍵、エミュレーターのログ。

## Pull Request案

タイトル: `Add Easy Audio Android voice recorder`

本文:

端末内で音声を録音・整理・再生・共有するAndroidアプリを追加します。録音中は復旧可能なPCMデータを順次保存し、停止時にWAVまたはM4Aで確定します。画面は日本語/英語とダークモードに対応します。

波形、再生速度、フォルダー、メモ、ブックマーク、非破壊の無音スキップ、元データを保持できるトリミングを備えます。広告はGoogleのテストIDを使い、UMPの状態が広告リクエストを許可する場合のみ一覧に表示します。

検証結果はTEST_REPORT.mdを参照してください。実機での着信や長時間録音など、未確認の項目はDEVICE_CHECKLIST.mdに分けています。GitHub Actionsは単体テスト・Lint・APK生成を行い、debug APKをArtifactsに保存します。

## 現在の状態

ローカル成果物と検証結果を提示した後、ユーザーが対象リポジトリを作成して指定しました。この指示を受け、開発ブランチへの反映とPull Request作成を進めています。mainへのアプリ本体のマージは行いません。PRではAPK生成と、10分録音を含むAndroid統合テストを実行する構成です。

