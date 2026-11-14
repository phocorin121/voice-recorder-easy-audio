# Voice Recorder – Easy Audio / かんたんボイスレコーダー

Kotlin / Jetpack Compose / Material 3 のAndroid録音アプリ。minSdk 26、compileSdk / targetSdk 35、Java 17。
別アプリから独立したプロジェクトです。Android StudioのGUIは必要ありません。

## ビルド

Android SDK Platform 35・Build Tools・Java 17を用意します。SDKの場所は `ANDROID_HOME` または、Git管理外の `local.properties` の `sdk.dir` に設定します。

Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Linux / GitHub Actions:

```sh
chmod +x gradlew
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

APK出力先: `app/build/outputs/apk/debug/app-debug.apk`

Windows上で日本語パスによるGradleワーカーの起動エラーが出る場合は、英数字パスの作業用リンクとGRADLE_USER_HOMEを使用します。ビルド構成はワーカー1つ、Kotlinコンパイラを同一プロセスで実行し、メモリ使用量を抑えています。

## 実装構成

- `RecordingService.kt`: 明示的なマイクForeground Service。通知の停止/一時停止、ロック中の継続、音声フォーカス喪失とAndroid 10以降のマイク抑制通知への対応。
- `Pcm.kt`: モノラル44.1 kHz・16ビットPCM WAV、RIFFヘッダー修復、サンプル単位のトリミング、波形の音量ピーク。
- `AudioCodec.kt`: Android MediaCodecのAACエンコード/デコード。M4A編集はPCMへ展開して選択範囲をAACへ再エンコード。WAV編集はPCMを直接コピーします。
- `Repository.kt`: アプリ専用の端末内保存、AtomicFileで一覧を原子的に更新。録音ごとにUUIDを使い、名前変更で音声ファイルを移動しません。
- `PlaybackService.kt`: Media3 ExoPlayerとMediaSessionService。バックグラウンド再生、音声フォーカス、イヤホン取り外し時の一時停止、非破壊の無音スキップ。
- `MainActivity.kt`: 一覧/お気に入り/フォルダー、名前とメモ検索、並べ替え、複数選択、共有、編集、設定、ローカライズ。
- `res/values*/strings.xml`: 英語/日本語の画面・通知・エラー・ダイアログ。
- `.github/workflows/android.yml`: 単体テスト・Lint・debug APKビルド、APKと検査レポートをArtifactsとして保存。

## 録音データの保護

録音中は `.wav.part` へPCMを順次書き出し、約1秒ごとにWAVヘッダーとファイルを同期します。停止後はまずWAVを確保し、M4A選択時にAACへ変換します。変換失敗時にはWAVを保持します。途中ファイルと一覧に登録されていない完成ファイルを次回起動時に回収します。

Androidによる強制終了や電源断の際、最後の未同期部分を完全に保証するものではありません。Androidの「強制停止」で録音を継続する仕組みや、自動で隠れて録音を再開する仕組みはありません。ユーザーが再度開いた際に残されたファイルを復旧します。

本アプリの削除・データ消去では録音も失われます。通常のアプリ更新ではアプリ専用保存領域と一覧を維持します。同じapplicationId・同じ署名鍵を使って更新してください。大切な音声は共有メニューからバックアップしてください。

一時領域を含め、M4A変換時やトリミング時には出力より多い空き容量が必要です。残り容量が少なくなると録音を止め、確保済み音声を保存します。WAVはRIFFのサイズ上限前に自動停止します。

## 広告・プライバシー

Googleのデモ用AdMobアプリIDとバナーIDだけを使用しています。UMPで同意情報を更新し、広告をリクエスト可能な場合だけ広告SDKを初期化します。同意情報取得の失敗時は広告なしで動作します。バナーは一覧画面のみで表示し、録音中・編集画面には配置しません。

録音・メモを自動アップロードする実装はありません。広告SDKは通信します。共有はFileProviderの読み取り専用URIを使い、ユーザーが選択したファイルのみをAndroidの共有メニューへ渡します。不要なストレージ権限と通話録音は使用していません。

本番公開前には自分のAdMobアプリの登録、対象地域に合わせたUMPメッセージ設定、公開用プライバシーポリシー、Data safety申告、リリース署名、Google Playの最新ターゲットAPI要件の確認が必要です。本番広告IDへの置き換えは承認を得てから行います。

## テスト

単体テスト: WAV仕様、途中ファイル修復、入力範囲の検証、トリミングのサンプル一致と元データ保全、波形値、品質と形式の整合性。

エミュレーター/実機上の統合テスト:

```sh
./gradlew connectedDebugAndroidTest
```

10分以上のバックグラウンド・画面ロック録音テストを含める場合:

```sh
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.longRecording=true
```

統合テストはテスト用録音をアプリ内に追加します。通常は10分テストをスキップします。エミュレーターでのマイク入出力は実機の音質確認を代替しません。確認結果は `TEST_REPORT.md`、実機での手順は `DEVICE_CHECKLIST.md` を参照してください。

## 拡張と完成判定

StandardはAAC 96 kbps、High QualityはAAC 192 kbps、LosslessはWAV PCMです。形式だけを別に選ぶ設定を用意せず、不正な組み合わせを防ぎます。MP3/FLACは将来、コーデック実装と品質設定を追加します。初期版では選択できません。

Phase 1の実録音・保存・再生の検証を終えるまでは、Phase 2/3のコードがあっても完成扱いにしません。ストア公開やGitHub Actionsの成功も、実行結果が得られるまで完了扱いにしません。

実装ソースの最終ビルド・単体テスト6件・Lint（エラー0、警告19）は成功しました。一方、仮想端末の起動失敗と空き容量不足のため、Android上の実録音・再生・UI検証は未実行です。開発版APKを用意していますが、完成版としては扱っていません。

手動実行専用の `.github/workflows/android-integration.yml` も用意しています。Actionsの `Android integration tests` から起動し、10分テストが必要な場合はlongRecordingを有効にします。GitHubへの反映と実行は承認後に行います。

CIのdebug署名鍵はランナーごとに変わる場合があります。録音を保持したまま更新するには同じ鍵が必要です。署名不一致の際にアンインストールせず、先に大切な録音を共有して保存してください。公開版の署名とAABでの両言語同梱は公開準備で設定します。

実装・ビルド手順の参考: [AndroidのForeground Service](https://developer.android.com/develop/background-work/services/fgs/service-types)、[AdMobテスト広告](https://developers.google.com/admob/android/test-ads)、[エミュレーターのCLI](https://developer.android.com/studio/run/emulator-commandline)、[CIエミュレーターの設定](https://github.com/ReactiveCircus/android-emulator-runner)。
