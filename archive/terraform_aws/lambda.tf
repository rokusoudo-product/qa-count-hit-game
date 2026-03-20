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

# ── Sprint 2: Game Logic ──────────────────────────────────────
data "archive_file" "preset_questions" {
  type        = "zip"
  source_file = "${path.module}/../lambda/questions/preset_questions.py"
  output_path = "${path.module}/.build/preset_questions.zip"
}

data "archive_file" "start_game" {
  type        = "zip"
  source_dir  = "${path.module}/../lambda/game"
  output_path = "${path.module}/.build/start_game.zip"
}

data "archive_file" "submit_answer" {
  type        = "zip"
  source_dir  = "${path.module}/../lambda/game"
  output_path = "${path.module}/.build/submit_answer.zip"
}

data "archive_file" "submit_prediction" {
  type        = "zip"
  source_dir  = "${path.module}/../lambda/game"
  output_path = "${path.module}/.build/submit_prediction.zip"
}

data "archive_file" "phase_timeout" {
  type        = "zip"
  source_dir  = "${path.module}/../lambda/game"
  output_path = "${path.module}/.build/phase_timeout.zip"
}

resource "aws_lambda_function" "preset_questions" {
  function_name    = "${local.prefix}-preset-questions"
  filename         = data.archive_file.preset_questions.output_path
  source_code_hash = data.archive_file.preset_questions.output_base64sha256
  role             = aws_iam_role.lambda_exec.arn
  handler          = "preset_questions.handler"
  runtime          = "python3.11"
  environment { variables = local.lambda_env }
}

resource "aws_lambda_function" "start_game" {
  function_name    = "${local.prefix}-start-game"
  filename         = data.archive_file.start_game.output_path
  source_code_hash = data.archive_file.start_game.output_base64sha256
  role             = aws_iam_role.lambda_exec.arn
  handler          = "start_game.handler"
  runtime          = "python3.11"
  environment { variables = local.lambda_env }
}

resource "aws_lambda_function" "submit_answer" {
  function_name    = "${local.prefix}-submit-answer"
  filename         = data.archive_file.submit_answer.output_path
  source_code_hash = data.archive_file.submit_answer.output_base64sha256
  role             = aws_iam_role.lambda_exec.arn
  handler          = "submit_answer.handler"
  runtime          = "python3.11"
  environment { variables = local.lambda_env }
}

resource "aws_lambda_function" "submit_prediction" {
  function_name    = "${local.prefix}-submit-prediction"
  filename         = data.archive_file.submit_prediction.output_path
  source_code_hash = data.archive_file.submit_prediction.output_base64sha256
  role             = aws_iam_role.lambda_exec.arn
  handler          = "submit_prediction.handler"
  runtime          = "python3.11"
  environment { variables = local.lambda_env }
}

resource "aws_lambda_function" "phase_timeout" {
  function_name    = "${local.prefix}-phase-timeout"
  filename         = data.archive_file.phase_timeout.output_path
  source_code_hash = data.archive_file.phase_timeout.output_base64sha256
  role             = aws_iam_role.lambda_exec.arn
  handler          = "phase_timeout.handler"
  runtime          = "python3.11"
  environment { variables = local.lambda_env }
}
