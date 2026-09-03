# Universal Unlimited Pipe (UUP)

[![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen.svg)](https://minecraft.net/)
[![Forge 47.3.0+](https://img.shields.io/badge/Forge-47.3.0+-orange.svg)](https://files.minecraftforge.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## 📥 ビルド済み Mod JAR（直ダウンロード）
👉 **[uup-1.20.1-1.0.0.jar をダウンロード (releases/)](https://github.com/sabu8190/Universal-Unlimited-Pipe/raw/main/releases/uup-1.20.1-1.0.0.jar)**

---

**Universal Unlimited Pipe (UUP)** は、Minecraft Forge 1.20.1 向けの超高速・超軽量・高機能な配管＆無線輸送ネットワークMODです。

---

## ✨ 主な特徴


- **⚡ 超高速＆超軽量**:
  - パイプ自身はTick処理を行わず、コントローラー単一ブロックによる中央集権型高速ルーティングでサーバー負荷ゼロを実現。
- **📦 全リソース対応の専用パイプラインナップ**:
  - **アイテムパイプ (`item_pipe`)**: 最大 21億 アイテム/t
  - **フルードパイプ (`fluid_pipe`)**: 最大 21億 mB/t
  - **エネルギーパイプ (`energy_pipe`)**: 最大 922京 FE/t
  - **ガスパイプ (`gas_pipe`)**: Mekanism気体・化学物質対応（最大 922京 /t）
  - **ユニバーサルパイプ (`pipe`)**: 全リソース同時輸送対応の万能パイプ
- **🎮 2通りの接続スタイルを両立**:
  - **直結モード**: 機械やチェストにパイプをそのまま直結して即座に高速輸送。
  - **ノードパーツモード**: 機械面にペタッと貼り付ける「トランスファーノード」で詳細な搬入出・優先度・チャンネルを制御。
- **📡 無線輸送（ネットワークリンクカード）**:
  - 離れた場所や異次元（ディメンション間）のチェスト/機械の座標をカードに記憶させ、コントローラーに挿すだけでワイヤレス転送。
- **⏱️ オーバークロックアップグレード**:
  - コントローラーに挿入することで転送レートが指数関数的に向上。

---

## 🛠️ ビルド方法

```bash
./gradlew build
```
ビルド完了後、`build/libs/uup-1.20.1-1.0.0.jar` に生成されます。
