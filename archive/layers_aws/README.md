# Lambda Layer: qrcode

`get_qr` Lambda用のレイヤーです。

## ビルド手順

```bash
mkdir -p python
pip install qrcode[pil] Pillow -t python/
zip -r qrcode_layer.zip python/
```

生成された `qrcode_layer.zip` をこのディレクトリに配置してください。
Terraformの `lambda.tf` が自動的に参照します。
