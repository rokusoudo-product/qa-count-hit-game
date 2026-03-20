locals {
  lambda_env = {
    ROOMS_TABLE       = aws_dynamodb_table.rooms.name
    PLAYERS_TABLE     = aws_dynamodb_table.players.name
    ANSWERS_TABLE     = aws_dynamodb_table.answers.name
    CONNECTIONS_TABLE = aws_dynamodb_table.connections.name
    WEBSOCKET_API_URL = "https://${aws_apigatewayv2_api.websocket.id}.execute-api.${var.aws_region}.amazonaws.com/${var.environment}"
    ROOM_TTL_HOURS    = tostring(var.room_ttl_hours)
  }
}

# ── パッケージ化 ────────────────────────────────────────────
data "archive_file" "websocket_connect" {
  type        = "zip"
  source_file = "${path.module}/../lambda/websocket/connect.py"
  output_path = "${path.module}/.build/websocket_connect.zip"
}

data "archive_file" "websocket_disconnect" {
  type        = "zip"
  source_file = "${path.module}/../lambda/websocket/disconnect.py"
  output_path = "${path.module}/.build/websocket_disconnect.zip"
}

data "archive_file" "create_room" {
  type        = "zip"
  source_file = "${path.module}/../lambda/rooms/create_room.py"
  output_path = "${path.module}/.build/create_room.zip"
}

data "archive_file" "get_qr" {
  type        = "zip"
  source_file = "${path.module}/../lambda/rooms/get_qr.py"
  output_path = "${path.module}/.build/get_qr.zip"
}

data "archive_file" "join_room" {
  type        = "zip"
  source_file = "${path.module}/../lambda/rooms/join_room.py"
  output_path = "${path.module}/.build/join_room.zip"
}

# ── Lambda Functions ─────────────────────────────────────────
resource "aws_lambda_function" "ws_connect" {
  function_name    = "${local.prefix}-ws-connect"
  filename         = data.archive_file.websocket_connect.output_path
  source_code_hash = data.archive_file.websocket_connect.output_base64sha256
  role             = aws_iam_role.lambda_exec.arn
  handler          = "connect.handler"
  runtime          = "python3.11"
  environment { variables = local.lambda_env }
}

resource "aws_lambda_function" "ws_disconnect" {
  function_name    = "${local.prefix}-ws-disconnect"
  filename         = data.archive_file.websocket_disconnect.output_path
  source_code_hash = data.archive_file.websocket_disconnect.output_base64sha256
  role             = aws_iam_role.lambda_exec.arn
  handler          = "disconnect.handler"
  runtime          = "python3.11"
  environment { variables = local.lambda_env }
}

resource "aws_lambda_function" "create_room" {
  function_name    = "${local.prefix}-create-room"
  filename         = data.archive_file.create_room.output_path
  source_code_hash = data.archive_file.create_room.output_base64sha256
  role             = aws_iam_role.lambda_exec.arn
  handler          = "create_room.handler"
  runtime          = "python3.11"
  environment { variables = local.lambda_env }
}

resource "aws_lambda_function" "get_qr" {
  function_name    = "${local.prefix}-get-qr"
  filename         = data.archive_file.get_qr.output_path
  source_code_hash = data.archive_file.get_qr.output_base64sha256
  role             = aws_iam_role.lambda_exec.arn
  handler          = "get_qr.handler"
  runtime          = "python3.11"
  layers           = [aws_lambda_layer_version.qrcode_layer.arn]
  environment { variables = local.lambda_env }
}

resource "aws_lambda_function" "join_room" {
  function_name    = "${local.prefix}-join-room"
  filename         = data.archive_file.join_room.output_path
  source_code_hash = data.archive_file.join_room.output_base64sha256
  role             = aws_iam_role.lambda_exec.arn
  handler          = "join_room.handler"
  runtime          = "python3.11"
  environment { variables = local.lambda_env }
}

# ── Lambda Layer: qrcode ──────────────────────────────────────
resource "aws_lambda_layer_version" "qrcode_layer" {
  layer_name          = "${local.prefix}-qrcode"
  filename            = "${path.module}/../layers/qrcode_layer.zip"
  compatible_runtimes = ["python3.11"]
  description         = "qrcode + Pillow libraries"
}
